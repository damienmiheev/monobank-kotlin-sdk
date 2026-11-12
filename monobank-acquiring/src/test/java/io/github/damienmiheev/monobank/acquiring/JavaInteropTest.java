package io.github.damienmiheev.monobank.acquiring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus;
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo;
import io.github.damienmiheev.monobank.acquiring.common.PaymentType;
import io.github.damienmiheev.monobank.acquiring.common.SaveCardData;
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest;
import io.github.damienmiheev.monobank.acquiring.invoice.InvoiceStatusResponse;
import io.github.damienmiheev.monobank.acquiring.webhook.VerificationResult;
import io.github.damienmiheev.monobank.acquiring.webhook.WebhookParser;
import io.github.damienmiheev.monobank.acquiring.webhook.WebhookVerifier;
import io.github.damienmiheev.monobank.core.CurrencyCodes;
import io.github.damienmiheev.monobank.core.MinorUnits;
import io.github.damienmiheev.monobank.core.MonobankClientOptions;
import io.github.damienmiheev.monobank.core.MonobankException;
import io.github.damienmiheev.monobank.core.RequestOutcome;
import io.github.damienmiheev.monobank.core.RetryPolicy;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Compiles and runs the calls a Java payment plugin makes. */
class JavaInteropTest {
    @Test
    void javaCallersCanBuildEverything() {
        MonobankAcquiring mono = new MonobankAcquiring(
                AcquiringConfig.builder("token")
                        .cmsName("killbill")
                        .options(MonobankClientOptions.builder()
                                .requestTimeout(Duration.ofSeconds(20))
                                .retryPolicy(RetryPolicy.NONE)
                                .build())
                        .build());
        assertThat(mono.getInvoices()).isNotNull();
        assertThat(mono.getWebhooks()).isNotNull();
        assertThat(MonobankAcquiring.create("token").getConfig().getToken()).isEqualTo("token");

        CreateInvoiceRequest request = CreateInvoiceRequest.builder(MinorUnits.toMinor(new BigDecimal("499.00"), CurrencyCodes.UAH))
                .ccy(CurrencyCodes.UAH)
                .paymentType(PaymentType.DEBIT)
                .merchantPaymInfo(MerchantPaymInfo.builder().reference("kb-payment-1").destination("Seqra").build())
                .saveCardData(SaveCardData.saveTo("account-1"))
                .webHookUrl("https://example.com/hook")
                .build();
        assertThat(request.getAmount()).isEqualTo(49_900L);

        InvoiceStatusResponse parsed = WebhookParser.parse(
                "{\"invoiceId\":\"i\",\"status\":\"hold\",\"amount\":49900,\"ccy\":980}");
        assertThat(parsed.getStatus()).isEqualTo(InvoiceStatus.HOLD);
        assertThat(MinorUnits.toMajor(parsed.getAmount(), parsed.getCcy())).isEqualByComparingTo("499.00");

        WebhookVerifier verifier = WebhookVerifier.ofPublicKey(
                "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEAG5/6w6vnlbYoFfDyXXN/KoBmUcN75bRQh80PahGevrhjqBBr79sRKBRnzG81TeT98ANjE5sDwFfyfxnoFIrfA==");
        assertThat(verifier.check(null, new byte[0])).isEqualTo(VerificationResult.INVALID_SIGNATURE);

        try {
            throw new io.github.damienmiheev.monobank.core.MonobankNetworkException("x", null, RequestOutcome.NOT_SENT);
        } catch (MonobankException e) {
            assertThat(e.getOutcome()).isEqualTo(RequestOutcome.NOT_SENT);
        }
    }
}
