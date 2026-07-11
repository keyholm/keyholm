package app.keyholm.ui.common

import androidx.biometric.AuthenticationRequest
import androidx.biometric.AuthenticationResult
import androidx.biometric.AuthenticationResultLauncher
import androidx.biometric.BiometricPrompt
import androidx.biometric.compose.rememberAuthenticationLauncher
import androidx.biometric.registerForAuthenticationResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import app.keyholm.keystore.AuthenticatorPolicy
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal val CANCELLATION_ERRORS =
    setOf(
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_CANCELED,
    )

sealed interface PromptResult {
    data class Success(
        val crypto: BiometricPrompt.CryptoObject,
    ) : PromptResult

    data object Canceled : PromptResult

    data object Failed : PromptResult

    data object NoCryptoObject : PromptResult
}

internal class ResultSlot {
    private var continuation: CancellableContinuation<AuthenticationResult>? = null

    fun deliver(result: AuthenticationResult) {
        continuation?.resume(result)
        continuation = null
    }

    suspend fun awaitResult(launch: () -> Unit): AuthenticationResult =
        suspendCancellableCoroutine { cont ->
            continuation = cont
            cont.invokeOnCancellation { continuation = null }
            launch()
        }
}

class CryptoPrompt internal constructor(
    private val launcher: AuthenticationResultLauncher,
    private val slot: ResultSlot,
) {
    suspend fun authenticate(
        title: String,
        cryptoObject: BiometricPrompt.CryptoObject,
        allowedAuthenticators: AuthenticatorPolicy,
        content: AuthenticationRequest.BodyContent,
    ): PromptResult {
        val result = prompt(title, cryptoObject, allowedAuthenticators, content)
        return when {
            result is AuthenticationResult.Success -> {
                result.crypto?.let(PromptResult::Success) ?: PromptResult.NoCryptoObject
            }

            result is AuthenticationResult.Error && result.errorCode in CANCELLATION_ERRORS -> {
                PromptResult.Canceled
            }

            else -> {
                PromptResult.Failed
            }
        }
    }

    suspend fun confirm(
        title: String,
        cryptoObject: BiometricPrompt.CryptoObject? = null,
        allowedAuthenticators: AuthenticatorPolicy,
        content: AuthenticationRequest.BodyContent,
    ): Boolean = prompt(title, cryptoObject, allowedAuthenticators, content) is AuthenticationResult.Success

    private suspend fun prompt(
        title: String,
        cryptoObject: BiometricPrompt.CryptoObject?,
        allowedAuthenticators: AuthenticatorPolicy,
        content: AuthenticationRequest.BodyContent,
    ): AuthenticationResult {
        val fallback =
            if (allowedAuthenticators != AuthenticatorPolicy.Biometric) {
                AuthenticationRequest.Biometric.Fallback.DeviceCredential
            } else {
                AuthenticationRequest.Biometric.Fallback.CustomOption("Cancel")
            }
        val request =
            AuthenticationRequest.Biometric
                .Builder(title, fallback)
                .setContent(content)
                .setMinStrength(AuthenticationRequest.Biometric.Strength.Class3(cryptoObject))
                .build()
        return slot.awaitResult { launcher.launch(request) }
    }

    companion object {
        operator fun invoke(activity: FragmentActivity): CryptoPrompt {
            val slot = ResultSlot()
            val launcher = activity.registerForAuthenticationResult { slot.deliver(it) }
            return CryptoPrompt(launcher, slot)
        }
    }
}

// A lot of pain was had before adhering to the rule that there should only be
// one of these per Activity
@Composable
fun rememberCryptoPrompt(): CryptoPrompt {
    val slot = remember { ResultSlot() }
    val launcher = rememberAuthenticationLauncher { slot.deliver(it) }
    return remember(launcher) { CryptoPrompt(launcher, slot) }
}
