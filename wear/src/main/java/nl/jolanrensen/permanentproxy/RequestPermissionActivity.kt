package nl.jolanrensen.permanentproxy

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.isVisible
import nl.jolanrensen.permanentproxy.Constants.logE
import nl.jolanrensen.permanentproxy.Constants.toastLong
import kotlin.concurrent.thread

class RequestPermissionActivity : ComponentActivity() {

    @Volatile
    var currentADBProcess: SendSingleCommand? = null

    @Volatile
    var stop = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_request_permission)

        val loadingBar = findViewById<ProgressBar>(R.id.loading_bar)
        val tooLong = findViewById<TextView>(R.id.too_long)

        findViewById<TextView>(R.id.cancel).setOnClickListener {
            finish()
        }

        thread(start = true) {
            val logs = arrayListOf<String>()
            try {
                currentADBProcess = SendSingleCommand(
                    logs = logs,
                    context = this,
                    ip = "localhost",
                    port = Constants.PORT,
                    command = "pm grant \\\nnl.jolanrensen.permanentproxy \\\nandroid.permission.WRITE_SECURE_SETTINGS",
                    timeout = 2500,
                    ctrlC = false
                ) {
                    currentADBProcess = null
                    Constants.logD(it.toString())
                    runOnUiThread {
                        if (checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                            == PackageManager.PERMISSION_GRANTED
                        ) {
                            toastLong(getString(R.string.permission_granted))
                            setResult(Activity.RESULT_OK)
                        } else {
                            toastLong(getString(R.string.something_wrong))
                        }
                        finish()
                    }
                }
            } catch (e: Exception) {
                currentADBProcess = null
                logE("$logs", e)
                runOnUiThread {
                    toastLong(getString(R.string.something_wrong))
                    finish()
                }
            }
        }

        // useless loading bar to show google the app is doing something
        thread(start = true) {
            var s = 0
            while (s <= 35 && !stop) {
                runOnUiThread { loadingBar.progress = ((s / 35f) * 100f).toInt() }
                s++
                Thread.sleep(1000)
            }
            runOnUiThread {
                tooLong.isVisible = true
                tooLong.setOnClickListener {
                    startActivity(Intent(this@RequestPermissionActivity, OldWatchActivity::class.java))
                    finish()
                }
            }

        }
    }

    override fun onStop() {
        currentADBProcess?.cancel()
        stop = true
        logE("requesting permission canceled")
        super.onStop()
    }
}
