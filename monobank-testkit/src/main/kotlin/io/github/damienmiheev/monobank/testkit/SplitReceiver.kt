package io.github.damienmiheev.monobank.testkit

/**
 * A split payment receiver (sub-merchant) listed by `GET /api/merchant/split-receiver/list`,
 * configured with [MerchantSetup.Builder.splitReceivers] or [FakeMonobankAcquiring.Builder.splitReceivers].
 *
 * @property id Receiver id, used as `basketOrder[].splitReceiverId`.
 * @property name Receiver's name.
 * @property edrpou Receiver's EDRPOU.
 */
public class SplitReceiver private constructor(
    public val id: String,
    public val name: String,
    public val edrpou: String,
) {
    override fun toString(): String = "SplitReceiver(id=$id, name=$name, edrpou=$edrpou)"

    public companion object {
        /** The receiver a merchant with split payments has when none is configured (monobank's docs example). */
        @JvmField
        public val DEFAULT: SplitReceiver = SplitReceiver("sr_aB3dC5eF7g", "Fake Split Receiver", "1234567890")

        /** A receiver. */
        @JvmStatic
        public fun of(
            id: String,
            name: String,
            edrpou: String,
        ): SplitReceiver {
            require(id.isNotBlank()) { "id must not be blank" }
            return SplitReceiver(id, name, edrpou)
        }
    }
}
