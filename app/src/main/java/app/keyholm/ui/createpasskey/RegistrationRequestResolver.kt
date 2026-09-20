package app.keyholm.ui.createpasskey

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.exceptions.domerrors.DataError
import androidx.credentials.exceptions.domerrors.EncodingError
import androidx.credentials.provider.PendingIntentHandler
import app.keyholm.provider.AttestationOffer
import app.keyholm.provider.CreationOffer
import app.keyholm.provider.preferRpName
import app.keyholm.store.DeniedNativeAppRepository
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.PasskeyRepository
import app.keyholm.store.StoredRecordException
import app.keyholm.ui.common.CryptoPrompt
import app.keyholm.ui.common.ErrorMessages
import app.keyholm.ui.common.appLabel
import app.keyholm.ui.common.promptContent
import app.keyholm.ui.common.userLabel
import app.keyholm.util.B64
import app.keyholm.webauthn.Caller
import app.keyholm.webauthn.ClientDataHash
import app.keyholm.webauthn.CreationOptions
import app.keyholm.webauthn.CredentialId
import app.keyholm.webauthn.CredentialUser
import app.keyholm.webauthn.IdentityPreference
import app.keyholm.webauthn.InvalidOptionsException
import app.keyholm.webauthn.PackageName
import app.keyholm.webauthn.PrfExtension
import app.keyholm.webauthn.RelyingParty
import app.keyholm.webauthn.RpId
import app.keyholm.webauthn.TrustDecision
import app.keyholm.webauthn.UserHandle
import app.keyholm.webauthn.WebAuthn
import app.keyholm.webauthn.parseCreationOptions
import app.keyholm.webauthn.resolveTrustDecision
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import java.io.IOException

private val USER_ID_BYTES = 1..64

private val log = Logger.withTag("app.keyholm.ui.createpasskey.RegistrationRequestResolver")

private typealias CreationOptionsChooser = suspend (prompt: CreationOptionsPrompt) -> CreationChoice?

internal class RegistrationRequestResolver(
    private val context: Context,
    private val intent: Intent,
    private val passkeyRepo: PasskeyRepository,
    private val deniedAppsRepo: DeniedNativeAppRepository,
    private val keyMaterial: RegistrationKeyMaterial,
    private val cryptoPrompt: CryptoPrompt,
    private val chooseCreationOptions: CreationOptionsChooser,
) {
    suspend fun prepareRegistration(offer: CreationOffer): Registration<RegistrationContext> {
        val existing =
            try {
                passkeyRepo.passkeys.first()
            } catch (e: IOException) {
                log.e(e) { "couldn't read the passkey store" }
                return Registration.Interrupted(ErrorMessages.CHECK_EXISTING_FAILED)
            } catch (e: StoredRecordException) {
                log.e(e) { "couldn't read the passkey store" }
                return Registration.Interrupted(ErrorMessages.CHECK_EXISTING_FAILED)
            }

        return when (val request = retrieveValidatedRequest()) {
            is Registration.Failed -> request
            is Registration.Ready -> resolveValidatedRegistration(request.value, existing, offer)
        }
    }

    private suspend fun resolveValidatedRegistration(
        request: CreateRequest,
        existing: List<PasskeyRecord>,
        offer: CreationOffer,
    ): Registration<RegistrationContext> {
        val options = request.options
        val rpId = RpId(options.rp.id)

        val decision =
            context.resolveTrustDecision(
                request.providerRequest.callingAppInfo,
                rpId,
                offer.nativeAppTrust,
                deniedAppsRepo.accepted.first(),
                request.callingRequest.clientDataHash?.let(::ClientDataHash),
            )
        val caller =
            when (decision) {
                is TrustDecision.Denied -> return denyRegistration(decision)
                is TrustDecision.Allowed -> decision.caller
            }

        val excludeIds = options.excludeCredentials.mapTo(mutableSetOf()) { CredentialId(it.id) }
        return if (existing.any { it.credentialId in excludeIds && it.rp.id == rpId }) {
            confirmAlreadyRegistered(request, offer)
        } else {
            buildRegistrationContext(request, rpId, offer, caller)
        }
    }

    private suspend fun confirmAlreadyRegistered(
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

    private suspend fun buildRegistrationContext(
        request: CreateRequest,
        rpId: RpId,
        offer: CreationOffer,
        caller: Caller.Trusted,
    ): Registration<RegistrationContext> {
        val options = request.options
        val userId =
            try {
                B64.dec(options.user.id)
            } catch (e: IllegalArgumentException) {
                log.e(e) { "couldn't decode user.id" }
                return Registration.MalformedRequest(EncodingError(), "user.id is not valid base64url")
            }
        if (userId.size !in USER_ID_BYTES) {
            return Registration.MalformedRequest(DataError(), "user.id must be 1 to 64 bytes")
        }
        val userHandle = UserHandle.of(userId)

        val choice = resolveCreationChoice(request, offer) ?: return Registration.Canceled()

        val prfEvalSalts =
            try {
                PrfExtension.saltsForCreation(options)
            } catch (e: IllegalArgumentException) {
                log.e(e) { "couldn't decode PRF salt" }
                return Registration.MalformedRequest(EncodingError(), "prf salt is not valid base64url")
            }

        return Registration.Ready(
            RegistrationContext(
                info =
                    RegistrantInfo(
                        rp = RelyingParty(rpId, options.rp.name),
                        user =
                            CredentialUser(
                                handle = userHandle,
                                name = options.user.name,
                                displayName = options.user.displayName,
                            ),
                        callingPackage = PackageName(request.providerRequest.callingAppInfo.packageName),
                        credPropsRequested = options.extensions?.credProps == true,
                        prfRequested = PrfExtension.requestedAtCreation(options),
                    ),
                challenge = options.challenge,
                caller = caller,
                algorithm = choice.algorithm,
                includeAttestation = choice.includeAttestation,
                identifyAsKeyholm = choice.identifyAsKeyholm,
                prfEvalSalts = prfEvalSalts,
            ),
        )
    }

    private suspend fun denyRegistration(decision: TrustDecision.Denied): Registration.Failed {
        if (decision is TrustDecision.Denied.NativeApp) {
            val ref = decision.ref
            deniedAppsRepo.record(ref.rpId, ref.packageName, ref.certFingerprints).onFailure {
                log.e(it) { "couldn't record the denial" }
                Toast.makeText(context, ErrorMessages.DENIAL_RECORD_FAILED, Toast.LENGTH_LONG).show()
            }
        }
        return Registration.NoCreateOption(decision.message)
    }

    private suspend fun resolveCreationChoice(
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

    private fun parseOptions(requestJson: String): Registration<CreationOptions> =
        try {
            Registration.Ready(parseCreationOptions(requestJson))
        } catch (e: InvalidOptionsException) {
            log.e(e) { "invalid creation options" }
            Registration.MalformedRequest(DataError(), e.detail)
        } catch (e: SerializationException) {
            log.e(e) { "couldn't parse the creation options" }
            Registration.MalformedRequest(EncodingError(), "requestJson is not valid creation options")
        }

    private fun retrieveValidatedRequest(): Registration<CreateRequest> {
        val providerRequest = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        val callingRequest = providerRequest?.callingRequest
        return when {
            providerRequest == null || callingRequest !is CreatePublicKeyCredentialRequest -> {
                Registration.Internal()
            }

            else -> {
                when (val parsed = parseOptions(callingRequest.requestJson)) {
                    is Registration.Failed -> {
                        parsed
                    }

                    is Registration.Ready -> {
                        if (WebAuthn.supportsAttachment(parsed.value)) {
                            Registration.Ready(CreateRequest(providerRequest, callingRequest, parsed.value))
                        } else {
                            Registration.NoCreateOption(ErrorMessages.DEVICE_BOUND_NOT_SUPPORTED)
                        }
                    }
                }
            }
        }
    }
}
