package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.damienmiheev.monobank.acquiring.AcquiringConfig;
import io.github.damienmiheev.monobank.acquiring.MonobankAcquiring;
import io.github.damienmiheev.monobank.acquiring.common.InvoiceStatus;
import io.github.damienmiheev.monobank.acquiring.common.MerchantPaymInfo;
import io.github.damienmiheev.monobank.acquiring.common.SaveCardData;
import io.github.damienmiheev.monobank.acquiring.invoice.CancelInvoiceRequest;
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest;
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceResponse;
import io.github.damienmiheev.monobank.acquiring.invoice.InvoiceStatusResponse;
import io.github.damienmiheev.monobank.core.MinorUnits;
import io.github.damienmiheev.monobank.core.MonobankClientOptions;
import io.github.damienmiheev.monobank.core.MonobankServerException;
import io.github.damienmiheev.monobank.core.MonobankSignatureException;
import io.github.damienmiheev.monobank.core.RequestOutcome;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The calls a Kill Bill payment plugin makes, on Kill Bill's dependency versions and Java 11. */
class KillBillConsumerTest {
    private HttpServer server;
    private KeyPair keyPair;
    private MonobankAcquiring mono;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = generator.generateKeyPair();
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        String pubkeyJson = "{\"key\":\"" + Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.US_ASCII)) + "\"}";

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/merchant/invoice/create", exchange -> respond(exchange, 200,
                "{\"invoiceId\":\"inv-1\",\"pageUrl\":\"https://pay.mbnk.biz/inv-1\"}"));
        server.createContext("/api/merchant/invoice/status", exchange -> respond(exchange, 200,
                "{\"invoiceId\":\"inv-1\",\"status\":\"success\",\"amount\":49900,\"ccy\":980,"
                        + "\"modifiedDate\":\"2024-05-01T12:30:00+03:00\","
                        + "\"walletData\":{\"cardToken\":\"tok\",\"walletId\":\"acc-1\",\"status\":\"created\"}}"));
        server.createContext("/api/merchant/invoice/cancel", exchange -> respond(exchange, 500,
                "{\"errCode\":\"INTERNAL_ERROR\",\"errText\":\"boom\"}"));
        server.createContext("/api/merchant/pubkey", exchange -> respond(exchange, 200, pubkeyJson));
        server.start();

        mono = new MonobankAcquiring(AcquiringConfig.builder("test-token")
                .options(MonobankClientOptions.builder()
                        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                        .build())
                .build());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void runsOnKillBillsJacksonAndJava11() {
        String expected = System.getProperty("expectedJacksonDatabind");
        String actual = com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION.toString().replace('-', '.');
        assertEquals(expected, actual, "Jackson databind on the classpath");
        assertTrue(System.getProperty("java.specification.version").equals("11")
                || Boolean.getBoolean("allowNewerJava"), "runs on Java 11");
    }

    @Test
    void firstChargeStatusAndWebhook() throws Exception {
        CreateInvoiceResponse created = mono.getInvoices().create(
                CreateInvoiceRequest.builder(MinorUnits.toMinor(new BigDecimal("499.00"), 980))
                        .merchantPaymInfo(MerchantPaymInfo.builder().reference("kb-1").build())
                        .saveCardData(SaveCardData.saveTo("acc-1"))
                        .build());
        assertEquals("inv-1", created.getInvoiceId());

        InvoiceStatusResponse status = mono.getInvoices().status("inv-1");
        assertEquals(InvoiceStatus.SUCCESS, status.getStatus());
        assertEquals(0, new BigDecimal("499.00").compareTo(MinorUnits.toMajor(status.getAmount(), status.getCcy())));
        assertEquals("tok", status.getWalletData().getCardToken());

        byte[] body = "{\"invoiceId\":\"inv-1\",\"status\":\"success\",\"amount\":49900,\"ccy\":980}"
                .getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(body);
        String xSign = Base64.getEncoder().encodeToString(signer.sign());
        assertEquals(InvoiceStatus.SUCCESS, mono.getWebhooks().verifyAndParse(xSign, body).getStatus());
        assertThrows(MonobankSignatureException.class, () -> mono.getWebhooks().verifyAndParse("AAAA", body));
    }

    @Test
    void serverErrorOnARefundHasAnUnknownOutcomeAndIsNotRetried() {
        MonobankServerException e = assertThrows(MonobankServerException.class, () ->
                mono.getInvoices().cancel(CancelInvoiceRequest.builder("inv-1").amount(100L).extRef("refund-1").build()));
        assertEquals(RequestOutcome.UNKNOWN, e.getOutcome());
        assertEquals("INTERNAL_ERROR", e.getErrorCode());
    }
}
