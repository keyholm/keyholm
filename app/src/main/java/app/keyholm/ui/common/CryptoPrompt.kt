package app.keyholm.ui.common

import android.view.View
import android.view.ViewTreeObserver
import androidx.biometric.AuthenticationRequest
import androidx.biometric.AuthenticationResult
import androidx.biometric.AuthenticationResultLauncher
import androidx.biometric.BiometricPrompt
import androidx.biometric.compose.rememberAuthenticationLauncher
import androidx.biometric.registerForAuthenticationResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.fragment.app.FragmentActivity
import app.keyholm.keystore.AuthenticatorPolicy
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal val CANCELLATION_ERRORS =
    setOf(
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    )

sealed interface PromptResult {
    data class Success(
        val crypto: BiometricPrompt.CryptoObject,
    ) : PromptResult

    data object Canceled : PromptResult

    data object Interrupted : PromptResult

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

interface CryptoAuthenticator {
    suspend fun authenticate(
        title: String,
        cryptoObject: BiometricPrompt.CryptoObject,
        allowedAuthenticators: AuthenticatorPolicy,
        content: AuthenticationRequest.BodyContent,
    ): PromptResult
}

class CryptoPrompt internal constructor(
    private val launcher: AuthenticationResultLauncher,
    private val slot: ResultSlot,
    private val view: () -> View,
) : CryptoAuthenticator {
    override suspend fun authenticate(
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

            result is AuthenticationResult.CustomFallbackSelected -> {
                PromptResult.Canceled
            }

            result is AuthenticationResult.Error && result.errorCode in CANCELLATION_ERRORS -> {
                PromptResult.Canceled
            }

            result is AuthenticationResult.Error && result.errorCode == BiometricPrompt.ERROR_CANCELED -> {
                PromptResult.Interrupted
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
        val request =
            when (allowedAuthenticators) {
                AuthenticatorPolicy.DeviceCredential -> {
                    AuthenticationRequest.Credential
                        .Builder(title)
                        .setContent(content)
                        .setCryptoObject(cryptoObject)
                        .build()
                }

                AuthenticatorPolicy.Either -> {
                    biometricRequest(title, AuthenticationRequest.Biometric.Fallback.DeviceCredential, cryptoObject, content)
                }

                AuthenticatorPolicy.Biometric -> {
                    biometricRequest(title, AuthenticationRequest.Biometric.Fallback.CustomOption("Cancel"), cryptoObject, content)
                }
            }
        // The system cancels a prompt opened before our window has focus
        view().awaitWindowFocus()
        return slot.awaitResult { launcher.launch(request) }
    }

    private fun biometricRequest(
        title: String,
        fallback: AuthenticationRequest.Biometric.Fallback,
        cryptoObject: BiometricPrompt.CryptoObject?,
        content: AuthenticationRequest.BodyContent,
    ): AuthenticationRequest =
        AuthenticationRequest.Biometric
            .Builder(title, fallback)
            .setContent(content)
            .setMinStrength(AuthenticationRequest.Biometric.Strength.Class3(cryptoObject))
            .build()

    companion object {
        operator fun invoke(activity: FragmentActivity): CryptoPrompt {
            val slot = ResultSlot()
            val launcher = activity.registerForAuthenticationResult { slot.deliver(it) }
            return CryptoPrompt(launcher, slot) { activity.window.decorView }
        }
    }
}

private suspend fun View.awaitWindowFocus() {
    if (hasWindowFocus()) return
    suspendCancellableCoroutine { cont ->
        val listener =
            object : ViewTreeObserver.OnWindowFocusChangeListener {
                override fun onWindowFocusChanged(hasFocus: Boolean) {
                    if (!hasFocus) return
                    viewTreeObserver.removeOnWindowFocusChangeListener(this)
                    cont.resume(Unit)
                }
            }
        viewTreeObserver.addOnWindowFocusChangeListener(listener)
        cont.invokeOnCancellation { viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
    }
}

// A lot of pain was had before adhering to the rule that there should only be
// one of these per Activity
@Composable
fun rememberCryptoPrompt(): CryptoPrompt {
    val slot = remember { ResultSlot() }
    val launcher = rememberAuthenticationLauncher { slot.deliver(it) }
    val view = LocalView.current
    return remember(launcher, view) { CryptoPrompt(launcher, slot) { view } }
}
