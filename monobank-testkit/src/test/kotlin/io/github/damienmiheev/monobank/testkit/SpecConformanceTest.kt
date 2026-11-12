package io.github.damienmiheev.monobank.testkit

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * Checks the raw JSON the fake emits against monobank's OpenAPI spec (v2412): every field name the
 * fake sends exists in the spec's response schema, required fields are present, and enum values are
 * valid. The spec's response fields are extracted into `spec-response-fields.tsv`.
 */
class SpecConformanceTest {
    private class SpecField(
        val required: Boolean,
        val type: String,
        val enum: Set<String>,
    )

    private val spec: Map<String, Map<String, SpecField>> =
        requireNotNull(javaClass.getResource("/spec-response-fields.tsv"))
            .readText()
            .lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .groupBy({ it[0] }) { it }
            .mapValues { (_, rows) ->
                rows.filter { it[1].isNotEmpty() }.associate {
                    it[1] to SpecField(it[2] == "required", it[3], it[4].split(',').filter(String::isNotEmpty).toSet())
                }
            }

    private val receiver = WebhookReceiver()
    private val fake =
        FakeMonobankAcquiring
            .builder()
            .refundSettlement(RefundSettlement.MANUAL)
            .addMerchant(MerchantSetup.builder("split-token").splitPayments(true).build())
            .build()
            .start()
    private val json = ObjectMapper()

    @AfterEach
    fun close() {
        fake.close()
        receiver.close()
    }

    private fun call(
        method: String,
        path: String,
        body: String? = null,
        token: String = fake.token,
    ): JsonNode {
        val response = rawCall(fake, method, path, body, token)
        val node = json.readTree(response.body())
        val route = "$method ${path.substringBefore('?')}"
        if (response.statusCode() == 200) conforms(route, node) else conforms("ERROR", node)
        return node
    }

    /** Field paths like `cancelList[].status`; `metadata` is free-form and not descended into. */
    private fun conforms(
        route: String,
        node: JsonNode,
    ) {
        val fields = requireNotNull(spec[route]) { "no spec for $route" }
        val emitted = mutableListOf<Pair<String, JsonNode>>()
        val objects = mutableSetOf<String>()
        flatten(node, "", emitted, objects)
        for ((name, value) in emitted) {
            val field = fields[name]
            assertThat(field).describedAs("$route emits '$name', which the spec does not have").isNotNull()
            if (field!!.enum.isNotEmpty()) {
                assertThat(value.asText()).describedAs("$route $name").isIn(field.enum)
            }
            val expected =
                when (field.type) {
                    "integer" -> value.isIntegralNumber
                    "string" -> value.isTextual
                    "array" -> value.isArray
                    "object" -> value.isObject
                    else -> true
                }
            assertThat(expected).describedAs("$route $name has the wrong JSON type: $value").isTrue()
        }
        // required fields of every object that is present (for arrays: of any element)
        for ((name, field) in fields.filterValues { it.required }) {
            val parent = name.substringBeforeLast('.', "")
            if (parent in objects) {
                assertThat(emitted.any { it.first == name }).describedAs("$route misses required '$name'").isTrue()
            }
        }
    }

    private fun flatten(
        node: JsonNode,
        prefix: String,
        out: MutableList<Pair<String, JsonNode>>,
        objects: MutableSet<String>,
    ) {
        when {
            node.isObject -> {
                objects.add(prefix)
                node.fields().forEach { (key, value) ->
                    val name = if (prefix.isEmpty()) key else "$prefix.$key"
                    out.add(name to value)
                    if (key != "metadata") flatten(value, name, out, objects)
                }
            }

            node.isArray -> {
                node.forEach { flatten(it, "$prefix[]", out, objects) }
            }
        }
    }

    @Test
    fun `every route and the webhook body match the spec's response schema`() {
        call("GET", MonobankPaths.PUBKEY)
        call("GET", MonobankPaths.DETAILS)
        val create =
            call(
                "POST",
                MonobankPaths.INVOICE_CREATE,
                """{"amount":4200,"withAppUrl":true,"webHookUrl":"${receiver.url}",""" +
                    """"saveCardData":{"saveCard":true,"walletId":"w-1"},""" +
                    """"merchantPaymInfo":{"reference":"ref-1","destination":"d","metadata":{"k":"v"}}}""",
            )
        val invoiceId = create.path("invoiceId").asText()
        call("GET", "${MonobankPaths.INVOICE_STATUS}?invoiceId=$invoiceId")
        fake.payInvoice(invoiceId)
        call("POST", MonobankPaths.INVOICE_CANCEL, """{"invoiceId":"$invoiceId","amount":1000,"extRef":"r-1"}""")
        fake.settleRefund(invoiceId, "r-1", true)
        call("POST", MonobankPaths.INVOICE_CANCEL, """{"invoiceId":"$invoiceId","amount":500}""")
        call("GET", "${MonobankPaths.INVOICE_STATUS}?invoiceId=$invoiceId")

        val failed = call("POST", MonobankPaths.INVOICE_CREATE, """{"amount":100}""").path("invoiceId").asText()
        fake.failInvoice(failed)
        call("GET", "${MonobankPaths.INVOICE_STATUS}?invoiceId=$failed")

        val hold = call("POST", MonobankPaths.INVOICE_CREATE, """{"amount":100,"paymentType":"hold"}""")
        fake.payInvoice(hold.path("invoiceId").asText())
        call(
            "POST",
            MonobankPaths.INVOICE_FINALIZE,
            """{"invoiceId":"${hold.path("invoiceId").asText()}","amount":50}""",
        )
        val removable = call("POST", MonobankPaths.INVOICE_CREATE, """{"amount":100}""").path("invoiceId").asText()
        call("POST", MonobankPaths.INVOICE_REMOVE, """{"invoiceId":"$removable"}""")

        val cardToken = fake.invoice(invoiceId)!!.cardToken!!
        call("GET", "${MonobankPaths.WALLET}?walletId=w-1")
        val walletPayment = """{"cardToken":"$cardToken","amount":100,"ccy":980,"initiationKind":"merchant"}"""
        call("POST", MonobankPaths.WALLET_PAYMENT, walletPayment)
        fake.enqueueWalletPaymentOutcome(WalletPaymentOutcome.threeDs())
        call("POST", MonobankPaths.WALLET_PAYMENT, walletPayment)
        fake.enqueueWalletPaymentOutcome(WalletPaymentOutcome.declined())
        call("POST", MonobankPaths.WALLET_PAYMENT, walletPayment)
        call("GET", "${MonobankPaths.STATEMENT}?from=${Instant.now().minusSeconds(60).epochSecond}")
        call("DELETE", "${MonobankPaths.WALLET_CARD}?cardToken=$cardToken")

        assertThat(call("GET", MonobankPaths.SPLIT_RECEIVERS, token = "split-token").path("list").size()).isEqualTo(1)
        call("GET", MonobankPaths.SPLIT_RECEIVERS)

        // errors
        call("GET", "${MonobankPaths.INVOICE_STATUS}?invoiceId=nope")
        call("POST", MonobankPaths.INVOICE_CANCEL, """{"invoiceId":"$failed"}""")

        // webhook bodies have the invoice/status shape
        val webhooks = fake.awaitWebhooks(invoiceId, 4, Duration.ofSeconds(5))
        webhooks.forEach { conforms("GET ${MonobankPaths.INVOICE_STATUS}", json.readTree(it.body)) }
    }
}
