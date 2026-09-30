package app.keyholm.ui.main

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import androidx.credentials.provider.CallingAppInfo
import app.keyholm.store.PasskeyRecord
import app.keyholm.webauthn.PrivilegedAllowlist
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineDispatcher

internal suspend fun openPasskeyCallerIntent(
    context: Context,
    record: PasskeyRecord,
    dispatcher: CoroutineDispatcher,
    log: Logger,
): Intent {
    val origin = "https://${record.rp.id.value}"
    val web = Intent(Intent.ACTION_VIEW, origin.toUri())
    val pkg = record.callingPackage.value
    val signingInfo =
        try {
            context.packageManager
                .getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
                .signingInfo
        } catch (e: PackageManager.NameNotFoundException) {
            log.i(e) { "creating app $pkg not installed" }
            null
        } ?: return web
    val allowlist = PrivilegedAllowlist.load(context, dispatcher)

    // We're only using this essentially to test whether the calling app is a
    // browser or not so we can call the right app
    @SuppressLint("VisibleForTests")
    val callingAppInfo = CallingAppInfo(pkg, signingInfo, origin)

    val privileged =
        try {
            callingAppInfo.getOrigin(allowlist) != null
        } catch (e: IllegalStateException) {
            log.d(e) { "$pkg not on privileged allowlist" }
            false
        }
    return if (privileged) web else context.packageManager.getLaunchIntentForPackage(pkg) ?: web
}
