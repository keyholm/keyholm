package app.keyholm.ui.createpasskey

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.AuthenticationRequest
import androidx.compose.runtime.mutableStateOf
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.exceptions.domerrors.DomError
import androidx.credentials.provider.ProviderCreateCredentialRequest
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.keystore.KeySecurityLevel
import app.keyholm.keystore.SecureKeyManager
import app.keyholm.store.DeniedNativeAppRepository
import app.keyholm.store.MigrationRepository
import app.keyholm.store.PasskeyRepository
import app.keyholm.ui.common.CryptoPrompt
import app.keyholm.ui.common.ErrorMessages
import app.keyholm.ui.common.WorkingIndicator
import app.keyholm.ui.common.appLabel
import app.keyholm.ui.common.promptContent
import app.keyholm.ui.common.userLabel
import app.keyholm.ui.theme.KeyholmTheme
import app.keyholm.webauthn.AuthenticatorData
import app.keyholm.webauthn.Caller
import app.keyholm.webauthn.CreationOptions
import app.keyholm.webauthn.CredentialUser
import app.keyholm.webauthn.PackageName
import app.keyholm.webauthn.PrfExtension
import app.keyholm.webauthn.RelyingParty
import app.keyholm.webauthn.SigningInput
import app.keyholm.webauthn.WebAuthnAlgorithm
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.security.Signature

internal sealed interface Registration<out T> {
    data class Ready<T>(
        val value: T,
    ) : Registration<T>

    sealed interface Failed : Registration<Nothing> {
        val toastMessage: String?
    }

    data class Internal(
        override val toastMessage: String? = null,
    ) : Failed

    data class MalformedRequest(
        val domError: DomError,
        val detail: String,
    ) : Failed {
        override val toastMessage = ErrorMessages.MALFORMED_REQUEST
    }

    data object AlreadyRegistered : Failed {
        override val toastMessage = ErrorMessages.ALREADY_REGISTERED
    }

    data class NoCreateOption(
        override val toastMessage: String? = null,
    ) : Failed

    data class Interrupted(
        override val toastMessage: String? = null,
    ) : Failed

    data class Canceled(
        override val toastMessage: String? = null,
    ) : Failed
}

internal data class CreateRequest(
    val providerRequest: ProviderCreateCredentialRequest,
    val callingRequest: CreatePublicKeyCredentialRequest,
    val options: CreationOptions,
)

internal data class KeyMaterial(
    val authenticators: AuthenticatorPolicy,
    val generated: SecureKeyManager.GeneratedCredential,
    val authData: AuthenticatorData,
    val toSign: SigningInput,
    val signature: Signature,
    val prfSecurityLevel: KeySecurityLevel?,
)

internal sealed interface KeyCreation {
    data class Created(
        val material: KeyMaterial,
    ) : KeyCreation

    data object DevicePropertiesUnavailable : KeyCreation
}

internal data class RegistrantInfo(
    val rp: RelyingParty,
    val user: CredentialUser,
    val callingPackage: PackageName,
    val credPropsRequested: Boolean,
    val prfRequested: Boolean,
    val discoverable: Boolean,
)

internal data class RegistrationContext(
    val info: RegistrantInfo,
    val challenge: String,
    val caller: Caller.Trusted,
    val algorithm: WebAuthnAlgorithm,
    val includeAttestation: Boolean,
    val includeDeviceProperties: Boolean,
    val identifyAsKeyholm: Boolean,
    val prfEvalInputs: PrfExtension.Inputs?,
)

internal fun registrationDescription(
    appLabel: String,
    algorithm: WebAuthnAlgorithm,
    includeAttestation: Boolean,
): String =
    buildString {
        append("$appLabel wants to create a device-bound ${algorithm.displayName} passkey")
        if (includeAttestation) {
            append(" with attestation, which reveals identifying information about your device")
        }
        append(":")
    }

internal fun registrationPromptContent(
    context: Context,
    info: RegistrantInfo,
    algorithm: WebAuthnAlgorithm,
    includeAttestation: Boolean,
    preferRpName: Boolean,
): AuthenticationRequest.BodyContent =
    promptContent(
        description =
            registrationDescription(
                context.appLabel(info.callingPackage),
                algorithm,
                includeAttestation,
            ),
        lastUsedAt = null,
        rp = info.rp,
        preferRpName = preferRpName,
        userLabel = userLabel(info.user.name, info.user.displayName),
    )

class Activity internal constructor(
    private val dispatcher: CoroutineDispatcher,
) : FragmentActivity() {
    constructor() : this(Dispatchers.IO)

    private val cryptoPrompt = CryptoPrompt(this)
    private val passkeyRepo by lazy { PasskeyRepository(applicationContext) }
    private val keyMaterial by lazy {
        RegistrationKeyMaterial(applicationContext, passkeyRepo, MigrationRepository(applicationContext), dispatcher)
    }
    private val deniedAppsRepo by lazy { DeniedNativeAppRepository(applicationContext) }
    private val creationChoice = Channel<CreationChoice?>(Channel.CONFLATED)
    private val creationOptions = mutableStateOf<CreationOptionsPrompt?>(null)
    private val prompts by lazy {
        RegistrationPrompts(
            applicationContext,
            intent,
            keyMaterial,
            cryptoPrompt,
            ::chooseCreationOptions,
        )
    }
    private val requestResolver by lazy {
        RegistrationRequestResolver(
            applicationContext,
            intent,
            passkeyRepo,
            deniedAppsRepo,
            dispatcher,
            prompts,
        )
    }
    private val ceremony by lazy {
        RegistrationCeremony(this, dispatcher, keyMaterial, cryptoPrompt, prompts, requestResolver)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setHideOverlayWindows(true)
        enableEdgeToEdge()
        setUpContent()
        lifecycleScope.launch { ceremony.run() }
    }

    private fun setUpContent() {
        setContent {
            KeyholmTheme {
                WorkingIndicator(ceremony.working.value)
                creationOptions.value?.let { prompt ->
                    CreationOptionsSheet(
                        prompt = prompt,
                        onChoice = {
                            creationOptions.value = null
                            creationChoice.trySend(it)
                        },
                        onDismiss = {
                            creationOptions.value = null
                            creationChoice.trySend(null)
                        },
                    )
                }
            }
        }
    }

    private suspend fun chooseCreationOptions(prompt: CreationOptionsPrompt): CreationChoice? {
        creationOptions.value = prompt
        return creationChoice.receive()
    }
}
