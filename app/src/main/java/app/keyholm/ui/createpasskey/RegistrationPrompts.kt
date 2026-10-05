package app.keyholm.ui.createpasskey

import android.content.Context
import android.content.Intent
import app.keyholm.authenticator.IdentityPreference
import app.keyholm.ctap.RpId
import app.keyholm.provider.AttestationOffer
import app.keyholm.provider.CreationOffer
import app.keyholm.provider.preferRpName
import app.keyholm.ui.common.CryptoPrompt

internal typealias CreationOptionsChooser = suspend (prompt: CreationOptionsPrompt) -> CreationChoice?

internal suspend fun CreationOptionsChooser.chooseIfNeeded(prompt: CreationOptionsPrompt): CreationChoice? {
    val needsSheet =
        prompt.algorithms.size > 1 ||
            prompt.attestation is OptionChoice.Ask ||
            prompt.identity is OptionChoice.Ask
    return if (needsSheet) {
        this(prompt)
    } else {
        CreationChoice(prompt.algorithms.single(), prompt.attestation.initial, includeDeviceProperties = false, prompt.identity.initial)
    }
}

internal fun AttestationOffer.attestationChoice(): OptionChoice =
    when (this) {
        AttestationOffer.NotRequested -> OptionChoice.Fixed(false)
        AttestationOffer.Ask -> OptionChoice.Ask(initial = false)
    }

internal fun IdentityPreference.identityChoice(): OptionChoice =
    when (this) {
        IdentityPreference.ALWAYS_ASK -> OptionChoice.Ask(initial = true)
        IdentityPreference.ENABLED -> OptionChoice.Fixed(true)
        IdentityPreference.DISABLED -> OptionChoice.Fixed(false)
    }

internal class RegistrationPrompts(
    private val context: Context,
    private val intent: Intent,
    private val keyMaterial: RegistrationKeyMaterial,
    private val cryptoPrompt: CryptoPrompt,
    private val chooseCreationOptions: CreationOptionsChooser,
) {
    suspend fun confirmAlreadyRegistered(
        request: CreateRequest,
        offer: CreationOffer,
        info: RegistrantInfo,
    ): Registration<RegistrationContext> {
        val choice = resolveCreationChoice(request, offer) ?: return Registration.Canceled()
        return when (val r = keyMaterial.resolveAuthenticators(offer.authenticators)) {
            is Registration.Failed -> {
                r
            }

            // WebAuthn 6.3.2: the gesture authorizes disclosing that the credential exists.
            is Registration.Ready -> {
                if (
                    cryptoPrompt.confirm(
                        title = "Create passkey",
                        allowedAuthenticators = r.value,
                        content =
                            registrationPromptContent(
                                context,
                                info,
                                choice.algorithm,
                                choice.includeAttestation,
                                intent.preferRpName(),
                            ),
                    )
                ) {
                    Registration.AlreadyRegistered
                } else {
                    Registration.Canceled()
                }
            }
        }
    }

    suspend fun resolveCreationChoice(
        request: CreateRequest,
        offer: CreationOffer,
    ): CreationChoice? =
        chooseCreationOptions.chooseIfNeeded(
            CreationOptionsPrompt(
                rpId = RpId(request.options.rp.id),
                userName = request.options.user.name,
                algorithms = offer.algorithms,
                attestation = offer.attestation.attestationChoice(),
                identity = offer.identity.identityChoice(),
                devicePropertiesAvailable = true,
            ),
        )

    suspend fun chooseWithoutDeviceProperties(
        offer: CreationOffer,
        registration: RegistrationContext,
    ): CreationChoice? =
        chooseCreationOptions(
            CreationOptionsPrompt(
                rpId = registration.info.rp.id,
                userName = registration.info.user.name,
                algorithms = offer.algorithms,
                attestation = OptionChoice.Ask(initial = registration.includeAttestation),
                identity =
                    if (offer.identity == IdentityPreference.ALWAYS_ASK) {
                        OptionChoice.Ask(initial = registration.identifyAsKeyholm)
                    } else {
                        OptionChoice.Fixed(registration.identifyAsKeyholm)
                    },
                devicePropertiesAvailable = false,
            ),
        )
}
