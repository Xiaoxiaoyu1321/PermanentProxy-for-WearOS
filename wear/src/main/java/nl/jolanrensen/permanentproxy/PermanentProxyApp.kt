package nl.jolanrensen.permanentproxy

import android.app.Application
import nl.jolanrensen.permanentproxy.Constants.logD

/**
 * Registers the Shizuku listeners as early as possible so that the binder state is already known
 * when the first activity starts.
 */
class PermanentProxyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        logD("PermanentProxyApp.onCreate, initialising Shizuku")
        ShizukuManager.init()
    }
}
