package io.github.damienmiheev.monobank.acquiring

import io.github.damienmiheev.monobank.acquiring.common.InitiationKind
import io.github.damienmiheev.monobank.acquiring.invoice.CancelInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.CardData
import io.github.damienmiheev.monobank.acquiring.invoice.CreateInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.FinalizeInvoiceRequest
import io.github.damienmiheev.monobank.acquiring.invoice.PaymentDirectRequest
import io.github.damienmiheev.monobank.acquiring.invoice.SyncCardData
import io.github.damienmiheev.monobank.acquiring.invoice.SyncCardType
import io.github.damienmiheev.monobank.acquiring.invoice.SyncPaymentRequest
import io.github.damienmiheev.monobank.acquiring.monopay.ImportMonopayKeyRequest
import io.github.damienmiheev.monobank.acquiring.pos.PosRefundRequest
import io.github.damienmiheev.monobank.acquiring.subscription.CreateSubscriptionRequest
import io.github.damienmiheev.monobank.acquiring.subscription.EditSubscriptionRequest
import io.github.damienmiheev.monobank.acquiring.subscription.SubscriptionListQuery
import io.github.damienmiheev.monobank.acquiring.subscription.SubscriptionPaymentsQuery
import io.github.damienmiheev.monobank.acquiring.wallet.WalletPaymentRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Every operation in `acquiring-operations.txt` (all of spec v2412 plus the legacy employee list)
 * must map to an SDK call that sends exactly that method and path.
 */
class SpecCoverageTest : AcquiringTestSupport() {
    private val since = OffsetDateTime.parse("2024-06-01T00:00:00+03:00")

    @Suppress("DEPRECATION")
    private val calls: Map<String, (MonobankAcquiring) -> Any?> =
        mapOf(
            "POST /api/merchant/invoice/create" to { m ->
                m.invoices.create(CreateInvoiceRequest.builder(100).build())
            },
            "GET /api/merchant/invoice/status" to { m -> m.invoices.status("i") },
            "POST /api/merchant/invoice/cancel" to { m ->
                m.invoices.cancel(CancelInvoiceRequest.builder("i").build())
            },
            "POST /api/merchant/invoice/remove" to { m -> m.invoices.remove("i") },
            "GET /api/merchant/pubkey" to { m -> m.merchant.publicKey() },
            "POST /api/merchant/invoice/finalize" to { m -> m.invoices.finalize(FinalizeInvoiceRequest.of("i", 1)) },
            "GET /api/merchant/qr/details" to { m -> m.qr.details("q") },
            "POST /api/merchant/qr/reset-amount" to { m -> m.qr.resetAmount("q") },
            "GET /api/merchant/qr/list" to { m -> m.qr.list() },
            "GET /api/merchant/details" to { m -> m.merchant.details() },
            "GET /api/merchant/statement" to { m -> m.merchant.statement(Instant.ofEpochSecond(1)) },
            "DELETE /api/merchant/wallet/card" to { m -> m.wallet.deleteCard("t") },
            "GET /api/merchant/wallet" to { m -> m.wallet.cards("w") },
            "POST /api/merchant/wallet/payment" to { m ->
                m.wallet.payment(WalletPaymentRequest.builder("t", 1, 980, InitiationKind.MERCHANT).build())
            },
            "POST /api/merchant/invoice/payment-direct" to { m ->
                m.invoices.paymentDirect(
                    PaymentDirectRequest.builder(1, CardData.of("4242", "0642", "1"), InitiationKind.CLIENT).build(),
                )
            },
            "GET /api/merchant/submerchant/list" to { m -> m.merchant.submerchants() },
            "GET /api/merchant/invoice/receipt" to { m -> m.invoices.receipt("i") },
            "GET /api/merchant/invoice/fiscal-checks" to { m -> m.invoices.fiscalChecks("i") },
            "POST /api/merchant/invoice/sync-payment" to { m ->
                m.invoices.syncPayment(
                    SyncPaymentRequest
                        .card(1, 980, SyncCardData.builder("4242", SyncCardType.FPAN, "0642", "02").build())
                        .build(),
                )
            },
            "GET /api/merchant/split-receiver/list" to { m -> m.split.receivers() },
            "GET /api/merchant/split-receiver/get-by-id" to { m -> m.split.receiver("s") },
            "GET /api/merchant/t2p/terminal/list" to { m -> m.terminals.list() },
            "GET /api/merchant/t2p/terminal/payment/external/status" to { m -> m.terminals.paymentStatus("e") },
            "POST /api/merchant/pos-transaction-cancel" to { m -> m.posRefunds.create(PosRefundRequest.of("r", 1)) },
            "GET /api/merchant/pos-transaction-cancel/status" to { m -> m.posRefunds.status("c") },
            "POST /api/merchant/subscription/create" to { m ->
                m.subscriptions.create(CreateSubscriptionRequest.builder(1, "1m").build())
            },
            "GET /api/merchant/subscription/status" to { m -> m.subscriptions.status("s") },
            "GET /api/merchant/subscription/payments" to { m ->
                m.subscriptions.payments(SubscriptionPaymentsQuery.builder("s", since).build())
            },
            "GET /api/merchant/subscription/list" to { m ->
                m.subscriptions.list(SubscriptionListQuery.builder(since).build())
            },
            "POST /api/merchant/subscription/remove" to { m -> m.subscriptions.remove("s") },
            "POST /api/merchant/subscription/edit" to { m ->
                m.subscriptions.edit(EditSubscriptionRequest.cancel("s"))
            },
            "POST /api/merchant/monopay/pubkey-import" to { m -> m.monopay.importKey(ImportMonopayKeyRequest.of("k")) },
            "POST /api/merchant/monopay/pubkey-delete" to { m -> m.monopay.deleteKey("k") },
            "GET /api/merchant/monopay/pubkey-list" to { m -> m.monopay.keys() },
            "GET /api/merchant/employee/list" to { m -> m.employees.list() },
        )

    private val operations: List<String> =
        resource("/acquiring-operations.txt")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    @Test
    fun `the operations list is complete and has no duplicates`() {
        assertThat(operations).hasSize(35).doesNotHaveDuplicates()
    }

    @Test
    fun `every documented operation has an SDK method`() {
        assertThat(operations.filterNot { it in calls }).describedAs("operations without an SDK method").isEmpty()
        assertThat(calls.keys.filterNot { it in operations }).describedAs("mapped operations not in the list").isEmpty()
    }

    @Test
    fun `every SDK method sends the documented method and path`() {
        for (operation in operations) {
            val (method, path) = operation.split(" ", limit = 2)
            val fixtureName = method + "_" + path.trimStart('/').replace('/', '_').replace('-', '_') + ".response.json"
            enqueueJson(fixture(fixtureName))

            calls.getValue(operation)(mono)

            val request = takeRequest()
            assertThat(request.method).describedAs(operation).isEqualTo(method)
            assertThat(request.requestUrl!!.encodedPath).describedAs(operation).isEqualTo(path)
        }
        assertThat(server.requestCount).isEqualTo(operations.size)
    }
}
