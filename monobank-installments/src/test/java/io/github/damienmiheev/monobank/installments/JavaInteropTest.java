package io.github.damienmiheev.monobank.installments;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.damienmiheev.monobank.core.MonobankClientOptions;
import io.github.damienmiheev.monobank.core.RetryPolicy;
import io.github.damienmiheev.monobank.installments.callback.InstallmentsCallbackParser;
import io.github.damienmiheev.monobank.installments.callback.InstallmentsCallbackVerifier;
import io.github.damienmiheev.monobank.installments.common.InvoiceSource;
import io.github.damienmiheev.monobank.installments.common.OrderState;
import io.github.damienmiheev.monobank.installments.common.OrderSubState;
import io.github.damienmiheev.monobank.installments.common.Product;
import io.github.damienmiheev.monobank.installments.guarantee.GuaranteeLetterRequest;
import io.github.damienmiheev.monobank.installments.order.AvailableProgram;
import io.github.damienmiheev.monobank.installments.order.CreateOrderAdditionalParams;
import io.github.damienmiheev.monobank.installments.order.CreateOrderInvoice;
import io.github.damienmiheev.monobank.installments.order.CreateOrderRequest;
import io.github.damienmiheev.monobank.installments.order.OrderStateInfo;
import io.github.damienmiheev.monobank.installments.order.OrderStateResult;
import io.github.damienmiheev.monobank.installments.order.ReturnOrderRequest;
import io.github.damienmiheev.monobank.installments.qr.CreateQrCartRequest;
import io.github.damienmiheev.monobank.installments.qr.QrCartResult;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

/** Compiles and runs the calls a Java integration makes. */
class JavaInteropTest {
    @Test
    void javaCallersCanBuildEverything() {
        MonobankInstallments chast = new MonobankInstallments(
                InstallmentsConfig.builder("store", "secret")
                        .environment(InstallmentsEnvironment.SANDBOX)
                        .options(MonobankClientOptions.builder()
                                .requestTimeout(Duration.ofSeconds(20))
                                .retryPolicy(RetryPolicy.NONE)
                                .build())
                        .build());
        assertThat(chast.getOrders()).isNotNull();
        assertThat(chast.getClients()).isNotNull();
        assertThat(chast.getGuaranteeLetters()).isNotNull();
        assertThat(chast.getStore()).isNotNull();
        assertThat(chast.getQrCarts()).isNotNull();
        assertThat(chast.getConfig().getBaseUrl()).isEqualTo("https://u2-demo-ext.mono.st4g3.com");
        assertThat(MonobankInstallments.create("store", "secret").getConfig().getStoreId()).isEqualTo("store");

        CreateOrderRequest request = CreateOrderRequest.builder(
                        "ORD-1",
                        "+380501234561",
                        new BigDecimal("2499.99"),
                        CreateOrderInvoice.of("INV-1", LocalDate.of(2024, 1, 15), InvoiceSource.INTERNET),
                        Collections.singletonList(AvailableProgram.of(Arrays.asList(3, 6, 10))),
                        Collections.singletonList(Product.of("TV", 1, new BigDecimal("2499.99"))))
                .resultCallback("https://shop.example/callback")
                .additionalParams(CreateOrderAdditionalParams.builder().nds(new BigDecimal("416.67")).build())
                .build();
        assertThat(request.getTotalSum()).isEqualByComparingTo("2499.99");

        ReturnOrderRequest refund = ReturnOrderRequest.builder("order", new BigDecimal("100.00"), "ret-1", true).build();
        assertThat(refund.getReturnMoneyToCard()).isTrue();
        CreateQrCartRequest cart = CreateQrCartRequest.of(
                "qr", "fa4a8249-336e-4e6d-9b85-79bc8be62377",
                Collections.singletonList(Product.of("TV", 1, BigDecimal.TEN)), "https://shop.example/callback");
        assertThat(cart.getProducts()).hasSize(1);
        assertThat(GuaranteeLetterRequest.of("order", "INV-1", LocalDate.of(2024, 1, 23)).getInvoice()).isNotNull();

        byte[] body = "{\"order_id\":\"o\",\"state\":\"IN_PROCESS\",\"order_sub_state\":\"WAITING_FOR_STORE_CONFIRM\"}"
                .getBytes(StandardCharsets.UTF_8);
        InstallmentsCallbackVerifier verifier = new InstallmentsCallbackVerifier("secret");
        String signature = verifier.sign(body);
        OrderStateInfo callback = verifier.verifyAndParse(signature, body);
        assertThat(callback.getState()).isEqualTo(OrderState.IN_PROCESS);
        assertThat(callback.getSubState()).isEqualTo(OrderSubState.WAITING_FOR_STORE_CONFIRM);
        assertThat(callback.isWaitingForStoreConfirm()).isTrue();
        assertThat(chast.getCallbacks().verify(signature, body)).isTrue();
        assertThat(InstallmentsCallbackVerifier.SIGNATURE_HEADER).isEqualTo("signature");
        assertThat(InstallmentsCallbackParser.parse(new String(body, StandardCharsets.UTF_8)).getOrderId()).isEqualTo("o");
    }

    @Test
    void javaCallersHandleTypedResults() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"message\":\"not found\"}"));
            server.enqueue(new MockResponse().setResponseCode(201).setBody("{\"id\":\"cart-1\"}"));
            server.start();
            MonobankInstallments chast = new MonobankInstallments(
                    InstallmentsConfig.builder("store", "secret")
                            .options(MonobankClientOptions.builder().baseUrl(server.url("/").toString()).build())
                            .build());

            OrderStateResult state = chast.getOrders().state("fa4a8249-336e-4e6d-9b85-79bc8be62377");
            assertThat(state.getStateOrNull()).isNull();
            assertThat(state).isInstanceOf(OrderStateResult.NotFound.class);
            assertThat(((OrderStateResult.NotFound) state).getMessage()).isEqualTo("not found");

            QrCartResult cart = chast.getQrCarts().create(CreateQrCartRequest.of(
                    "qr", "fa4a8249-336e-4e6d-9b85-79bc8be62377",
                    Collections.singletonList(Product.of("TV", 1, BigDecimal.TEN)), "https://shop.example/callback"));
            assertThat(cart).isInstanceOf(QrCartResult.Created.class);
            assertThat(((QrCartResult.Created) cart).getCartId()).isEqualTo("cart-1");
        }
    }
}
