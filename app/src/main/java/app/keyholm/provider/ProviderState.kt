package app.keyholm.provider

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.credentials.CredentialManager

fun isCredentialProviderEnabled(context: Context): Boolean {
    val manager = context.getSystemService(CredentialManager::class.java) ?: return false
    val component = ComponentName(context, Service::class.java)
    return manager.isEnabledCredentialProviderService(component)
}

fun setCredentialProviderComponentEnabled(
    context: Context,
    available: Boolean,
) {
    val component = ComponentName(context, Service::class.java)
    val state =
        if (available) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
    context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
}
