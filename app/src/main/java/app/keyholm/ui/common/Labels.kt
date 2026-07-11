package app.keyholm.ui.common

import android.content.Context
import android.content.pm.PackageManager
import app.keyholm.webauthn.PackageName
import app.keyholm.webauthn.RpId
import java.text.BreakIterator

// Something I ran into while testing, some identity proxy defaults we should just ignore
private val UNINFORMATIVE_RP_NAMES = setOf("keycloak")

private const val MAX_DISPLAY_LENGTH = 64

// WebAuthn 6.4.1
private fun String.truncated(): String {
    if (length <= MAX_DISPLAY_LENGTH) return this
    val breaks = BreakIterator.getCharacterInstance()
    breaks.setText(this)
    val cut = if (breaks.isBoundary(MAX_DISPLAY_LENGTH)) MAX_DISPLAY_LENGTH else breaks.preceding(MAX_DISPLAY_LENGTH)
    return substring(0, cut) + "\u2026"
}

internal fun rpDisplayName(
    rpId: RpId,
    rpName: String,
): String? =
    rpName
        .takeUnless {
            it.isBlank() || it == rpId.value ||
                it in UNINFORMATIVE_RP_NAMES
        }?.truncated()

internal fun rpLabel(
    rpId: RpId,
    rpName: String,
    preferRpName: Boolean,
): String {
    val name = rpDisplayName(rpId, rpName) ?: return rpId.value
    return if (preferRpName) "$name (${rpId.value})" else "${rpId.value} ($name)"
}

internal fun userLabel(
    userName: String,
    displayName: String,
): String =
    if (displayName.isBlank() || displayName == userName) {
        userName.truncated()
    } else {
        "${userName.truncated()} (${displayName.truncated()})"
    }

internal fun Context.appLabel(packageName: PackageName): String =
    runCatching {
        packageManager
            .getApplicationInfo(packageName.value, PackageManager.ApplicationInfoFlags.of(0L))
            .loadLabel(packageManager)
            .toString()
    }.getOrDefault(packageName.value)
