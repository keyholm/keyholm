package app.keyholm.ui.getpasskey

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.biometric.BiometricPrompt
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.EncodingError
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderGetCredentialRequest
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.provider.PendingSignatures
import app.keyholm.provider.credentialId
import app.keyholm.provider.nativeAppTrust
import app.keyholm.provider.preferRpName
import app.keyholm.store.DeniedNativeAppRepository
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.PasskeyRepository
import app.keyholm.store.StoredRecordException
import app.keyholm.ui.common.CANCELLATION_ERRORS
import app.keyholm.ui.common.CryptoPrompt
import app.keyholm.ui.common.ErrorMessages
import app.keyholm.ui.common.Outcome
import app.keyholm.ui.common.PRF_PROMPT_TITLE
import app.keyholm.ui.common.PromptResult
import app.keyholm.ui.common.appLabel
import app.keyholm.ui.common.promptContent
import app.keyholm.util.logger
import app.keyholm.webauthn.AssetLinkStatement
import app.keyholm.webauthn.AuthenticatorData
import app.keyholm.webauthn.Caller
import app.keyholm.webauthn.ClientDataHash
import app.keyholm.webauthn.ClientDataJson
import app.keyholm.webauthn.ClientDataType
import app.keyholm.webauthn.CredentialResponseJson
import app.keyholm.webauthn.DerSignature
import app.keyholm.webauthn.NativeAppTrust
import app.keyholm.webauthn.PackageName
import app.keyholm.webauthn.PrfExtension
import app.keyholm.webauthn.RequestOptions
import app.keyholm.webauthn.RpId
import app.keyholm.webauthn.SigningInput
import app.keyholm.webauthn.TrustDecision
import app.keyholm.webauthn.WebAuthn
import app.keyholm.webauthn.parseRequestOptionsOrLog
import app.keyholm.webauthn.resolveTrustDecision
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.Signature
import java.time.Instant

private data class SignInContext(
    val record: PasskeyRecord,
    val callingPackage: PackageName,
    val newSignCount: Int,
    val clientDataJSON: ClientDataJson,
    val authData: AuthenticatorData,
    val toSign: SigningInput,
    val signature: Signature,
    val allowedAuthenticators: AuthenticatorPolicy,
    val prfSalts: PrfExtension.Salts?,
)

private sealed interface SingleTap {
    data object Canceled : SingleTap

    sealed interface Proceeding : SingleTap {
        val signature: Signature?

        data class Authorized(
            override val signature: Signature,
        ) : Proceeding

        data class NeedsPrompt(
            override val signature: Signature?,
        ) : Proceeding
    }
}

private sealed interface SignInResult {
    data class Ready(
        val context: SignInContext,
    ) : SignInResult

    sealed interface Failed : SignInResult {
        val toastMessage: String?
    }

    data class Internal(
        override val toastMessage: String? = null,
    ) : Failed

    data class MalformedRequest(
        val detail: String,
    ) : Failed {
        override val toastMessage = ErrorMessages.MALFORMED_REQUEST
    }

    data class NoCredential(
        override val toastMessage: String? = null,
    ) : Failed

    data class Interrupted(
        override val toastMessage: String? = null,
    ) : Failed
}

private fun SignInResult.Failed.toException(): GetCredentialException =
    when (this) {
        is SignInResult.Internal -> GetCredentialUnknownException()
        is SignInResult.MalformedRequest -> GetPublicKeyCredentialDomException(EncodingError(), detail)
        is SignInResult.NoCredential -> NoCredentialException()
        is SignInResult.Interrupted -> GetCredentialInterruptedException()
    }

class Activity : FragmentActivity() {
    private val passkeyRepo by lazy { PasskeyRepository(applicationContext) }
    private val keyMaterial by lazy { SignInKeyMaterial(passkeyRepo) }
    private val cryptoPrompt = CryptoPrompt(this)
    private val deniedAppsRepo by lazy { DeniedNativeAppRepository(applicationContext) }
    private val log = logger()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The user has already selected one of our entries by this point.
        val providerRequest = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
        // If we offered the single tap and they cancelled, treat it as
        // cancelling everything
        val singleTap =
            when (val tap = resolveSingleTap(providerRequest)) {
                SingleTap.Canceled -> {
                    cancelGetCredential()
                    return
                }

                is SingleTap.Proceeding -> {
                    tap
                }
            }

        // We've already potentially shown the quick tap prompt
        // but we don't know if it's authorized signatures with our key or not.
        lifecycleScope.launch {
            val trust = intent.nativeAppTrust(applicationContext)
            val userAccepted = deniedAppsRepo.accepted.first()
            // We reuse the single tap pending signature if possible, even though we may need to
            // reprompt.
            val signIn =
                when (val result = prepareSignIn(providerRequest, singleTap.signature, trust, userAccepted)) {
                    is SignInResult.Ready -> result.context
                    is SignInResult.Failed -> return@launch failGetCredential(result.toException(), result.toastMessage)
                }

            val promptResult =
                when (singleTap) {
                    // Reuse the single tap signature if we have it was successful
                    is SingleTap.Proceeding.Authorized -> {
                        PromptResult.Success(BiometricPrompt.CryptoObject(singleTap.signature))
                    }

                    // Reprompt if there was no single tap or we otherwise need to
                    is SingleTap.Proceeding.NeedsPrompt -> {
                        cryptoPrompt.authenticate(
                            title = "Sign in",
                            cryptoObject = BiometricPrompt.CryptoObject(signIn.signature),
                            allowedAuthenticators = signIn.allowedAuthenticators,
                            content =
                                promptContent(
                                    description = "${appLabel(signIn.callingPackage)} wants to use a passkey:",
                                    lastUsedAt = signIn.record.lastUsedAt,
                                    rpId = signIn.record.rp.id,
                                    rpName = signIn.record.rp.name,
                                    preferRpName = intent.preferRpName(),
                                    userName = signIn.record.user.name,
                                    displayName = signIn.record.user.displayName,
                                ),
                        )
                    }
                }

            when (promptResult) {
                is PromptResult.Success -> {
                    finishSignIn(signIn, promptResult.crypto)
                }

                PromptResult.NoCryptoObject -> {
                    failGetCredential(GetCredentialUnknownException(), ErrorMessages.GET_NO_SIGNATURE)
                }

                PromptResult.Canceled -> {
                    cancelGetCredential()
                }

                PromptResult.Failed -> {
                    failGetCredential(GetCredentialUnknownException())
                }
            }
        }
    }

    private fun resolveSingleTap(providerRequest: ProviderGetCredentialRequest?): SingleTap {
        val signature = PendingSignatures.take(intent.credentialId())
        val result = providerRequest?.biometricPromptResult
        return when {
            result == null -> {
                SingleTap.Proceeding.NeedsPrompt(signature)
            }

            result.isSuccessful -> {
                signature?.let { SingleTap.Proceeding.Authorized(it) } ?: SingleTap.Proceeding.NeedsPrompt(null)
            }

            result.authenticationError?.errorCode?.let { it in CANCELLATION_ERRORS } == true -> {
                SingleTap.Canceled
            }

            else -> {
                SingleTap.Proceeding.NeedsPrompt(signature)
            }
        }
    }

    private suspend fun prepareSignIn(
        providerRequest: ProviderGetCredentialRequest?,
        existingSignature: Signature?,
        trust: NativeAppTrust,
        userAccepted: Map<RpId, List<AssetLinkStatement>>,
    ): SignInResult {
        val option =
            providerRequest
                ?.credentialOptions
                ?.filterIsInstance<GetPublicKeyCredentialOption>()
                ?.firstOrNull()
        if (providerRequest == null || option == null) return SignInResult.Internal()

        val credId = intent.credentialId()

        val records =
            passkeysOrLog().getOrElse {
                return SignInResult.Interrupted(ErrorMessages.LOOKUP_FAILED)
            }

        val record = records.firstOrNull { it.credentialId == credId }
        if (record == null) return SignInResult.NoCredential()

        val request = parseRequestOptionsOrLog(option.requestJson, log)
        if (request == null) return SignInResult.MalformedRequest("requestJson is not valid request options")

        val rpId = record.rp.id
        if (RpId(request.rpId) != rpId) return logRpIdMismatch(request.rpId, rpId)

        val caller =
            when (
                val decision =
                    resolveTrustDecision(
                        providerRequest.callingAppInfo,
                        rpId,
                        trust,
                        userAccepted,
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

        val prfSalts =
            prfSaltsOrLog(record, request).getOrElse {
                return SignInResult.MalformedRequest("prf salt is not valid base64url")
            }

        return when (
            val ctx =
                buildSignInContext(
                    record,
                    PackageName(providerRequest.callingAppInfo.packageName),
                    request,
                    caller,
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
            Toast.makeText(this, ErrorMessages.DENIAL_RECORD_FAILED, Toast.LENGTH_LONG).show()
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
        request: RequestOptions,
        caller: Caller.Trusted,
        existingSignature: Signature?,
        prfSalts: PrfExtension.Salts?,
    ): Outcome<SignInContext> {
        val cd =
            WebAuthn.clientData(
                type = ClientDataType.Get,
                challengeB64Url = request.challenge,
                caller = caller,
            )
        val newSignCount = record.signCount + 1
        val authData = WebAuthn.assertionAuthData(record.rp.id, newSignCount)
        val toSign = SigningInput(authData.bytes + cd.hash.bytes)

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
                                clientDataJSON = cd.json,
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

    private suspend fun finishSignIn(
        signIn: SignInContext,
        crypto: BiometricPrompt.CryptoObject,
    ) {
        val sig =
            crypto.signature
                ?: return failGetCredential(GetCredentialUnknownException(), ErrorMessages.GET_NO_SIGNATURE)

        when (
            val derSignature =
                try {
                    sig.update(signIn.toSign.bytes)
                    Outcome.Success(DerSignature(sig.sign()))
                } catch (e: GeneralSecurityException) {
                    log.e(e) { "signing failed" }
                    Outcome.Failure(ErrorMessages.SIGN_FAILED_GET)
                }
        ) {
            is Outcome.Failure -> {
                failGetCredential(GetCredentialUnknownException(), derSignature.toastMessage)
            }

            is Outcome.Success -> {
                val updatedRecord =
                    signIn.record.copy(
                        signCount = signIn.newSignCount,
                        lastUsedAt = Instant.now(),
                    )
                when (val updated = keyMaterial.updateRecord(updatedRecord)) {
                    is Outcome.Failure -> failGetCredential(GetCredentialUnknownException(), updated.toastMessage)
                    is Outcome.Success -> finishSignInWithSignature(signIn, derSignature.value)
                }
            }
        }
    }

    private suspend fun finishSignInWithSignature(
        signIn: SignInContext,
        derSignature: DerSignature,
    ) {
        val prfSalts = signIn.prfSalts
        if (prfSalts == null) {
            respond(signIn, derSignature, prfResults = null)
            return
        }

        when (val mac = keyMaterial.macFor(signIn.record)) {
            is Outcome.Failure -> {
                failGetCredential(GetCredentialUnknownException(), mac.toastMessage)
            }

            is Outcome.Success -> {
                when (
                    val hmac = keyMaterial.hmacAuthenticatorsFor(signIn.record.hmacKeyAlias)
                ) {
                    is Outcome.Failure -> {
                        failGetCredential(GetCredentialUnknownException(), hmac.toastMessage)
                    }

                    is Outcome.Success -> {
                        val authorized =
                            cryptoPrompt.authenticate(
                                title = PRF_PROMPT_TITLE,
                                cryptoObject = BiometricPrompt.CryptoObject(mac.value),
                                allowedAuthenticators = hmac.value,
                                content =
                                    promptContent(
                                        description = "${appLabel(signIn.callingPackage)} wants access to the secret for:",
                                        lastUsedAt = signIn.record.lastUsedAt,
                                        rpId = signIn.record.rp.id,
                                        rpName = signIn.record.rp.name,
                                        preferRpName = intent.preferRpName(),
                                        userName = signIn.record.user.name,
                                        displayName = signIn.record.user.displayName,
                                    ),
                            ) as? PromptResult.Success
                        val prfResults = authorized?.crypto?.mac?.let { PrfExtension.evaluate(it, prfSalts) }
                        respond(signIn, derSignature, prfResults)
                    }
                }
            }
        }
    }

    private fun respond(
        signIn: SignInContext,
        derSignature: DerSignature,
        prfResults: PrfExtension.Results?,
    ) {
        val responseJson =
            CredentialResponseJson.assertionResponseJson(
                credentialId = signIn.record.credentialId,
                clientDataJSON = signIn.clientDataJSON,
                authData = signIn.authData,
                derSignature = derSignature,
                userHandle = signIn.record.user.handle,
                prfResults = prfResults,
            )

        val result = Intent()
        PendingIntentHandler.setGetCredentialResponse(
            result,
            GetCredentialResponse(PublicKeyCredential(responseJson)),
        )
        setResult(RESULT_OK, result)
        finish()
    }
}
