package app.keyholm

import android.content.pm.ApplicationInfo
import co.touchlab.kermit.Logger

class Application : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) Logger.setLogWriters(emptyList())
    }
}
