package nl.jolanrensen.permanentproxy

import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Fallback for devices where Shizuku cannot be used (and for people who prefer it): grant
 * `WRITE_SECURE_SETTINGS` once from a computer with adb. After that the app controls the proxy
 * directly through [ProxyManager] and never needs Shizuku.
 */
class AdbFallbackActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_adb_fallback)
    }
}
