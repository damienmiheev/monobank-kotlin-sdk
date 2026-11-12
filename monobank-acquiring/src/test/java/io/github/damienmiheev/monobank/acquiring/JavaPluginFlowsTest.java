package io.github.damienmiheev.monobank.acquiring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.damienmiheev.monobank.acquiring.common.AcquiringErrorCodes;
import io.github.damienmiheev.monobank.acquiring.common.CardTokenStatus;
import io.github.damienmiheev.monobank.acquiring.common.FiscalizationItem;
import io.github.damienmiheev.monobank.acquiring.common.InitiationKind;
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus;
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo;
import io.github.damienmiheev.monobank.acquiring.common.OperationStatus;
import io.github.damienmiheev.monobank.acquiring.common.Page;
import io.github.damienmiheev.monobank.acquiring.common.PaymentErrorCodes;
import io.github.damienmiheev.monobank.acquiring.common.SaveCardData;
import io.github.damienmiheev.monobank.acquiring.invoice.CancelInvoiceRequest;
import io.github.damienmiheev.monobank.acquiring.invoice.CancelInvoiceResponse;
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest;
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceResponse;
import io.github.damienmiheev.monobank.acquiring.invoice.FinalizeInvoiceRequest;
import io.github.damienmiheev.monobank.acquiring.invoice.InvoiceStatusResponse;
import io.github.damienmiheev.monobank.acquiring.invoice.PaymentResponse;
import io.github.damienmiheev.monobank.acquiring.merchant.StatementItem;
import io.github.damienmiheev.monobank.acquiring.monopay.MonopaySignedRequest;
import io.github.damienmiheev.monobank.acquiring.monopay.MonopaySigner;
import io.github.damienmiheev.monobank.acquiring.subscription.SubscriptionListItem;
import io.github.damienmiheev.monobank.acquiring.subscription.SubscriptionListQuery;
import io.github.damienmiheev.monobank.acquiring.wallet.WalletCard;
import io.github.damienmiheev.monobank.acquiring.wallet.WalletPaymentRequest;
import io.github.damienmiheev.monobank.acquiring.webhook.WebhookVerifier;
import io.github.damienmiheev.monobank.core.CurrencyCodes;
import io.github.damienmiheev.monobank.core.MonobankClientOptions;
import io.github.damienmiheev.monobank.core.MonobankServerException;
import io.github.damienmiheev.monobank.core.MonobankSignatureException;
import io.github.damienmiheev.monobank.core.RequestOutcome;
import io.github.damienmiheev.monobank.core.RetryPolicy;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Kill Bill plugin's flows, written in Java against a mock server. */
class JavaPluginFlowsTest {
    private MockWebServer server;
    private MonobankAcquiring mono;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        mono = new MonobankAcquiring(AcquiringConfig.builder("token")
                .cmsName("killbill")
                .cmsVersion("0.24.22")
                .options(MonobankClientOptions.builder()
                        .baseUrl(server.url("/").toString())
                        .retryPolicy(RetryPolicy.of(2, Duration.ZERO, Duration.ZERO))
                        .build())
                .build());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private void enqueue(int status, String body) {
        server.enqueue(new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body));
    }

    private String take(String method, String path) throws InterruptedException {
        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo(method);
        assertThat(request.getRequestUrl().encodedPath()).isEqualTo(path);
        assertThat(request.getHeader("X-Token")).isEqualTo("token");
        return request.getBody().readUtf8();
    }

    @Test
    void createWithSaveCardAndVerification() throws Exception {
        enqueue(200, "{\"invoiceId\":\"inv-1\",\"pageUrl\":\"https://pay.mbnk.biz/inv-1\"}");
        CreateInvoiceResponse created = mono.getInvoices().create(CreateInvoiceRequest.builder(49_900L)
                .ccy(CurrencyCodes.UAH)
                .merchantPaymInfo(MerchantPaymInfo.builder().reference("kb-tx-1").build())
                .saveCardData(SaveCardData.saveTo("account-1"))
                .webHookUrl("https://kb.example/hook")
                .build());
        assertThat(created.getInvoiceId()).isEqualTo("inv-1");
        assertThat(take("POST", "/api/merchant/invoice/create")).contains("\"saveCard\":true", "\"walletId\":\"account-1\"");

        enqueue(200, "{\"invoiceId\":\"inv-2\",\"pageUrl\":\"https://pay.mbnk.biz/inv-2\"}");
        mono.getInvoices().create(CreateInvoiceRequest.verification("account-1").build());
        assertThat(take("POST", "/api/merchant/invoice/create")).contains("\"paymentType\":\"verification\"", "\"amount\":0");
    }

    @Test
    void walletPaymentAndCards() throws Exception {
        enqueue(200, "{\"wallet\":[{\"cardToken\":\"tok-123456789\",\"maskedPan\":\"424242******4242\"}]}");
        List<WalletCard> cards = mono.getWallet().cards("account-1");
        assertThat(cards).hasSize(1);
        take("GET", "/api/merchant/wallet");

        enqueue(200, "{\"invoiceId\":\"inv-3\",\"tdsUrl\":\"https://3ds\",\"status\":\"processing\",\"amount\":100,\"ccy\":980}");
        PaymentResponse payment = mono.getWallet().payment(WalletPaymentRequest
                .builder(cards.get(0).getCardToken(), 100L, CurrencyCodes.UAH, InitiationKind.MERCHANT)
                .merchantPaymInfo(MerchantPaymInfo.builder().reference("kb-tx-2").build())
                .build());
        assertThat(payment.getStatus()).isEqualTo(OperationStatus.PROCESSING);
        assertThat(payment.isThreeDsRequired()).isTrue();
        assertThat(take("POST", "/api/merchant/wallet/payment")).contains("\"initiationKind\":\"merchant\"");

        enqueue(500, "{\"errCode\":\"INTERNAL_ERROR\"}");
        assertThatThrownBy(() -> mono.getWallet().payment(
                        WalletPaymentRequest.builder("t", 1L, 980, InitiationKind.CLIENT).build()))
                .isInstanceOfSatisfying(MonobankServerException.class,
                        e -> assertThat(e.getOutcome()).isEqualTo(RequestOutcome.UNKNOWN));
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void cancelFinalizeAndStatus() throws Exception {
        enqueue(200, "{\"status\":\"processing\",\"createdDate\":\"2024-05-01T12:30:00+03:00\"}");
        CancelInvoiceResponse cancel = mono.getInvoices().cancel(CancelInvoiceRequest.builder("inv-1")
                .extRef("kb-refund-1")
                .amount(100L)
                .items(Collections.singletonList(
                        FiscalizationItem.builder("Seqra", BigDecimal.ONE, 100L, "sku-1").build()))
                .build());
        assertThat(cancel.getStatus()).isEqualTo(OperationStatus.PROCESSING);
        take("POST", "/api/merchant/invoice/cancel");

        enqueue(500, "{\"errCode\":\"CANCEL_NOT_AVAILABLE\",\"errText\":\"no\"}");
        assertThatThrownBy(() -> mono.getInvoices().cancel(CancelInvoiceRequest.builder("inv-1").build()))
                .isInstanceOfSatisfying(MonobankServerException.class, e -> {
                    assertThat(e.getOutcome()).isEqualTo(RequestOutcome.REJECTED);
                    assertThat(e.getErrorCode()).isEqualTo(AcquiringErrorCodes.CANCEL_NOT_AVAILABLE);
                });
        take("POST", "/api/merchant/invoice/cancel");

        enqueue(200, "{\"status\":\"success\"}");
        assertThat(mono.getInvoices().finalize(FinalizeInvoiceRequest.of("inv-1", 100L)).getStatus())
                .isEqualTo(OperationStatus.SUCCESS);
        take("POST", "/api/merchant/invoice/finalize");

        enqueue(200, "{\"invoiceId\":\"inv-1\",\"status\":\"failure\",\"amount\":100,\"ccy\":980,\"errCode\":\"59\","
                + "\"cancelList\":[{\"status\":\"success\",\"extRef\":\"kb-refund-1\"}],"
                + "\"walletData\":{\"cardToken\":\"tok-123456789\",\"walletId\":\"account-1\",\"status\":\"created\"}}");
        InvoiceStatusResponse status = mono.getInvoices().status("inv-1");
        assertThat(status.getStatus()).isEqualTo(InvoiceStatus.FAILURE);
        assertThat(status.getStatusRaw()).isEqualTo("failure");
        assertThat(PaymentErrorCodes.describe(status.getErrCode()).getDescription()).contains("Insufficient");
        assertThat(status.cancelFor("kb-refund-1").getStatus()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(status.getWalletData().getStatus()).isEqualTo(CardTokenStatus.CREATED);
        assertThat(status.getWalletData().isCardSaved()).isTrue();
    }

    @Test
    void verifyAndParseWebhook() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        String keyFromApi = Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.US_ASCII));
        byte[] body = "{\"invoiceId\":\"inv-1\",\"status\":\"success\",\"amount\":100,\"ccy\":980}"
                .getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(body);
        String xSign = Base64.getEncoder().encodeToString(signer.sign());

        WebhookVerifier verifier = WebhookVerifier.ofPublicKey(keyFromApi);
        InvoiceStatusResponse parsed = verifier.verifyAndParse(xSign, body);
        assertThat(parsed.getStatus()).isEqualTo(InvoiceStatus.SUCCESS);
        assertThat(verifier.currentPublicKey()).isEqualTo(keyFromApi);
        assertThatThrownBy(() -> verifier.verifyAndParse(xSign, "{}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(MonobankSignatureException.class);

        enqueue(200, "{\"key\":\"" + keyFromApi + "\"}");
        assertThat(mono.getWebhooks().verify(xSign, body)).isTrue();
        assertThat(mono.getWebhooks().currentPublicKey()).isEqualTo(keyFromApi);
    }

    @Test
    void recoveryStatementSubscriptionsAndMonopay() throws Exception {
        enqueue(200, "{\"list\":[{\"invoiceId\":\"inv-9\",\"status\":\"success\",\"amount\":100,\"ccy\":980,"
                + "\"reference\":\"kb-tx-9\"}]}");
        List<StatementItem> found = mono.getMerchant().findByReference("kb-tx-9", Instant.ofEpochSecond(1_700_000_000L));
        assertThat(found).extracting(StatementItem::getInvoiceId).containsExactly("inv-9");
        take("GET", "/api/merchant/statement");

        enqueue(200, "{\"list\":[],\"pagination\":{\"totalItems\":0,\"itemsPerPage\":20,\"currentPage\":1,\"totalPages\":0}}");
        Page<SubscriptionListItem> page = mono.getSubscriptions().list(
                SubscriptionListQuery.builder(OffsetDateTime.parse("2024-01-01T00:00:00+02:00")).limit(20).build());
        assertThat(page.getItems()).isEmpty();
        assertThat(page.getHasNextPage()).isFalse();

        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        MonopaySignedRequest signed = MonopaySigner.of(generator.generateKeyPair().getPrivate())
                .sign("{\"amount\":100}", "req-1");
        assertThat(signed.getPayloadBase64()).isNotEmpty();
    }
}
