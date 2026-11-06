package app.keyholm.ui.createpasskey

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.widget.Toast
import app.keyholm.authenticator.CredentialKeyCreation
import app.keyholm.authenticator.CredentialKeyMaterial
import app.keyholm.ctap.AuthenticatorData
import app.keyholm.ctap.ClientDataHash
import app.keyholm.ctap.CredentialId
import app.keyholm.ctap.KEYHOLM_AAGUID
import app.keyholm.ctap.ZERO_AAGUID
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.keystore.HmacKeyManager
import app.keyholm.keystore.SecureKeyManager
import app.keyholm.store.MigrationRepository
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.PasskeyRepository
import app.keyholm.ui.common.AuthenticatorsResolution
import app.keyholm.ui.common.ErrorMessages
import app.keyholm.ui.common.Outcome
import app.keyholm.ui.common.resolveCreateAuthenticators
import app.keyholm.util.logger
import app.keyholm.webauthn.SigningInput
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import javax.crypto.Mac

private fun CredentialKeyCreation.Failed.toastMessage(): String? =
    when (this) {
        is CredentialKeyCreation.SecureElementUnavailable -> message
        CredentialKeyCreation.KeyInvalidated -> ErrorMessages.KEY_INVALIDATED
        CredentialKeyCreation.SecurityError -> ErrorMessages.SECURITY_ERROR_CREATE
        is CredentialKeyCreation.HardwareError -> ErrorMessages.hardwareError(cause)
    }

internal class RegistrationKeyMaterial(
    private val context: Context,
    private val passkeyRepo: PasskeyRepository,
    private val migrationRepo: MigrationRepository,
    private val dispatcher: CoroutineDispatcher,
) {
    private val keys = CredentialKeyMaterial(dispatcher)
    private val hmacKeyManager by lazy { HmacKeyManager() }
    private val log = logger()

    suspend fun discard(credentialId: CredentialId) = keys.discard(credentialId)

    suspend fun resolveAuthenticators(policy: AuthenticatorPolicy): Registration<AuthenticatorPolicy> =
        when (val r = withContext(dispatcher) { resolveCreateAuthenticators(context, policy) }) {
            is AuthenticatorsResolution.Ready -> Registration.Ready(r.value)
            is AuthenticatorsResolution.Unavailable -> Registration.NoCreateOption(r.message)
        }

    suspend fun create(
        registration: RegistrationContext,
        credentialId: CredentialId,
        clientDataHash: ClientDataHash,
        createAuthenticators: AuthenticatorPolicy,
        invalidateOnBiometricEnrollment: Boolean,
    ): Registration<KeyCreation> {
        val authenticators =
            when (val r = resolveAuthenticators(createAuthenticators)) {
                is Registration.Failed -> return r
                is Registration.Ready -> r.value
            }
        val request =
            SecureKeyManager.CredentialKeyRequest(
                credentialId = credentialId,
                attestationChallenge = clientDataHash,
                algorithm = registration.algorithm,
                authenticators = authenticators,
                invalidateOnBiometricEnrollment = invalidateOnBiometricEnrollment,
                includeDeviceProperties = registration.includeDeviceProperties,
            )
        return when (val created = keys.create(request, registration.info.prfRequested)) {
            is CredentialKeyCreation.Created -> {
                val aaguid = if (registration.identifyAsKeyholm) KEYHOLM_AAGUID else ZERO_AAGUID
                val authData =
                    AuthenticatorData.forRegistration(
                        registration.info.rp.id,
                        credentialId,
                        created.keys.generated.publicKey,
                        aaguid,
                        request.algorithm,
                    )
                Registration.Ready(
                    KeyCreation.Created(
                        KeyMaterial(
                            authenticators = request.authenticators,
                            generated = created.keys.generated,
                            authData = authData,
                            toSign = SigningInput(authData.bytes + clientDataHash.bytes),
                            signature = created.keys.signature,
                            prfSecurityLevel = created.keys.prfSecurityLevel,
                        ),
                    ),
                )
            }

            CredentialKeyCreation.DevicePropertiesUnavailable -> {
                Registration.Ready(KeyCreation.DevicePropertiesUnavailable)
            }

            is CredentialKeyCreation.Failed -> {
                Registration.Internal(created.toastMessage())
            }
        }
    }

    suspend fun persist(record: PasskeyRecord): Registration<Unit> =
        passkeyRepo.put(record).fold(
            onSuccess = {
                migrationRepo.delete(record.rp.id, record.user.name).onFailure { error ->
                    log.e(error) { "placeholder cleanup failed" }
                    Toast.makeText(context, ErrorMessages.PLACEHOLDER_CLEANUP_FAILED, Toast.LENGTH_LONG).show()
                }
                Registration.Ready(Unit)
            },
            onFailure = {
                log.e(it) { "saving passkey failed" }
                Registration.Interrupted(ErrorMessages.SAVE_FAILED)
            },
        )

    suspend fun macFor(credentialId: CredentialId): Outcome<Mac> =
        try {
            Outcome.Success(withContext(dispatcher) { hmacKeyManager.macFor(credentialId) })
        } catch (e: KeyPermanentlyInvalidatedException) {
            log.e(e) { "macFor failed" }
            Outcome.Failure(ErrorMessages.KEY_INVALIDATED)
        } catch (e: GeneralSecurityException) {
            log.e(e) { "macFor failed" }
            Outcome.Failure(ErrorMessages.SECURITY_ERROR_CREATE)
        }

    suspend fun hmacAuthenticatorsFor(credentialId: CredentialId): Outcome<AuthenticatorPolicy> =
        try {
            Outcome.Success(withContext(dispatcher) { hmacKeyManager.allowedAuthenticatorsForHmac(credentialId) })
        } catch (e: GeneralSecurityException) {
            log.e(e) { "hmacAuthenticatorsFor failed" }
            Outcome.Failure(ErrorMessages.SECURITY_ERROR_CREATE)
        }
}
