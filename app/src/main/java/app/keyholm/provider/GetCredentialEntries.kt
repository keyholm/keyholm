package app.keyholm.provider

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.CredentialEntry
import app.keyholm.keystore.SecureKeyManager
import app.keyholm.settings.Settings
import app.keyholm.settings.SettingsRepository
import app.keyholm.store.DeniedNativeAppRepository
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.PasskeyRepository
import app.keyholm.store.StoredRecordException
import app.keyholm.util.logger
import app.keyholm.webauthn.NativeAppTrust
import app.keyholm.webauthn.RpId
import app.keyholm.webauthn.TrustDecision
import app.keyholm.webauthn.parseRequestOptionsOrLog
import app.keyholm.webauthn.resolveTrustDecision
import kotlinx.coroutines.flow.first
import java.io.IOException

private const val UNLOCK_REQUEST_CODE = 1

internal class GetCredentialEntries(
    private val context: Context,
) {
    private val log = logger()
    private val keyManager = SecureKeyManager()
    private val passkeyRepo = PasskeyRepository(context)
    private val settingsRepo = SettingsRepository(context)
    private val deniedAppsRepo = DeniedNativeAppRepository(context)
    private val entryBuilder =
        GetCredentialEntryBuilder(context, keyManager, passkeyRepo) { credentialId, preferRpName, trust ->
            credentialPendingIntent(credentialId.b64.hashCode(), credentialUri(credentialId)) {
                putPreferRpName(preferRpName)
                putNativeAppTrust(trust)
            }
        }

    suspend fun of(request: BeginGetCredentialRequest): List<CredentialEntry>? {
        PendingSignatures.clear()
        val records = passkeyRepo.passkeys.first()
        val settings = settingsRepo.settings.first()
        val perOption =
            request.beginGetCredentialOptions
                .filterIsInstance<BeginGetPublicKeyCredentialOption>()
                .map { entriesFor(it, request.callingAppInfo, records, settings) }
        return if (perOption.any { it == null }) null else perOption.filterNotNull().flatten()
    }

    private suspend fun entriesFor(
        option: BeginGetPublicKeyCredentialOption,
        callingAppInfo: CallingAppInfo?,
        records: List<PasskeyRecord>,
        settings: Settings,
    ): List<CredentialEntry>? {
        val requestOptions = parseRequestOptionsOrLog(option.requestJson, log) ?: return null
        val matching = entryBuilder.matchingRecords(records, requestOptions)
        // We don't want to prompt the user if we _know_ we'll deny the request. But we want to
        // persist that this app was denied and tell the user about it.
        val allowSingleTap =
            matching.isNotEmpty() &&
                singleTapAllowed(callingAppInfo, RpId(requestOptions.rpId), settings.nativeAppTrust)
        return entryBuilder.buildEntries(
            option,
            matching,
            allowSingleTap,
            settings.preferRpName,
            settings.nativeAppTrust,
        )
    }

    private suspend fun singleTapAllowed(
        callingAppInfo: CallingAppInfo?,
        rpId: RpId,
        trust: NativeAppTrust,
    ): Boolean {
        if (callingAppInfo == null) return false
        val accepted =
            try {
                deniedAppsRepo.accepted.first()
            } catch (e: IOException) {
                log.e(e) { "couldn't read the denied apps store" }
                return false
            } catch (e: StoredRecordException) {
                log.e(e) { "couldn't read the denied apps store" }
                return false
            }
        return context.resolveTrustDecision(callingAppInfo, rpId, trust, accepted) is TrustDecision.Allowed
    }

    private fun credentialPendingIntent(
        requestCode: Int,
        data: android.net.Uri,
        extras: Intent.() -> Unit,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, app.keyholm.ui.getpasskey.Activity::class.java).setData(data).apply(extras),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

internal fun unlockPendingIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(
        context,
        UNLOCK_REQUEST_CODE,
        Intent(context, app.keyholm.ui.getpasskey.UnlockActivity::class.java),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
