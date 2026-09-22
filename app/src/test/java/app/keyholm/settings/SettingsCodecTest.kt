package app.keyholm.settings

import android.app.Application
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.webauthn.AlgorithmFamily
import app.keyholm.webauthn.AlgorithmPreference
import app.keyholm.webauthn.IdentityPreference
import app.keyholm.webauthn.MlDsaSupport
import app.keyholm.webauthn.NativeAppTrust
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.jupiter.api.assertThrows
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsCodecTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    @Test
    fun `create authenticators round-trip every policy`() {
        AuthenticatorPolicy.entries.forEach { policy ->
            assertThat(decodeAuthenticatorPolicy(encodeAuthenticatorPolicy(policy))).isEqualTo(policy)
        }
    }

    @Test
    fun `create authenticators default to Either when unset`() {
        assertThat(decodeAuthenticatorPolicy(null)).isEqualTo(AuthenticatorPolicy.Either)
    }

    @Test
    fun `create authenticators reject an unrecognized stored token`() {
        assertThrows<IllegalStateException> { decodeAuthenticatorPolicy("SOME_FUTURE_POLICY") }
    }

    @Test
    fun `nativeAppTrust round-trips DenyAll`() =
        runBlocking<Unit> {
            val encoded = encodeNativeAppTrust(NativeAppTrust.DenyAll)
            assertThat(decodeNativeAppTrust(encoded, context)).isEqualTo(NativeAppTrust.DenyAll)
        }

    @Test
    fun `nativeAppTrust round-trips AllowAll`() =
        runBlocking<Unit> {
            val encoded = encodeNativeAppTrust(NativeAppTrust.AllowAll)
            assertThat(decodeNativeAppTrust(encoded, context)).isEqualTo(NativeAppTrust.AllowAll)
        }

    @Test
    fun `nativeAppTrust round-trips Community`() =
        runBlocking<Unit> {
            val encoded = encodeNativeAppTrust(NativeAppTrust.Community(emptyMap()))
            assertThat(decodeNativeAppTrust(encoded, context)).isInstanceOf(NativeAppTrust.Community::class.java)
        }

    @Test
    fun `nativeAppTrust defaults to Community when unset`() =
        runBlocking<Unit> {
            assertThat(decodeNativeAppTrust(null, context)).isInstanceOf(NativeAppTrust.Community::class.java)
        }

    @Test
    fun `nativeAppTrust rejects an unrecognized stored value`() =
        runBlocking<Unit> {
            assertThrows<IllegalStateException> { decodeNativeAppTrust("SOME_FUTURE_MODE", context) }
        }

    @Test
    fun `algorithm preference round-trips every variant`() {
        listOf(
            AlgorithmPreference.FirstOffered,
            AlgorithmPreference.AlwaysAsk,
            AlgorithmPreference.Prefer(AlgorithmFamily.ES256),
        ).forEach { preference ->
            assertThat(decodeAlgorithmPreference(encodeAlgorithmPreference(preference))).isEqualTo(preference)
        }
    }

    @Test
    fun `algorithm preference defaults to FirstOffered when unset`() {
        assertThat(decodeAlgorithmPreference(null)).isEqualTo(AlgorithmPreference.FirstOffered)
    }

    @Test
    fun `algorithm preference rejects an unrecognized stored value`() {
        assertThrows<IllegalStateException> { decodeAlgorithmPreference("ML_DSA_9000") }
    }

    @Test
    fun `identity preference round-trips every variant`() {
        IdentityPreference.entries.forEach { preference ->
            assertThat(decodeIdentityPreference(encodeIdentityPreference(preference))).isEqualTo(preference)
        }
    }

    @Test
    fun `identity preference defaults to ENABLED when unset`() {
        assertThat(decodeIdentityPreference(null)).isEqualTo(IdentityPreference.ENABLED)
    }

    @Test
    fun `identity preference rejects an unrecognized stored value`() {
        assertThrows<IllegalStateException> { decodeIdentityPreference("SOME_FUTURE_MODE") }
    }

    @Test
    fun `enabled families default when unset and reject junk`() {
        assertThat(decodeEnabledFamilies(null)).isEqualTo(DEFAULT_ENABLED_FAMILIES)
        assertThat(decodeEnabledFamilies(setOf("ES256"))).containsExactly(AlgorithmFamily.ES256)
        assertThrows<IllegalStateException> { decodeEnabledFamilies(emptySet()) }
        assertThrows<IllegalStateException> { decodeEnabledFamilies(setOf("RSA9000")) }
    }

    @Test
    fun `ML-DSA support round-trips every state`() {
        MlDsaSupport.entries.forEach { support ->
            assertThat(decodeMlDsaSupport(encodeMlDsaSupport(support))).isEqualTo(support)
        }
    }

    @Test
    fun `ML-DSA support defaults to off when unset`() {
        assertThat(decodeMlDsaSupport(null)).isEqualTo(MlDsaSupport.OFF)
    }

    @Test
    fun `ML-DSA support rejects an unrecognized stored token`() {
        assertThrows<IllegalStateException> { decodeMlDsaSupport("SOME_FUTURE_MODE") }
    }
}
