package app.keyholm.ui.pxp

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

fun isPxpActivityEnabled(context: Context): Boolean =
    context.packageManager.getComponentEnabledSetting(ComponentName(context, Activity::class.java)) ==
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED

fun setPxpActivityEnabled(
    context: Context,
    enabled: Boolean,
) {
    val state =
        if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
    context.packageManager.setComponentEnabledSetting(
        ComponentName(context, Activity::class.java),
        state,
        PackageManager.DONT_KILL_APP,
    )
}
