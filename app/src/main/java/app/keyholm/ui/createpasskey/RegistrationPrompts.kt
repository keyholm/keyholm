package app.keyholm.ui.createpasskey

import android.content.Context
import android.content.Intent
import app.keyholm.provider.AttestationOffer
import app.keyholm.provider.CreationOffer
import app.keyholm.provider.preferRpName
import app.keyholm.ui.common.CryptoPrompt
import app.keyholm.ui.common.appLabel
import app.keyholm.ui.common.promptContent
import app.keyholm.ui.common.userLabel
import app.keyholm.webauthn.IdentityPreference
import app.keyholm.webauthn.PackageName
import app.keyholm.webauthn.RelyingParty
import app.keyholm.webauthn.RpId

internal typealias CreationOptionsChooser = suspend (prompt: CreationOptionsPrompt) -> CreationChoice?

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
                            promptContent(
                                description =
                                    registrationDescription(
                                        context.appLabel(PackageName(request.providerRequest.callingAppInfo.packageName)),
                                        choice.algorithm,
                                        choice.includeAttestation,
                                    ),
                                lastUsedAt = null,
                                rp = RelyingParty(RpId(request.options.rp.id), request.options.rp.name),
                                preferRpName = intent.preferRpName(),
                                userLabel = userLabel(request.options.user.name, request.options.user.displayName),
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
    ): CreationChoice? {
        val options = request.options
        val algorithms = offer.algorithms
        val attestation =
            when (offer.attestation) {
                AttestationOffer.NotRequested -> OptionChoice.Fixed(false)
                AttestationOffer.Ask -> OptionChoice.Ask(initial = false)
            }
        val identity =
            when (offer.identity) {
                IdentityPreference.ALWAYS_ASK -> OptionChoice.Ask(initial = true)
                IdentityPreference.ENABLED -> OptionChoice.Fixed(true)
                IdentityPreference.DISABLED -> OptionChoice.Fixed(false)
            }
        val needsSheet =
            algorithms.size > 1 ||
                attestation is OptionChoice.Ask ||
                identity is OptionChoice.Ask
        return if (needsSheet) {
            chooseCreationOptions(
                CreationOptionsPrompt(
                    rpId = RpId(options.rp.id),
                    userName = options.user.name,
                    algorithms = algorithms,
                    attestation = attestation,
                    identity = identity,
                ),
            )
        } else {
            CreationChoice(algorithms.single(), attestation.initial, identity.initial)
        }
    }
}
