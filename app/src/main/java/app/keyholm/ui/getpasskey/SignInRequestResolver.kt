package app.keyholm.ui.getpasskey

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.provider.ProviderGetCredentialRequest
import app.keyholm.provider.credentialId
import app.keyholm.store.DeniedNativeAppRepository
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.PasskeyRepository
import app.keyholm.store.StoredRecordException
import app.keyholm.ui.common.ErrorMessages
import app.keyholm.ui.common.Outcome
import app.keyholm.util.logger
import app.keyholm.webauthn.Caller
import app.keyholm.webauthn.ClientDataHash
import app.keyholm.webauthn.ClientDataType
import app.keyholm.webauthn.PackageName
import app.keyholm.webauthn.PrfExtension
import app.keyholm.webauthn.RequestOptions
import app.keyholm.webauthn.RpId
import app.keyholm.webauthn.SigningInput
import app.keyholm.webauthn.TrustDecision
import app.keyholm.webauthn.TrustPolicy
import app.keyholm.webauthn.WebAuthn
import app.keyholm.webauthn.parseRequestOptionsOrLog
import app.keyholm.webauthn.resolveTrustDecision
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.security.Signature

internal class SignInRequestResolver(
    private val context: Context,
    private val intent: Intent,
    private val passkeyRepo: PasskeyRepository,
    private val deniedAppsRepo: DeniedNativeAppRepository,
    private val keyMaterial: SignInKeyMaterial,
    private val dispatcher: CoroutineDispatcher,
) {
    private val log = logger()

    suspend fun prepareSignIn(
        providerRequest: ProviderGetCredentialRequest?,
        existingSignature: Signature?,
        policy: TrustPolicy,
    ): SignInResult {
        val option =
            providerRequest
                ?.credentialOptions
                ?.filterIsInstance<GetPublicKeyCredentialOption>()
                ?.firstOrNull()
        if (providerRequest == null || option == null) return SignInResult.Internal()

        val records =
            passkeysOrLog().getOrElse {
                return SignInResult.Interrupted(ErrorMessages.LOOKUP_FAILED)
            }

        val record = records.firstOrNull { it.credentialId == intent.credentialId() }
        if (record == null) return SignInResult.NoCredential()

        val request = parseRequestOptionsOrLog(option.requestJson, log)
        if (request == null) return SignInResult.MalformedRequest("requestJson is not valid request options")

        val rpId = record.rp.id
        if (RpId(request.rpId) != rpId) return logRpIdMismatch(request.rpId, rpId)

        val caller =
            when (
                val decision =
                    context.resolveTrustDecision(
                        providerRequest.callingAppInfo,
                        rpId,
                        policy,
                        dispatcher,
                        option.clientDataHash?.let(::ClientDataHash),
                    )
            ) {
                is TrustDecision.Denied -> {
                    recordDenial(decision)
                    return SignInResult.NoCredential(decision.message)
                }

                is TrustDecision.Allowed -> {
                    decision.caller
                }
            }

        return buildSignInResult(providerRequest, record, request, caller, existingSignature)
    }

    private suspend fun buildSignInResult(
        providerRequest: ProviderGetCredentialRequest,
        record: PasskeyRecord,
        request: RequestOptions,
        caller: Caller.Trusted,
        existingSignature: Signature?,
    ): SignInResult {
        val prfSalts =
            prfSaltsOrLog(record, request).getOrElse {
                return SignInResult.MalformedRequest("prf salt is not valid base64url")
            }

        val clientData =
            WebAuthn.clientData(
                type = ClientDataType.Get,
                challengeB64Url = request.challenge,
                caller = caller,
            )

        return when (
            val ctx =
                buildSignInContext(
                    record,
                    PackageName(providerRequest.callingAppInfo.packageName),
                    clientData,
                    existingSignature,
                    prfSalts,
                )
        ) {
            is Outcome.Success -> SignInResult.Ready(ctx.value)
            is Outcome.Failure -> SignInResult.Internal(ctx.toastMessage)
        }
    }

    private suspend fun passkeysOrLog(): Result<List<PasskeyRecord>> =
        try {
            Result.success(passkeyRepo.passkeys.first())
        } catch (e: IOException) {
            log.e(e) { "couldn't read the passkey store" }
            Result.failure(e)
        } catch (e: StoredRecordException) {
            log.e(e) { "couldn't read the passkey store" }
            Result.failure(e)
        }

    private suspend fun recordDenial(decision: TrustDecision.Denied) {
        if (decision !is TrustDecision.Denied.NativeApp) return
        val ref = decision.ref
        deniedAppsRepo.record(ref.rpId, ref.packageName, ref.certFingerprints).onFailure {
            log.e(it) { "couldn't record the denial" }
            Toast.makeText(context, ErrorMessages.DENIAL_RECORD_FAILED, Toast.LENGTH_LONG).show()
        }
    }

    private fun prfSaltsOrLog(
        record: PasskeyRecord,
        request: RequestOptions,
    ): Result<PrfExtension.Salts?> =
        try {
            Result.success(if (record.hasPrf) PrfExtension.saltsForAssertion(request, record.credentialId) else null)
        } catch (e: IllegalArgumentException) {
            log.e(e) { "couldn't decode PRF salt" }
            Result.failure(e)
        }

    private fun logRpIdMismatch(
        requestRpId: String,
        storedRpId: RpId,
    ): SignInResult {
        log.e { "request rpId $requestRpId doesn't match stored ${storedRpId.value}" }
        return SignInResult.NoCredential()
    }

    private suspend fun buildSignInContext(
        record: PasskeyRecord,
        callingPackage: PackageName,
        clientData: WebAuthn.ClientData,
        existingSignature: Signature?,
        prfSalts: PrfExtension.Salts?,
    ): Outcome<SignInContext> {
        val newSignCount = record.signCount + 1
        val authData = WebAuthn.assertionAuthData(record.rp.id, newSignCount)
        val toSign = SigningInput(authData.bytes + clientData.hash.bytes)

        val algorithm = record.keystore.coseAlgorithm
        val signatureOutcome = existingSignature?.let { Outcome.Success(it) } ?: keyMaterial.signFor(record, algorithm)
        return when (val signature = signatureOutcome) {
            is Outcome.Failure -> {
                signature
            }

            is Outcome.Success -> {
                when (
                    val authenticators = keyMaterial.authenticatorsFor(record.keyAlias, algorithm)
                ) {
                    is Outcome.Failure -> {
                        authenticators
                    }

                    is Outcome.Success -> {
                        Outcome.Success(
                            SignInContext(
                                record = record,
                                callingPackage = callingPackage,
                                newSignCount = newSignCount,
                                clientDataJSON = clientData.json,
                                authData = authData,
                                toSign = toSign,
                                signature = signature.value,
                                allowedAuthenticators = authenticators.value,
                                prfSalts = prfSalts,
                            ),
                        )
                    }
                }
            }
        }
    }
}
