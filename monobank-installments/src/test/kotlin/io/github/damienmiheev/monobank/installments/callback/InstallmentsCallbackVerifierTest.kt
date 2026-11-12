package io.github.damienmiheev.monobank.installments.callback

import io.github.damienmiheev.monobank.core.MonobankSerializationException
import io.github.damienmiheev.monobank.core.MonobankSignatureException
import io.github.damienmiheev.monobank.installments.InstallmentsTestSupport
import io.github.damienmiheev.monobank.installments.common.OrderState
import io.github.damienmiheev.monobank.installments.common.OrderSubState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64

class InstallmentsCallbackVerifierTest : InstallmentsTestSupport() {
    private val body =
        """{"order_id":"fa4a8249-336e-4e6d-9b85-79bc8be62377","state":"IN_PROCESS","order_sub_state":"WAITING_FOR_STORE_CONFIRM"}"""
            .toByteArray()

    // Computed outside the SDK:
    // printf '%s' "$body" | openssl dgst -sha256 -hmac test-store-secret -binary | base64
    private val opensslSignature = "7bxL56/mbu/3ikTS97SbqHa5GC4U0p+7f2CX4f9CsLc="

    private val verifier = InstallmentsCallbackVerifier(STORE_SECRET)

    @Test
    fun `valid signature verifies and the body parses`() {
        assertThat(verifier.verify(opensslSignature, body)).isTrue()
        assertThat(chast.callbacks.verify(opensslSignature, body)).isTrue()

        val callback = verifier.verifyAndParse(opensslSignature, body)

        assertThat(callback.orderId).isEqualTo(ORDER_ID)
        assertThat(callback.state).isEqualTo(OrderState.IN_PROCESS)
        assertThat(callback.subState).isEqualTo(OrderSubState.WAITING_FOR_STORE_CONFIRM)
        assertThat(callback.isWaitingForStoreConfirm).isTrue()
        assertThat(callback.message).isNull()
    }

    @Test
    fun `sign produces the header monobank sends`() {
        assertThat(verifier.sign(body)).isEqualTo(opensslSignature)
        assertThat(verifier.sign(body)).isEqualTo(hmacBase64(STORE_SECRET, body))
    }

    @Test
    fun `signature header tolerates surrounding whitespace and missing padding`() {
        assertThat(verifier.verify(" $opensslSignature\n", body)).isTrue()
        assertThat(verifier.verify(opensslSignature.trimEnd('='), body)).isTrue()
    }

    @Test
    fun `tampered body is rejected`() {
        val tampered = String(body).replace("WAITING_FOR_STORE_CONFIRM", "REJECTED_BY_CLIENT").toByteArray()
        assertThat(verifier.verify(opensslSignature, tampered)).isFalse()
        val reformatted = String(body).replace(",", ", ").toByteArray()
        assertThat(verifier.verify(opensslSignature, reformatted)).isFalse()
        assertThatThrownBy { verifier.verifyAndParse(opensslSignature, tampered) }
            .isInstanceOfSatisfying(MonobankSignatureException::class.java) {
                assertThat(it.reason).isEqualTo(MonobankSignatureException.Reason.INVALID_SIGNATURE)
                assertThat(it.outcome).isNull()
            }
    }

    @Test
    fun `wrong secret is rejected`() {
        assertThat(InstallmentsCallbackVerifier("another-secret").verify(opensslSignature, body)).isFalse()
    }

    @Test
    fun `missing, blank, malformed or truncated header is rejected`() {
        assertThat(verifier.verify(null, body)).isFalse()
        assertThat(verifier.verify("", body)).isFalse()
        assertThat(verifier.verify("   ", body)).isFalse()
        assertThat(verifier.verify("not base64 !!!", body)).isFalse()
        val truncated = Base64.getEncoder().encodeToString(Base64.getDecoder().decode(opensslSignature).copyOf(16))
        assertThat(verifier.verify(truncated, body)).isFalse()
        val hexInsteadOfBase64 = Base64.getDecoder().decode(opensslSignature).joinToString("") { "%02x".format(it) }
        assertThat(verifier.verify(hexInsteadOfBase64, body)).isFalse()
        assertThatThrownBy { verifier.verifyOrThrow(null, body) }.isInstanceOf(MonobankSignatureException::class.java)
    }

    @Test
    fun `parser reads QR and unknown states and fails cleanly on garbage`() {
        val qr =
            InstallmentsCallbackParser.parse(
                """{"order_id":"o","state":"FAIL","order_sub_state":"CONFIRM_TIME_EXPIRED"}""",
            )
        assertThat(qr.subState).isEqualTo(OrderSubState.CONFIRM_TIME_EXPIRED)
        val unknown =
            InstallmentsCallbackParser.parse(
                """{"order_id":"o","state":"NEW_STATE","order_sub_state":"NEW_SUB"}""",
            )
        assertThat(unknown.state).isEqualTo(OrderState.UNKNOWN)
        assertThat(unknown.subState).isEqualTo(OrderSubState.UNKNOWN)
        assertThatThrownBy { InstallmentsCallbackParser.parse("not json") }
            .isInstanceOfSatisfying(MonobankSerializationException::class.java) { assertThat(it.outcome).isNull() }
        assertThatThrownBy { InstallmentsCallbackParser.parse("""{"state":"FAIL"}""") }
            .isInstanceOf(MonobankSerializationException::class.java)
    }

    @Test
    fun `verifier never prints the secret`() {
        assertThat(verifier.toString()).doesNotContain(STORE_SECRET)
        assertThatThrownBy { InstallmentsCallbackVerifier("") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
