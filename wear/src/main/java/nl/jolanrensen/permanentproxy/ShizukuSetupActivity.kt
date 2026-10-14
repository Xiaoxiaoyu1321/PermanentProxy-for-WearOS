package nl.jolanrensen.permanentproxy

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.activity.ComponentActivity
import nl.jolanrensen.permanentproxy.Constants.SHIZUKU_GUIDE
import nl.jolanrensen.permanentproxy.Constants.SHIZUKU_PACKAGE
import nl.jolanrensen.permanentproxy.Constants.SHIZUKU_WEBSITE
import nl.jolanrensen.permanentproxy.Constants.isShizukuInstalled
import nl.jolanrensen.permanentproxy.Constants.launchUrlOnPhone
import nl.jolanrensen.permanentproxy.Constants.openUrl
import nl.jolanrensen.permanentproxy.Constants.toast

/**
 * Explains how to get Shizuku running.
 *
 * Shizuku has to be started once per boot on non-rooted devices: either from the watch itself via
 * wireless debugging (Android 11+) or from a computer with `adb`. This screen is the replacement
 * for the old "How to enable ADB over Bluetooth?" screen.
 */
class ShizukuSetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shizuku_setup)

        findViewById<TextView>(R.id.open_shizuku).setOnClickListener {
            val launch = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            if (launch == null) {
                toast(getString(R.string.shizuku_not_installed))
            } else {
                startActivity(launch)
            }
        }

        findViewById<TextView>(R.id.download_shizuku).setOnClickListener {
            if (isShizukuInstalled()) {
                // Already installed, so the interesting part is the setup guide, not the download.
                launchUrlOnPhone(SHIZUKU_GUIDE)
            } else {
                launchUrlOnPhone(SHIZUKU_WEBSITE)
            }
        }

        findViewById<TextView>(R.id.developer_options).setOnClickListener {
            val intents = listOf(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
            val opened = intents.any { intent ->
                runCatching {
                    startActivity(intent)
                    true
                }.getOrDefault(false)
            }
            if (!opened) openUrl(SHIZUKU_GUIDE)
        }
    }
}
