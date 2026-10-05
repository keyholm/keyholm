package app.keyholm.ctap.handlers

import androidx.biometric.BiometricPrompt
import app.keyholm.authenticator.AssertionKeyMaterial
import app.keyholm.authenticator.KeyAccess
import app.keyholm.ctap.AuthenticatorData
import app.keyholm.ctap.CredentialId
import app.keyholm.ctap.Ctap2Response
import app.keyholm.ctap.Ctap2Result
import app.keyholm.ctap.Ctap2Status
import app.keyholm.ctap.PUBLIC_KEY_TYPE
import app.keyholm.ctap.RpId
import app.keyholm.ctap.requests.GetAssertionRequest
import app.keyholm.ctap.requests.parseGetAssertion
import app.keyholm.settings.Settings
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.PasskeyRepository
import app.keyholm.store.readStore
import app.keyholm.store.writeStore
import app.keyholm.ui.common.PromptRelay
import app.keyholm.ui.common.PromptResult
import app.keyholm.ui.common.promptContent
import app.keyholm.ui.pxp.AccountChoicePrompt
import app.keyholm.ui.pxp.AccountChooser
import app.keyholm.util.logger
import com.upokecenter.cbor.CBORObject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant

private const val GA_RESPONSE_CREDENTIAL = 1
private const val GA_RESPONSE_AUTH_DATA = 2
private const val GA_RESPONSE_SIGNATURE = 3
private const val GA_RESPONSE_USER = 4
private const val GA_RESPONSE_USER_SELECTED = 6

internal class GetAssertionHandler(
    private val settings: Settings,
    private val passkeyRepo: PasskeyRepository,
    private val prompt: PromptRelay,
    private val chooseAccount: AccountChooser,
    private val dispatcher: CoroutineDispatcher,
) {
    private val log = logger()
    private val keys = AssertionKeyMaterial(passkeyRepo, dispatcher)

    suspend fun handle(body: ByteArray): Ctap2Response =
        when (val parsed = parseGetAssertion(body)) {
            is Ctap2Result.Rejected -> Ctap2Response(parsed.status)
            is Ctap2Result.Ok -> respond(parsed.value)
        }

    private suspend fun respond(request: GetAssertionRequest): Ctap2Response {
        val stored = readStore { passkeyRepo.activePasskeys.first() }
        val matching = stored?.let { matchingRecords(it, request.rpId, request.allowIds) }
        return when {
            stored == null -> {
                Ctap2Response(Ctap2Status.OTHER)
            }

            matching.isNullOrEmpty() -> {
                Ctap2Response(Ctap2Status.NO_CREDENTIALS)
            }

            matching.size == 1 -> {
                sign(matching.single(), request, userSelected = false)
            }

            else -> {
                chooseAccount(AccountChoicePrompt(matching.first().rp, matching, settings.preferRpName))
                    ?.let { sign(it, request, userSelected = true) }
                    ?: Ctap2Response(Ctap2Status.OPERATION_DENIED)
            }
        }
    }

    private fun matchingRecords(
        stored: List<PasskeyRecord>,
        rpId: RpId,
        allowIds: Set<CredentialId>?,
    ): List<PasskeyRecord> {
        val forRp = stored.filter { it.rp.id == rpId }
        if (allowIds == null) return forRp.filter { it.discoverable }.sortedByDescending { it.createdAt }
        return forRp.filter { it.credentialId in allowIds }
    }

    private suspend fun sign(
        record: PasskeyRecord,
        request: GetAssertionRequest,
        userSelected: Boolean,
    ): Ctap2Response {
        val signature =
            when (val r = keys.signatureFor(record)) {
                is KeyAccess.Failure -> return Ctap2Response(Ctap2Status.OTHER)
                is KeyAccess.Success -> r.value
            }
        val authenticators =
            when (val r = keys.authenticatorsFor(record)) {
                is KeyAccess.Failure -> return Ctap2Response(Ctap2Status.OTHER)
                is KeyAccess.Success -> r.value
            }
        val authData = AuthenticatorData.forAssertion(request.rpId, record.signCount + 1)

        val result =
            prompt.authenticate(
                title = "Sign in",
                cryptoObject = BiometricPrompt.CryptoObject(signature),
                allowedAuthenticators = authenticators,
                content =
                    promptContent(
                        description = "The device whose QR code you scanned wants to use a passkey:",
                        record = record,
                        preferRpName = settings.preferRpName,
                    ),
            )
        val crypto = (result as? PromptResult.Success)?.crypto
        if (crypto == null) {
            log.w { "the assertion was not authorised" }
            return Ctap2Response(Ctap2Status.OPERATION_DENIED)
        }
        val sig = crypto.signature ?: return Ctap2Response(Ctap2Status.OPERATION_DENIED)
        val derSignature =
            runCatching {
                withContext(dispatcher) {
                    sig.update(authData.bytes + request.clientDataHash.bytes)
                    sig.sign()
                }
            }.getOrElse {
                log.e(it) { "signing failed" }
                return Ctap2Response(Ctap2Status.OTHER)
            }

        val updated = record.copy(signCount = record.signCount + 1, lastUsedAt = Instant.now())
        if (!writeStore { passkeyRepo.update(updated) }) return Ctap2Response(Ctap2Status.OTHER)

        return Ctap2Response(assertionObject(updated, authData, derSignature, userSelected))
    }

    private fun assertionObject(
        record: PasskeyRecord,
        authData: AuthenticatorData,
        signature: ByteArray,
        userSelected: Boolean,
    ): CBORObject {
        val credential =
            CBORObject
                .NewMap()
                .Add("id", record.credentialId.bytes())
                .Add("type", PUBLIC_KEY_TYPE)
        val user =
            CBORObject
                .NewMap()
                .Add("id", record.user.handle.bytes())
                .Add("name", record.user.name)
                .Add("displayName", record.user.displayName)
        return CBORObject
            .NewMap()
            .Add(GA_RESPONSE_CREDENTIAL, credential)
            .Add(GA_RESPONSE_AUTH_DATA, authData.bytes)
            .Add(GA_RESPONSE_SIGNATURE, signature)
            .Add(GA_RESPONSE_USER, user)
            .apply { if (userSelected) Add(GA_RESPONSE_USER_SELECTED, true) }
    }
}
