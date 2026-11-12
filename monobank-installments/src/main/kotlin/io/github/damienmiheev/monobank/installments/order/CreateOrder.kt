package io.github.damienmiheev.monobank.installments.order

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.damienmiheev.monobank.installments.common.InvoiceSource
import io.github.damienmiheev.monobank.installments.common.Product
import io.github.damienmiheev.monobank.installments.internal.maskPhone
import io.github.damienmiheev.monobank.installments.internal.requireHryvnias
import io.github.damienmiheev.monobank.installments.internal.requireNotBlank
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Request of `POST /api/order/create`.
 *
 * ```kotlin
 * CreateOrderRequest.builder(
 *     storeOrderId = "ORD-1",
 *     clientPhone = "+380501234567",
 *     totalSum = BigDecimal("2499.99"),
 *     invoice = CreateOrderInvoice.of("INV-1", LocalDate.now(), InvoiceSource.INTERNET),
 *     availablePrograms = listOf(AvailableProgram.of(listOf(3, 6, 10))),
 *     products = listOf(Product.of("TV", 1, BigDecimal("2499.99"))),
 * ).resultCallback("https://shop.example/chast/callback").build()
 * ```
 *
 * [toString] masks [clientPhone].
 *
 * @property storeOrderId Your order id, 1–64 characters. monobank deduplicates by it: repeating the request with
 *   the same id returns the existing order instead of creating a new one.
 * @property clientPhone Client phone in international format, e.g. `+380501234567`.
 * @property totalSum Order total in hryvnias, decimal (e.g. `2499.99`), not kopiyky. The spec's schema says at least 2,
 *   the docs' prose says at least 1 UAH; only "positive, whole kopiyky" is checked locally and monobank decides.
 * @property invoice Invoice (receipt) of the purchase.
 * @property availablePrograms Offered installment programs (the spec allows exactly one).
 * @property products Products, at least one.
 * @property resultCallback URL monobank POSTs state changes to (verify them with `InstallmentsCallbackVerifier`).
 * @property additionalParams Seller phone, VAT and down payment.
 * @property financialCompanyMerchantInfo Seller details, for financial companies acting for a store.
 */
@ConsistentCopyVisibility
public data class CreateOrderRequest internal constructor(
    @get:JsonProperty("store_order_id") public val storeOrderId: String,
    @get:JsonProperty("client_phone") public val clientPhone: String,
    @get:JsonProperty("total_sum") public val totalSum: BigDecimal,
    @get:JsonProperty("invoice") public val invoice: CreateOrderInvoice,
    @get:JsonProperty("available_programs") public val availablePrograms: List<AvailableProgram>,
    @get:JsonProperty("products") public val products: List<Product>,
    @get:JsonProperty("result_callback") public val resultCallback: String?,
    @get:JsonProperty("additional_params") public val additionalParams: CreateOrderAdditionalParams?,
    @get:JsonProperty("financial_company_merchant_info")
    public val financialCompanyMerchantInfo: FinancialCompanyMerchantInfo?,
) {
    override fun toString(): String =
        "CreateOrderRequest(storeOrderId=$storeOrderId, clientPhone=${maskPhone(clientPhone)}, totalSum=$totalSum, " +
            "invoice=$invoice, availablePrograms=$availablePrograms, products=$products, " +
            "resultCallback=$resultCallback, additionalParams=$additionalParams, " +
            "financialCompanyMerchantInfo=$financialCompanyMerchantInfo)"

    /** Builder for [CreateOrderRequest]. */
    public class Builder internal constructor(
        private val storeOrderId: String,
        private val clientPhone: String,
        private val totalSum: BigDecimal,
        private val invoice: CreateOrderInvoice,
        private val availablePrograms: List<AvailableProgram>,
        private val products: List<Product>,
    ) {
        private var resultCallback: String? = null
        private var additionalParams: CreateOrderAdditionalParams? = null
        private var financialCompanyMerchantInfo: FinancialCompanyMerchantInfo? = null

        /** URL for state callbacks (sent on `WAITING_FOR_STORE_CONFIRM` and on every `FAIL`). */
        public fun resultCallback(url: String?): Builder = apply { resultCallback = url }

        /** Seller phone, VAT and down payment. */
        public fun additionalParams(params: CreateOrderAdditionalParams?): Builder = apply { additionalParams = params }

        /** Seller details for financial companies. */
        public fun financialCompanyMerchantInfo(info: FinancialCompanyMerchantInfo?): Builder =
            apply { financialCompanyMerchantInfo = info }

        /** Builds the request. */
        public fun build(): CreateOrderRequest {
            requireNotBlank(storeOrderId, "storeOrderId")
            require(storeOrderId.length <= MAX_STORE_ORDER_ID) {
                "storeOrderId must be at most $MAX_STORE_ORDER_ID characters"
            }
            requireNotBlank(clientPhone, "clientPhone")
            requireHryvnias(totalSum, "totalSum")
            require(availablePrograms.size == 1) { "availablePrograms must contain exactly one program" }
            require(products.isNotEmpty()) { "products must not be empty" }
            return CreateOrderRequest(
                storeOrderId,
                clientPhone,
                totalSum,
                invoice,
                availablePrograms.toList(),
                products.toList(),
                resultCallback,
                additionalParams,
                financialCompanyMerchantInfo,
            )
        }
    }

    public companion object {
        private const val MAX_STORE_ORDER_ID = 64

        /**
         * Starts a request with the required fields.
         *
         * @param storeOrderId Your order id (1–64 characters); also the idempotency key.
         * @param clientPhone Client phone, `+380…`.
         * @param totalSum Order total in hryvnias (decimal), at most 2 decimal places.
         * @param invoice Invoice of the purchase.
         * @param availablePrograms Offered programs, e.g. `listOf(AvailableProgram.of(listOf(3, 6, 10)))`.
         * @param products Products, at least one.
         */
        @JvmStatic
        public fun builder(
            storeOrderId: String,
            clientPhone: String,
            totalSum: BigDecimal,
            invoice: CreateOrderInvoice,
            availablePrograms: List<AvailableProgram>,
            products: List<Product>,
        ): Builder = Builder(storeOrderId, clientPhone, totalSum, invoice, availablePrograms, products)
    }
}

/**
 * Invoice (receipt) of a new order.
 *
 * @property number Invoice or receipt number.
 * @property date Invoice date (`YYYY-MM-DD`).
 * @property source Sales channel.
 * @property pointId Point of sale id, at most 50 characters.
 */
@ConsistentCopyVisibility
public data class CreateOrderInvoice internal constructor(
    @get:JsonProperty("number") public val number: String,
    @get:JsonProperty("date") public val date: LocalDate,
    @get:JsonProperty("source") public val source: InvoiceSource,
    @get:JsonProperty("point_id") public val pointId: String?,
) {
    public companion object {
        private const val MAX_POINT_ID = 50

        /** Creates an invoice; [pointId] is optional (at most 50 characters). */
        @JvmStatic
        @JvmOverloads
        public fun of(
            number: String,
            date: LocalDate,
            source: InvoiceSource,
            pointId: String? = null,
        ): CreateOrderInvoice {
            requireNotBlank(number, "number")
            require(pointId == null || pointId.length in 1..MAX_POINT_ID) {
                "pointId must be 1-$MAX_POINT_ID characters"
            }
            return CreateOrderInvoice(number, date, source, pointId)
        }
    }
}

/**
 * An installment program offered to the client.
 *
 * @property availablePartsCount Allowed numbers of payments, each 3–25 (e.g. `[3, 6, 10]`).
 * @property type Program type. monobank ignores it (deprecated in the spec); left out unless set.
 */
@ConsistentCopyVisibility
public data class AvailableProgram internal constructor(
    @get:JsonProperty("available_parts_count") public val availablePartsCount: List<Int>,
    @get:JsonProperty("type") public val type: String?,
) {
    public companion object {
        private const val MIN_PARTS = 3
        private const val MAX_PARTS = 25

        /**
         * Creates a program.
         *
         * @param availablePartsCount Allowed numbers of payments (3–25), not empty.
         * @param type Ignored by monobank; e.g. `payment_installments`.
         */
        @JvmStatic
        @JvmOverloads
        public fun of(
            availablePartsCount: List<Int>,
            type: String? = null,
        ): AvailableProgram {
            require(availablePartsCount.isNotEmpty()) { "availablePartsCount must not be empty" }
            require(availablePartsCount.all { it in MIN_PARTS..MAX_PARTS }) {
                "availablePartsCount values must be $MIN_PARTS-$MAX_PARTS"
            }
            return AvailableProgram(availablePartsCount.toList(), type)
        }
    }
}

/**
 * Optional parameters of a new order. [toString] masks [sellerPhone].
 *
 * @property sellerPhone Seller's phone (for monobank's Telegram bot).
 * @property nds VAT amount in hryvnias, decimal (only in rare cases).
 * @property extInitialSum Down payment the client already paid at the store's till, in hryvnias, decimal.
 *   It is not part of the credit amount.
 */
@ConsistentCopyVisibility
public data class CreateOrderAdditionalParams internal constructor(
    @get:JsonProperty("seller_phone") public val sellerPhone: String?,
    @get:JsonProperty("nds") public val nds: BigDecimal?,
    @get:JsonProperty("ext_initial_sum") public val extInitialSum: BigDecimal?,
) {
    override fun toString(): String =
        "CreateOrderAdditionalParams(sellerPhone=${maskPhone(sellerPhone)}, nds=$nds, extInitialSum=$extInitialSum)"

    /** Builder for [CreateOrderAdditionalParams]. */
    public class Builder internal constructor() {
        private var sellerPhone: String? = null
        private var nds: BigDecimal? = null
        private var extInitialSum: BigDecimal? = null

        /** Seller's phone. */
        public fun sellerPhone(phone: String?): Builder = apply { sellerPhone = phone }

        /** VAT in hryvnias (decimal). */
        public fun nds(nds: BigDecimal?): Builder = apply { this.nds = nds?.let { requireHryvnias(it, "nds", true) } }

        /** Down payment already paid at the till, in hryvnias (decimal). */
        public fun extInitialSum(sum: BigDecimal?): Builder =
            apply { extInitialSum = sum?.let { requireHryvnias(it, "extInitialSum", true) } }

        /** Builds the parameters. */
        public fun build(): CreateOrderAdditionalParams = CreateOrderAdditionalParams(sellerPhone, nds, extInitialSum)
    }

    public companion object {
        /** Starts building parameters. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}

/**
 * Seller details, used by financial companies that create orders for a store.
 *
 * @property storeName Store or point of sale name.
 * @property edrpouCode Seller's EDRPOU code (digits only).
 * @property ibanAccount Seller's IBAN.
 */
@ConsistentCopyVisibility
public data class FinancialCompanyMerchantInfo internal constructor(
    @get:JsonProperty("store_name") public val storeName: String?,
    @get:JsonProperty("edrpou_code") public val edrpouCode: String?,
    @get:JsonProperty("iban_account") public val ibanAccount: String?,
) {
    public companion object {
        /** Creates seller details; every field is optional. */
        @JvmStatic
        public fun of(
            storeName: String?,
            edrpouCode: String?,
            ibanAccount: String?,
        ): FinancialCompanyMerchantInfo {
            require(edrpouCode == null || (edrpouCode.isNotEmpty() && edrpouCode.all { it in '0'..'9' })) {
                "edrpouCode must contain digits only"
            }
            return FinancialCompanyMerchantInfo(storeName, edrpouCode, ibanAccount)
        }
    }
}

/**
 * Answer of `POST /api/order/create` (HTTP 201).
 *
 * @property orderId monobank order id (UUID). Use it for state, confirm, reject and return. When the same
 *   `store_order_id` was sent before, this is the id of the existing order.
 */
@ConsistentCopyVisibility
public data class CreateOrderResponse
    @JsonCreator
    internal constructor(
        @param:JsonProperty("order_id") public val orderId: String,
    )
