package nl.jolanrensen.permanentproxy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.content.Intent.CATEGORY_BROWSABLE
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.wear.activity.ConfirmationActivity
import androidx.wear.activity.ConfirmationActivity.EXTRA_ANIMATION_TYPE
import androidx.wear.activity.ConfirmationActivity.OPEN_ON_PHONE_ANIMATION
import androidx.wear.remote.interactions.RemoteActivityHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URL

object Constants {
    const val LOGTAG = "PermanentProxy"

    /** Where Shizuku can be downloaded from. */
    const val SHIZUKU_WEBSITE = "https://shizuku.rikka.app/download/"
    const val SHIZUKU_GUIDE = "https://shizuku.rikka.app/guide/setup/"

    /** Package name of the Shizuku app, used to check whether it is installed. */
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    const val APP_PACKAGE = "nl.jolanrensen.permanentproxy"

    fun logD(message: String) = Log.d(LOGTAG, message)
    fun logI(message: String) = Log.i(LOGTAG, message)
    fun logE(message: String, e: Throwable? = null) = Log.e(LOGTAG, message, e)

    fun Context.toast(message: String, length: Int = Toast.LENGTH_SHORT) =
        Toast.makeText(this, message, length).show()

    fun Context.toastLong(message: String) = toast(message, Toast.LENGTH_LONG)

    /** True when the Shizuku app itself is installed on this device. */
    fun Context.isShizukuInstalled(): Boolean = runCatching {
        packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /**
     * Used to grant WRITE_SECURE_SETTINGS manually from a PC. Only a fallback: Shizuku does not
     * need this permission at all.
     */
    const val MANUAL_GRANT_COMMAND =
        "adb shell pm grant nl.jolanrensen.permanentproxy android.permission.WRITE_SECURE_SETTINGS"

    /** The command a PC has to run to undo the manual setup again. */
    const val MANUAL_REVOKE_COMMAND =
        "adb shell pm revoke nl.jolanrensen.permanentproxy android.permission.WRITE_SECURE_SETTINGS"

    /**
     * Reads the current external IP (and its country) so the user can verify that traffic really
     * goes through the proxy.
     */
    fun getCurrentIP(callback: (ip: String?, countryCode: String?) -> Unit) {
        val job = GlobalScope.launch(Dispatchers.IO) {
            try {
                val ip: String
                callback(
                    URL("https://api64.ipify.org")
                        .readText()
                        .also { ip = it },
                    JSONObject(URL("http://ip-api.com/json/$ip").readText()).getString("countryCode")
                )
            } catch (e: Exception) {
                logE("Could not determine the external IP", e)
                callback(null, null)
            }
        }

        // Don't leave the UI stuck on "loading…" forever if the network is unreachable.
        GlobalScope.launch {
            delay(20_000)
            if (job.isActive) {
                job.cancel()
                callback(null, null)
            }
        }
    }

    fun Context.launchUrlOnPhone(url: String) {
        // RemoteIntent was removed from the shared wearable library; the AndroidX replacement is
        // RemoteActivityHelper. It simply does nothing when no phone is connected.
        runCatching {
            RemoteActivityHelper(this).startRemoteActivity(
                Intent(ACTION_VIEW).addCategory(CATEGORY_BROWSABLE).setData(Uri.parse(url))
            )
        }.onFailure { logE("Could not open the URL on the phone", it) }

        runCatching {
            startActivity(
                Intent(this, ConfirmationActivity::class.java)
                    .putExtra(EXTRA_ANIMATION_TYPE, OPEN_ON_PHONE_ANIMATION)
            )
        }.onFailure { logE("Could not show the confirmation animation", it) }
    }

    fun Activity.openUrl(url: String) {
        runCatching {
            startActivity(
                Intent(ACTION_VIEW).addCategory(CATEGORY_BROWSABLE).setData(Uri.parse(url))
            )
        }.onFailure { logE("Could not open $url", it) }
    }
}
