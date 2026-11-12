package io.github.damienmiheev.monobank.acquiring.subscription

import io.github.damienmiheev.monobank.acquiring.AcquiringTestSupport
import io.github.damienmiheev.monobank.acquiring.common.CardTokenStatus
import io.github.damienmiheev.monobank.core.MonobankBadRequestException
import io.github.damienmiheev.monobank.core.MonobankServerException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class SubscriptionApiTest : AcquiringTestSupport() {
    private val from = OffsetDateTime.parse("2024-06-26T18:12:44+03:00")
    private val to = OffsetDateTime.parse("2024-07-26T18:12:44+03:00")

    @Test
    fun `create sends the request and reads subscriptionId`() {
        enqueueFixture("POST_api_merchant_subscription_create.response.json")

        val response =
            mono.subscriptions.create(
                CreateSubscriptionRequest
                    .builder(4200, "1m")
                    .ccy(980)
                    .redirectUrl("https://example.com/your/website/result/page")
                    .webHookUrls(
                        SubscriptionWebHookUrls.of(
                            "https://example.com/mono/subscription/charge/webhook",
                            "https://example.com/mono/subscription/status/webhook",
                        ),
                    ).validity(3600)
                    .build(),
            )

        assertThat(response.subscriptionId).isEqualTo("s2_AbrCdXyZ13")
        assertThat(response.pageUrl).isEqualTo("https://pay.mbnk.biz/s2_AbrCdXyZ13")
        val body = takeRequest("POST", "/api/merchant/subscription/create").json()
        val expected = MAPPER.readTree(fixture("POST_api_merchant_subscription_create.request.json"))
        assertThat(body).isEqualTo(expected)
    }

    @Test
    fun `create validates and is not retried`() {
        assertThatThrownBy { CreateSubscriptionRequest.builder(0, "1m").build() }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            CreateSubscriptionRequest.builder(
                1,
                " ",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        enqueueJson("{}", status = 500)
        assertThatThrownBy { mono.subscriptions.create(CreateSubscriptionRequest.builder(1, "1d").build()) }
            .isInstanceOf(MonobankServerException::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `status reads every field and hides the card token`() {
        enqueueFixture("GET_api_merchant_subscription_status.response.json")

        val status = mono.subscriptions.status("s2_AbrCdXyZ13")

        assertThat(takeRequest("GET", "/api/merchant/subscription/status").query("subscriptionId"))
            .isEqualTo("s2_AbrCdXyZ13")
        assertThat(status.subscriptionId).isEqualTo("s2_AbrCdXyZ13")
        assertThat(status.status).isEqualTo(SubscriptionStatus.ACTIVE)
        assertThat(status.startDate).isEqualTo(OffsetDateTime.parse("2024-06-01T14:15:22Z"))
        assertThat(status.endDate).isEqualTo(OffsetDateTime.parse("2024-06-20T09:30:15Z"))
        assertThat(status.amount).isEqualTo(4200)
        assertThat(status.ccy).isEqualTo(980)
        assertThat(status.interval).isEqualTo("1m")
        assertThat(status.nextChargeDate).isEqualTo(OffsetDateTime.parse("2024-07-01T14:15:22Z"))
        assertThat(status.cancellationDesc).isEqualTo("Скасовано за запитом користувача")
        assertThat(status.summary!!.totalPaid).isEqualTo(3)
        assertThat(status.summary!!.totalFailed).isEqualTo(1)
        val wallet = status.walletData!!
        assertThat(wallet.cardToken).isEqualTo("67XZtXdR4NpKU3")
        assertThat(wallet.walletId).isEqualTo("c1376a611e17b059aeaf96b73258da9c")
        assertThat(wallet.status).isEqualTo(CardTokenStatus.CREATED)
        assertThat(wallet.failureDescription).isEqualTo("Недостатньо коштів на картці")
        assertThat(status.toString()).doesNotContain("67XZtXdR4NpKU3")
    }

    @Test
    fun `status survives unknown values and missing optional objects`() {
        enqueueJson("""{"subscriptionId":"s","status":"paused","amount":1,"ccy":980}""")
        val status = mono.subscriptions.status("s")
        assertThat(status.status).isEqualTo(SubscriptionStatus.UNKNOWN)
        assertThat(status.statusRaw).isEqualTo("paused")
        assertThat(status.summary).isNull()
        assertThat(status.walletData).isNull()
    }

    @Test
    fun `payments sends RFC 3339 dates encoded and returns a page`() {
        enqueueFixture("GET_api_merchant_subscription_payments.response.json")

        val page =
            mono.subscriptions.payments(
                SubscriptionPaymentsQuery
                    .builder("s2_AbrCdXyZ13", from)
                    .dateTo(to)
                    .page(2)
                    .limit(50)
                    .build(),
            )

        val request = takeRequest("GET", "/api/merchant/subscription/payments")
        assertThat(request.path).contains("dateFrom=2024-06-26T18%3A12%3A44%2B03%3A00")
        assertThat(request.query("dateFrom")).isEqualTo("2024-06-26T18:12:44+03:00")
        assertThat(request.query("dateTo")).isEqualTo("2024-07-26T18:12:44+03:00")
        assertThat(request.query("subscriptionId")).isEqualTo("s2_AbrCdXyZ13")
        assertThat(request.query("page")).isEqualTo("2")
        assertThat(request.query("limit")).isEqualTo("50")
        val payment = page.items.single()
        assertThat(payment.amount).isEqualTo(4200)
        assertThat(payment.status).isEqualTo(SubscriptionPaymentStatus.SUCCESS)
        assertThat(payment.chargedAt).isEqualTo(OffsetDateTime.parse("2024-06-01T14:15:22Z"))
        assertThat(payment.ccy).isEqualTo(980)
        assertThat(page.pagination!!.totalItems).isEqualTo(10)
        assertThat(page.pagination!!.itemsPerPage).isEqualTo(20)
        assertThat(page.pagination!!.currentPage).isEqualTo(1)
        assertThat(page.pagination!!.totalPages).isEqualTo(1)
        assertThat(page.hasNextPage).isFalse()
    }

    @Test
    fun `payments with only the required parameters`() {
        enqueueJson(
            """{"payments":[],"pagination":{"totalItems":40,"itemsPerPage":20,"currentPage":1,"totalPages":2}}""",
        )
        val page = mono.subscriptions.payments(SubscriptionPaymentsQuery.builder("s", from).build())
        val request = takeRequest()
        assertThat(request.requestUrl!!.queryParameterNames).containsExactlyInAnyOrder("subscriptionId", "dateFrom")
        assertThat(page.items).isEmpty()
        assertThat(page.hasNextPage).isTrue()
    }

    @Test
    fun `list filters and pages`() {
        enqueueFixture("GET_api_merchant_subscription_list.response.json")

        val page =
            mono.subscriptions.list(
                SubscriptionListQuery
                    .builder(from)
                    .dateTo(to)
                    .status(SubscriptionStatus.CANCELLED)
                    .page(1)
                    .limit(20)
                    .build(),
            )

        val request = takeRequest("GET", "/api/merchant/subscription/list")
        assertThat(request.query("status")).isEqualTo("cancelled")
        assertThat(request.query("dateFrom")).isEqualTo("2024-06-26T18:12:44+03:00")
        assertThat(request.query("dateTo")).isEqualTo("2024-07-26T18:12:44+03:00")
        assertThat(request.query("page")).isEqualTo("1")
        assertThat(request.query("limit")).isEqualTo("20")
        val item = page.items.single()
        assertThat(item.subscriptionId).isEqualTo("s2_AbrCdXyZ13")
        assertThat(item.amount).isEqualTo(4200)
        assertThat(item.interval).isEqualTo("1m")
        assertThat(item.startDate).isEqualTo(OffsetDateTime.parse("2024-06-01T14:15:22Z"))
        assertThat(item.created).isEqualTo(OffsetDateTime.parse("2024-05-30T12:10:05Z"))
        assertThat(item.nextChargeDate).isEqualTo(OffsetDateTime.parse("2024-07-01T14:15:22Z"))
        assertThat(item.endDate).isEqualTo(OffsetDateTime.parse("2024-09-01T14:15:22Z"))
        assertThat(item.status).isEqualTo(SubscriptionStatus.ACTIVE)
        assertThat(page.pagination!!.totalItems).isEqualTo(5)
    }

    @Test
    fun `list without a list or pagination is an empty page and is retried on 5xx`() {
        enqueueJson("{}", status = 503)
        enqueueJson("{}")
        val page = mono.subscriptions.list(SubscriptionListQuery.builder(from).build())
        assertThat(page.items).isEmpty()
        assertThat(page.pagination).isNull()
        assertThat(page.hasNextPage).isFalse()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `query builders validate`() {
        assertThatThrownBy { SubscriptionListQuery.builder(from).status(SubscriptionStatus.UNKNOWN) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            SubscriptionListQuery
                .builder(
                    from,
                ).page(0)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SubscriptionPaymentsQuery.builder("s", from).limit(-1) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            SubscriptionPaymentsQuery.builder(
                "",
                from,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `remove sends subscriptionId and reports 1001 when already paid`() {
        enqueueFixture("POST_api_merchant_subscription_remove.response.json")
        mono.subscriptions.remove("s2_AbrCdXyZ13")
        assertThat(takeRequest("POST", "/api/merchant/subscription/remove").json().path("subscriptionId").asText())
            .isEqualTo("s2_AbrCdXyZ13")

        enqueueJson("""{"errCode":"1001","errText":"subscription already has payments"}""", status = 400)
        assertThatThrownBy { mono.subscriptions.remove("s") }
            .isInstanceOfSatisfying(MonobankBadRequestException::class.java) {
                assertThat(it.errorCode).isEqualTo("1001")
            }
    }

    @Test
    fun `edit cancels with and without a refund and is not retried`() {
        enqueueFixture("POST_api_merchant_subscription_edit.response.json")
        mono.subscriptions.edit(EditSubscriptionRequest.cancel("s2_AbrCdXyZ13", 4200))
        val body = takeRequest("POST", "/api/merchant/subscription/edit").json()
        assertThat(body).isEqualTo(MAPPER.readTree(fixture("POST_api_merchant_subscription_edit.request.json")))

        enqueueJson("{}")
        mono.subscriptions.edit(EditSubscriptionRequest.cancel("s"))
        val noRefund = takeRequest().json()
        assertThat(noRefund.has("refundAmount")).isFalse()
        assertThat(noRefund.path("action").asText()).isEqualTo("cancel")

        enqueueJson("{}", status = 500)
        assertThatThrownBy { mono.subscriptions.edit(EditSubscriptionRequest.cancel("s", 1)) }
            .isInstanceOf(MonobankServerException::class.java)
        assertThat(server.requestCount).isEqualTo(3)
        assertThatThrownBy { EditSubscriptionRequest.cancel("s", 0) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private companion object {
        val MAPPER = io.github.damienmiheev.monobank.core.json.MonobankJson.mapper
    }

    @Test
    fun `dates are sent with whole seconds and must be in order`() {
        enqueueJson("{}")
        mono.subscriptions.list(
            SubscriptionListQuery.builder(OffsetDateTime.parse("2024-06-26T18:12:44.123456789+03:00")).build(),
        )
        assertThat(takeRequest().query("dateFrom")).isEqualTo("2024-06-26T18:12:44+03:00")
        assertThatThrownBy { SubscriptionListQuery.builder(to).dateTo(from).build() }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SubscriptionPaymentsQuery.builder("s", to).dateTo(from).build() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
