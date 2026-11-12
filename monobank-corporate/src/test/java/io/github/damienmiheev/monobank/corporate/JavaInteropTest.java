package io.github.damienmiheev.monobank.corporate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.damienmiheev.monobank.core.MonobankClientOptions;
import io.github.damienmiheev.monobank.core.MonobankException;
import io.github.damienmiheev.monobank.core.RetryPolicy;
import io.github.damienmiheev.monobank.corporate.access.AccessRequestStatus;
import io.github.damienmiheev.monobank.corporate.client.ClientApi;
import io.github.damienmiheev.monobank.corporate.company.RegistrationRequest;
import io.github.damienmiheev.monobank.corporate.signature.CreateSignatureRequest;
import io.github.damienmiheev.monobank.corporate.signature.DocumentType;
import io.github.damienmiheev.monobank.corporate.signature.HashType;
import io.github.damienmiheev.monobank.corporate.signature.SignDocument;
import io.github.damienmiheev.monobank.corporate.signature.SignatureStatus;
import io.github.damienmiheev.monobank.corporate.signature.SigningStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

/** Compiles and runs the corporate calls from Java (the opt-in annotation is invisible here). */
class JavaInteropTest {
    private static String resource(String name) throws IOException {
        try (InputStream in = JavaInteropTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void javaCallersCanUseTheCorporateApi() throws Exception {
        String pem = resource("/keys/test-sec1.pem");
        CorporateKey key = CorporateKey.fromPem(pem);
        assertThat(key.getKeyId()).isEqualTo("9cfcc2eee16244c5d22082930e9a00e9d2ce441e");
        assertThat(key.getPublicKey().getPublicKeyPemBase64()).startsWith("LS0tLS1CRUdJTiBQVUJMSUMgS0VZ");
        assertThat(key.sign(new byte[] {1, 2, 3})).hasSize(64);

        try (MockWebServer server = new MockWebServer()) {
            server.start();
            CorporateConfig config = CorporateConfig.builder(pem)
                    .signatureEncoding(SignatureEncoding.P1363)
                    .signQueryString(false)
                    .clock(Clock.systemUTC())
                    .options(MonobankClientOptions.builder()
                            .baseUrl(server.url("/").toString())
                            .retryPolicy(RetryPolicy.NONE)
                            .requestTimeout(Duration.ofSeconds(5))
                            .build())
                    .build();
            MonobankCorporate mono = new MonobankCorporate(config);
            assertThat(MonobankCorporate.create(pem).getConfig().getKeyId()).isEqualTo(key.getKeyId());

            server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"errorDescription\":\"Request is unauthorized yet\"}"));
            assertThat(mono.getAccess().status("req-1")).isEqualTo(AccessRequestStatus.PENDING);
            RecordedRequest recorded = server.takeRequest();
            assertThat(recorded.getHeader("X-Key-Id")).isEqualTo(key.getKeyId());
            assertThat(recorded.getHeader("X-Request-Id")).isEqualTo("req-1");

            server.enqueue(new MockResponse().setBody("[]"));
            ClientApi client = mono.client("req-1");
            assertThat(client.statement(ClientApi.DEFAULT_ACCOUNT, Instant.now().minusSeconds(3600))).isEmpty();

            server.enqueue(new MockResponse().setResponseCode(403).setBody("{\"errorDescription\":\"Forbidden\"}"));
            try {
                mono.getCompany().settings();
            } catch (MonobankException e) {
                assertThat(e.getOutcome()).isNotNull();
            }
        }

        RegistrationRequest registration = RegistrationRequest.builder()
                .pubkey(key.getPublicKey())
                .name("Company")
                .description("PFM")
                .contactPerson("Roman")
                .phone("380671234567")
                .email("a@example.com")
                .logo(new byte[] {1})
                .build();
        assertThat(registration.getPubkey()).isEqualTo(key.getPublicKey().getPublicKeyPemBase64());

        CreateSignatureRequest signature = CreateSignatureRequest.builder(Collections.singletonList(
                        SignDocument.builder("Agreement", "a421fd")
                                .hashType(HashType.DSTU256)
                                .type(DocumentType.PDF)
                                .build()))
                .oneSigner(false)
                .build();
        assertThat(signature.getDocuments()).hasSize(1);

        SignatureStatus parsed = SignatureStatus.fromJson(
                "{\"requestId\":\"r\",\"documents\":[{\"status\":\"signed\",\"hash\":\"ab\"}]}");
        assertThat(parsed.getDocuments().get(0).getStatus()).isEqualTo(SigningStatus.SIGNED);

        CorporateSigner custom = new CorporateSigner() {
            @Override
            public String getKeyId() {
                return key.getKeyId();
            }

            @Override
            public byte[] sign(byte[] data) {
                return key.sign(data);
            }
        };
        assertThat(CorporateConfig.builder(custom).build().getKeyId()).isEqualTo(key.getKeyId());
        assertThatThrownBy(() -> CorporateKey.fromPem("nope")).isInstanceOf(IllegalArgumentException.class);
    }
}
