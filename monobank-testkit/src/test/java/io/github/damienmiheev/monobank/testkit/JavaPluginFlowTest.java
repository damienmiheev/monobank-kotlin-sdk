package io.github.damienmiheev.monobank.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.damienmiheev.monobank.acquiring.AcquiringConfig;
import io.github.damienmiheev.monobank.acquiring.MonobankAcquiring;
import io.github.damienmiheev.monobank.acquiring.common.BasketItem;
import io.github.damienmiheev.monobank.acquiring.common.CardTokenStatus;
import io.github.damienmiheev.monobank.acquiring.common.InitiationKind;
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus;
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo;
import io.github.damienmiheev.monobank.acquiring.common.OperationStatus;
import io.github.damienmiheev.monobank.acquiring.common.SaveCardData;
import io.github.damienmiheev.monobank.acquiring.invoice.CancelInvoiceRequest;
import io.github.damienmiheev.monobank.acquiring.invoice.CancelInvoiceResponse;
import io.github.damienmiheev.monobank.acquiring.invoice.CancelListItem;
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest;
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceResponse;
import io.github.damienmiheev.monobank.acquiring.invoice.InvoiceStatusResponse;
import io.github.damienmiheev.monobank.acquiring.invoice.PaymentResponse;
import io.github.damienmiheev.monobank.acquiring.wallet.WalletPaymentRequest;
import io.github.damienmiheev.monobank.core.MonobankNetworkException;
import io.github.damienmiheev.monobank.core.MonobankSignatureException;
import io.github.damienmiheev.monobank.core.RequestOutcome;
import io.github.damienmiheev.monobank.core.RetryPolicy;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Kill Bill plugin's flow, written in Java: hosted payment with card saving, webhooks verified
 * with the SDK, a saved-card renewal, a partial refund and a status check.
 */
class JavaPluginFlowTest {
    private FakeMonobankAcquiring fake;
    private MonobankAcquiring mono;
    private HttpServer notifications;
    private ExecutorService notificationThreads;
    private final List<InvoiceStatusResponse> verified = new CopyOnWriteArrayList<>();
    private final List<String> rejected = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        fake = FakeMonobankAcquiring.builder()
                .tokens("tenant-token")
                .clock(TestClock.system())
                .webhookRetries(3, Duration.ofMillis(20))
                .build()
                .start();
        mono = new MonobankAcquiring(AcquiringConfig.builder(fake.getToken())
                .cmsName("killbill")
                .cmsVersion("0.24.22")
                .options(fake.clientOptionsBuilder()
                        .requestTimeout(Duration.ofSeconds(2))
                        .retryPolicy(RetryPolicy.of(2, Duration.ZERO, Duration.ZERO))
                        .build())
                .build());

        // The plugin's POST /notification: verify the raw bytes, then parse; 200 for valid, 400 for invalid.
        notificationThreads = Executors.newFixedThreadPool(2);
        notifications = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        notifications.createContext("/notification", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String xSign = exchange.getRequestHeaders().getFirst("X-Sign");
            int status;
            try {
                verified.add(mono.getWebhooks().verifyAndParse(xSign, body));
                status = 200;
            } catch (MonobankSignatureException e) {
                rejected.add(e.getReason().name());
                status = 400;
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        notifications.setExecutor(notificationThreads);
        notifications.start();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        fake.close();
        notifications.stop(0);
        notificationThreads.shutdownNow();
        notificationThreads.awaitTermination(5, TimeUnit.SECONDS);
    }

    private String webhookUrl() {
        return "http://127.0.0.1:" + notifications.getAddress().getPort() + "/notification";
    }

    @Test
    void hostedPaymentWithCardSavingThenRenewalThenPartialRefund() {
        // 1. AUTHORIZE/PURCHASE with hosted=true and saveCard=true
        CreateInvoiceResponse created = mono.getInvoices().create(CreateInvoiceRequest.builder(10_000)
                .ccy(980)
                .validity(3600L)
                .webHookUrl(webhookUrl())
                .saveCardData(SaveCardData.saveTo("kb-account-1"))
                .merchantPaymInfo(MerchantPaymInfo.builder()
                        .reference("kb-tx-1")
                        .destination("Subscription")
                        .basketOrder(Collections.singletonList(
                                BasketItem.builder("Plan", BigDecimal.ONE, 10_000, "plan-pro").build()))
                        .build())
                .build());
        assertThat(created.getPageUrl()).startsWith(fake.getBaseUrl());

        // 2. the customer pays on the hosted page
        fake.payInvoice(created.getInvoiceId());

        // 3. three signed webhooks: processing, success, and the card webhook
        List<RecordedWebhook> sent = fake.awaitWebhooks(3, Duration.ofSeconds(5));
        assertThat(sent).allMatch(w -> w.getOutcome() == WebhookOutcome.DELIVERED);
        assertThat(verified).hasSize(3);
        assertThat(rejected).isEmpty();
        InvoiceStatusResponse latest = verified.stream()
                .max(Comparator.comparing(InvoiceStatusResponse::getModifiedDate))
                .orElseThrow();
        assertThat(latest.getStatus()).isEqualTo(InvoiceStatus.SUCCESS);
        assertThat(latest.getReference()).isEqualTo("kb-tx-1");
        String cardToken = verified.stream()
                .filter(w -> w.getWalletData() != null && w.getWalletData().getStatus() == CardTokenStatus.CREATED)
                .findFirst()
                .orElseThrow()
                .getWalletData()
                .getCardToken();
        assertThat(cardToken).isNotBlank();

        // 4. renewal: charge the saved card, merchant-initiated
        PaymentResponse renewal = mono.getWallet().payment(WalletPaymentRequest
                .builder(cardToken, 10_000, 980, InitiationKind.MERCHANT)
                .webHookUrl(webhookUrl())
                .merchantPaymInfo(MerchantPaymInfo.builder().reference("kb-tx-2").build())
                .build());
        assertThat(renewal.getStatus()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(fake.requestCount(MonobankPaths.WALLET_PAYMENT)).isEqualTo(1);

        // 5. partial refund with our extRef
        CancelInvoiceResponse refund = mono.getInvoices().cancel(CancelInvoiceRequest.builder(renewal.getInvoiceId())
                .amount(2_500L)
                .extRef("kb-refund-1")
                .build());
        assertThat(refund.getStatus()).isEqualTo(OperationStatus.PROCESSING);

        // 6. status: the refund settled, finalAmount lowered
        InvoiceStatusResponse status = mono.getInvoices().status(renewal.getInvoiceId());
        assertThat(status.getStatus()).isEqualTo(InvoiceStatus.REVERSED); // any refund reverses, verified live
        assertThat(status.getFinalAmount()).isEqualTo(7_500L);
        CancelListItem item = status.cancelFor("kb-refund-1");
        assertThat(item).isNotNull();
        assertThat(item.getStatus()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(item.getAmount()).isEqualTo(2_500L);

        // the refund's webhook carries the cancelList entry too
        fake.awaitWebhooks(6, Duration.ofSeconds(5));
        assertThat(verified.get(verified.size() - 1).cancelFor("kb-refund-1")).isNotNull();

        // reconcile path: both payments appear in the statement with our references
        assertThat(mono.getMerchant().findByReference("kb-tx-2", Instant.now().minusSeconds(300), null, null))
                .hasSize(1);
    }

    @Test
    void timeoutAfterTheChargeReachedMonobankIsUnknownAndFoundByReference() {
        String token = fake.addCard("kb-account-2");
        fake.injectFault(MonobankPaths.WALLET_PAYMENT, Fault.timeout());
        MonobankAcquiring impatient = new MonobankAcquiring(AcquiringConfig.builder(fake.getToken())
                .options(fake.clientOptionsBuilder().requestTimeout(Duration.ofMillis(300)).build())
                .build());

        assertThatThrownBy(() -> impatient.getWallet().payment(WalletPaymentRequest
                        .builder(token, 500, 980, InitiationKind.MERCHANT)
                        .merchantPaymInfo(MerchantPaymInfo.builder().reference("kb-tx-3").build())
                        .build()))
                .isInstanceOfSatisfying(MonobankNetworkException.class,
                        e -> assertThat(e.getOutcome()).isEqualTo(RequestOutcome.UNKNOWN));

        assertThat(fake.requestCount(MonobankPaths.WALLET_PAYMENT)).isEqualTo(1);
        assertThat(mono.getMerchant().findByReference("kb-tx-3", Instant.now().minusSeconds(60), null, null))
                .singleElement()
                .satisfies(item -> assertThat(item.getStatus()).isEqualTo(InvoiceStatus.SUCCESS));
    }

    @Test
    void declinedPaymentAndBadSignatureFromJava() {
        String invoiceId = mono.getInvoices().create(CreateInvoiceRequest.builder(100)
                .webHookUrl(webhookUrl())
                .build()).getInvoiceId();
        fake.corruptNextWebhookSignatures(1);

        FakeInvoice invoice = fake.payInvoice(invoiceId, CustomerPayment.declined("6", "Do not honor"));

        assertThat(invoice.getStatus()).isEqualTo("failure");
        List<RecordedWebhook> sent = fake.awaitWebhooks(2, Duration.ofSeconds(5));
        assertThat(sent.get(0).getResponseStatus()).isEqualTo(400);
        assertThat(sent.get(0).getOutcome()).isEqualTo(WebhookOutcome.FAILED);
        assertThat(rejected).containsOnly("INVALID_SIGNATURE");
        assertThat(verified).singleElement()
                .satisfies(w -> assertThat(w.getErrCode()).isEqualTo("6"));
        assertThat(fake.toString()).doesNotContain("tenant-token");
    }

    @Test
    void javaCallersCanCatchBindFailuresAndUseSeveralMerchants() {
        // start() throws only unchecked exceptions, so a Java fixture can catch them by type
        FakeMonobankAcquiring busy = FakeMonobankAcquiring.builder().port(fake.getPort()).build();
        try {
            busy.start();
            throw new AssertionError("expected the port to be in use");
        } catch (UncheckedIOException expected) {
            assertThat(expected.getCause()).isInstanceOf(java.io.IOException.class);
        } finally {
            busy.close();
        }

        try (FakeMonobankAcquiring tenants = FakeMonobankAcquiring.builder()
                .tokens("tenant-a")
                .addMerchant(MerchantSetup.builder("tenant-b").build())
                .build()
                .start()) {
            FakeMerchant tenantB = tenants.merchant("tenant-b");
            String card = tenantB.addCard("customer");
            tenantB.enqueueWalletPaymentOutcome(WalletPaymentOutcome.declined());
            MonobankAcquiring clientB = new MonobankAcquiring(AcquiringConfig.builder(tenantB.getToken())
                    .options(tenants.clientOptions())
                    .build());
            PaymentResponse declined = clientB.getWallet().payment(
                    WalletPaymentRequest.builder(card, 100, 980, InitiationKind.MERCHANT).build());
            assertThat(declined.getStatus()).isEqualTo(OperationStatus.FAILURE);
            assertThat(tenants.getMerchant().invoices()).isEmpty();
            assertThat(tenantB.invoices()).hasSize(1);
            tenants.dropNextWebhooks(1, w -> w.getKind() == WebhookKind.CARD);
            tenants.injectFault(MonobankPaths.WALLET_PAYMENT, Fault.answerAfterWebhooks(), 1, r -> r.getBody().contains(card));
        }
    }
}
