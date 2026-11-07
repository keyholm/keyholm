package app.keyholm.ctap

import android.content.Context
import app.keyholm.authenticator.IdentityPreference
import app.keyholm.ctap.handlers.GetAssertionHandler
import app.keyholm.ctap.handlers.MakeCredentialHandler
import app.keyholm.ctap.handlers.authenticatorInfo
import app.keyholm.pxp.ctap.PxpAuthenticator
import app.keyholm.settings.Settings
import app.keyholm.store.PasskeyRepository
import app.keyholm.ui.pxp.CtapUi
import kotlinx.coroutines.CoroutineDispatcher

internal class CtapAuthenticator(
    private val context: Context,
    private val settings: Settings,
    passkeyRepo: PasskeyRepository,
    ui: CtapUi,
    dispatcher: CoroutineDispatcher,
) : PxpAuthenticator {
    private val makeCredential = MakeCredentialHandler(context, settings, passkeyRepo, ui, dispatcher)
    private val getAssertion = GetAssertionHandler(settings, passkeyRepo, ui, dispatcher)

    // Clients time getInfo out quickly, so it can't wait for the user.
    override suspend fun getInfo(): ByteArray =
        authenticatorInfo(context, settings, identifyAsKeyholm = settings.identityPreference == IdentityPreference.ENABLED)

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
