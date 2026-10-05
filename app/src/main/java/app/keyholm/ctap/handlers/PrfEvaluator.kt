package app.keyholm.ctap.handlers

import app.keyholm.authenticator.AssertionKeyMaterial
import app.keyholm.authenticator.KeyAccess
import app.keyholm.ctap.PrfResults
import app.keyholm.ctap.PrfSalts
import app.keyholm.ctap.requests.EXTENSION_PRF
import app.keyholm.ctap.requests.PRF_FIRST
import app.keyholm.ctap.requests.PRF_SECOND
import app.keyholm.store.PasskeyRecord
import app.keyholm.ui.common.PrfPrompt
import app.keyholm.ui.common.PromptRelay
import app.keyholm.ui.common.prfPromptContent
import com.upokecenter.cbor.CBORObject
import kotlinx.coroutines.CoroutineDispatcher

private const val PRF_ENABLED = "enabled"
private const val PRF_RESULTS = "results"

internal fun prfOutput(
    enabled: Boolean,
    results: PrfResults?,
): CBORObject {
    val prf = CBORObject.NewMap()
    if (enabled) prf.Add(PRF_ENABLED, true)
    if (results != null) {
        val encoded = CBORObject.NewMap().Add(PRF_FIRST, results.first.bytes)
        results.second?.let { encoded.Add(PRF_SECOND, it.bytes) }
        prf.Add(PRF_RESULTS, encoded)
    }
    return CBORObject.NewMap().Add(EXTENSION_PRF, prf)
}

internal class PrfEvaluator(
    prompt: PromptRelay,
    private val keys: AssertionKeyMaterial,
    private val preferRpName: Boolean,
    dispatcher: CoroutineDispatcher,
) {
    private val prfPrompt = PrfPrompt(prompt, dispatcher)

    suspend fun evaluate(
        record: PasskeyRecord,
        salts: PrfSalts,
    ): PrfResults? =
        when (
            val r =
                prfPrompt.evaluate(
                    macFor = { keys.macFor(record) },
                    authenticatorsFor = { keys.hmacAuthenticatorsFor(record) },
                    salts = salts,
                    content =
                        prfPromptContent(
                            requester = "The device whose QR code you scanned",
                            declineAction = "continue anyway",
                            rp = record.rp,
                            user = record.user,
                            preferRpName = preferRpName,
                        ),
                )
        ) {
            is KeyAccess.Failure -> null
            is KeyAccess.Success -> r.value
        }
}
