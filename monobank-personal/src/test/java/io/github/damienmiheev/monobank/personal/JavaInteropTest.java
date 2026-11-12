package io.github.damienmiheev.monobank.personal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.damienmiheev.monobank.core.MinorUnits;
import io.github.damienmiheev.monobank.core.MonobankClientOptions;
import io.github.damienmiheev.monobank.core.RetryPolicy;
import io.github.damienmiheev.monobank.personal.bank.CurrencyRate;
import io.github.damienmiheev.monobank.personal.bank.SyncInfo;
import io.github.damienmiheev.monobank.personal.client.Account;
import io.github.damienmiheev.monobank.personal.client.AccountType;
import io.github.damienmiheev.monobank.personal.client.CashbackType;
import io.github.damienmiheev.monobank.personal.client.ClientInfo;
import io.github.damienmiheev.monobank.personal.statement.StatementApi;
import io.github.damienmiheev.monobank.personal.statement.StatementItem;
import io.github.damienmiheev.monobank.personal.webhook.WebhookEvent;
import io.github.damienmiheev.monobank.personal.webhook.WebhookEventType;
import io.github.damienmiheev.monobank.personal.webhook.WebhookParser;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

/** Compiles and runs the calls a Java application makes. */
class JavaInteropTest {
    @Test
    void javaCallersCanUseTheClient() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setBody(
                    "{\"clientId\":\"c\",\"accounts\":[{\"id\":\"a\",\"balance\":12345,\"currencyCode\":980,"
                            + "\"type\":\"black\",\"cashbackType\":\"None\"}]}"));
            server.enqueue(new MockResponse().setBody(
                    "[{\"id\":\"t\",\"time\":1700000000,\"amount\":-500,\"currencyCode\":980}]"));
            server.enqueue(new MockResponse().setBody(
                    "[{\"currencyCodeA\":840,\"currencyCodeB\":980,\"date\":1700000000,\"rateBuy\":41.1}]"));
            server.enqueue(new MockResponse().setBody(
                    "{\"serverKeyId\":\"k\",\"serverPubKey\":\"p\",\"serverTimeMsec\":1700000000123}"));
            server.enqueue(new MockResponse());

            MonobankPersonal mono = new MonobankPersonal(
                    PersonalConfig.builder("token")
                            .rateLimiter(PersonalRateLimiter.create(Duration.ofSeconds(90)))
                            .options(MonobankClientOptions.builder()
                                    .baseUrl(server.url("/").toString())
                                    .requestTimeout(Duration.ofSeconds(20))
                                    .retryPolicy(RetryPolicy.NONE)
                                    .build())
                            .build());

            ClientInfo info = mono.getClient().info();
            Account account = info.getAccounts().get(0);
            assertThat(account.getType()).isEqualTo(AccountType.BLACK);
            assertThat(account.getCashbackType()).isEqualTo(CashbackType.NONE);
            assertThat(MinorUnits.toMajor(account.getBalance(), account.getCurrencyCode()))
                    .isEqualByComparingTo("123.45");

            List<StatementItem> items = mono.getStatements()
                    .list(StatementApi.DEFAULT_ACCOUNT, Instant.ofEpochSecond(1_699_999_000L));
            assertThat(items.get(0).getAmount()).isEqualTo(-500L);
            assertThat(StatementApi.MAX_RANGE_SECONDS).isEqualTo(2_682_000L);
            assertThat(StatementApi.PAGE_SIZE).isEqualTo(500);

            List<CurrencyRate> rates = mono.getBank().currency();
            assertThat(rates.get(0).getRateBuy()).isEqualByComparingTo("41.1");
            SyncInfo sync = mono.getBank().sync();
            assertThat(sync.getServerTime()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_123L));

            mono.getClient().setWebhook("https://example.com/hook/secret");
            assertThat(server.getRequestCount()).isEqualTo(5);

            // listAll without a configured limiter paces itself; one short range is one request.
            MonobankPersonal unlimited = new MonobankPersonal(PersonalConfig.builder("token")
                    .options(MonobankClientOptions.builder().baseUrl(server.url("/").toString()).build())
                    .build());
            server.enqueue(new MockResponse().setBody(
                    "[{\"id\":\"t\",\"time\":1700000000,\"amount\":-500,\"currencyCode\":980}]"));
            List<StatementItem> all = unlimited.getStatements()
                    .listAll("0", Instant.ofEpochSecond(1_699_999_000L), Instant.ofEpochSecond(1_700_000_100L));
            assertThat(all).hasSize(1);

            server.enqueue(new MockResponse().setBody("[]"));
            List<List<StatementItem>> pages = new ArrayList<>();
            // Each listAll call paces only its own requests, so this one does not wait either.
            unlimited.getStatements().listAll(StatementApi.DEFAULT_ACCOUNT,
                    Instant.ofEpochSecond(1_699_999_000L), Instant.ofEpochSecond(1_700_000_100L), pages::add);
            assertThat(pages).hasSize(1);
        }

        assertThat(MonobankPersonal.create("token").getConfig().getToken()).isEqualTo("token");
        MonobankPersonal publicClient = MonobankPersonal.publicOnly();
        assertThatThrownBy(() -> publicClient.getClient().info()).isInstanceOf(IllegalStateException.class);
        assertThat(PersonalRateLimiter.create().getInterval()).isEqualTo(PersonalRateLimiter.DEFAULT_INTERVAL);

        WebhookEvent event = WebhookParser.parse(("{\"type\":\"StatementItem\",\"data\":{\"account\":\"a\","
                + "\"statementItem\":{\"id\":\"t\",\"time\":1700000000,\"amount\":100,\"currencyCode\":980}}}")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(event.getType()).isEqualTo(WebhookEventType.STATEMENT_ITEM);
        assertThat(event.getData().getStatementItem().getTime()).isEqualTo(Instant.ofEpochSecond(1_700_000_000L));
    }
}
