package app.keyholm.ctap

import android.content.Context
import app.keyholm.ctap.handlers.GetAssertionHandler
import app.keyholm.ctap.handlers.MakeCredentialHandler
import app.keyholm.ctap.handlers.authenticatorInfo
import app.keyholm.pxp.ctap.PxpAuthenticator
import app.keyholm.settings.Settings
import app.keyholm.store.PasskeyRepository
import app.keyholm.ui.common.PromptRelay
import kotlinx.coroutines.CoroutineDispatcher

class CtapAuthenticator(
    private val context: Context,
    private val settings: Settings,
    passkeyRepo: PasskeyRepository,
    prompt: PromptRelay,
    dispatcher: CoroutineDispatcher,
) : PxpAuthenticator {
    private val makeCredential = MakeCredentialHandler(context, settings, passkeyRepo, prompt, dispatcher)
    private val getAssertion = GetAssertionHandler(settings, passkeyRepo, prompt, dispatcher)

    override suspend fun getInfo(): ByteArray = authenticatorInfo(context, settings)

    override suspend fun handle(request: ByteArray): ByteArray {
        val body = request.copyOfRange(1, request.size)
        val response =
            when (request.first().toInt()) {
                CTAP_GET_INFO -> Ctap2Response(Ctap2Status.OK, getInfo())
                CTAP_MAKE_CREDENTIAL -> makeCredential.handle(body)
                CTAP_GET_ASSERTION -> getAssertion.handle(body)
                CTAP_GET_NEXT_ASSERTION -> Ctap2Response(Ctap2Status.NOT_ALLOWED)
                else -> Ctap2Response(Ctap2Status.INVALID_COMMAND)
            }
        return response.encode()
    }
}
