package nl.jolanrensen.permanentproxy

import android.app.Activity
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.jolanrensen.permanentproxy.Constants.toastLong

/**
 * Asks the user for the Shizuku runtime permission.
 *
 * This replaces the old activity that ran `pm grant ... WRITE_SECURE_SETTINGS` over ADB-over-
 * Bluetooth. With Shizuku the flow is much simpler: the app requests a permission from the Shizuku
 * service and the user confirms it in a dialog shown by Shizuku itself.
 */
class RequestPermissionActivity : ComponentActivity() {

    private val permissionListener =
        ShizukuManager.PermissionResultListener { granted, requestCode ->
            if (requestCode != ShizukuManager.PERMISSION_REQUEST_CODE) return@PermissionResultListener
            runOnUiThread { onPermissionResult(granted) }
        }

    private lateinit var message: TextView
    private lateinit var tooLong: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_request_permission)

        message = findViewById(R.id.message)
        tooLong = findViewById(R.id.too_long)
        val loadingBar = findViewById<ProgressBar>(R.id.loading_bar)

        findViewById<TextView>(R.id.cancel).setOnClickListener { finish() }

        ShizukuManager.addPermissionResultListener(permissionListener)

        when {
            ShizukuManager.isPermissionGranted -> {
                setResult(Activity.RESULT_OK)
                finish()
            }

            !ShizukuManager.isBinderAvailable -> {
                message.text = getString(R.string.shizuku_not_running)
                tooLong.isVisible = true
                tooLong.setText(R.string.setup_shizuku)
                tooLong.setOnClickListener {
                    startActivity(android.content.Intent(this, ShizukuSetupActivity::class.java))
                    finish()
                }
            }

            ShizukuManager.shouldShowRequestPermissionRationale -> {
                // The user picked "deny and don't ask again"; only Shizuku's own settings can fix it.
                message.text = getString(R.string.shizuku_permission_denied_permanently)
                tooLong.isVisible = true
                tooLong.setText(R.string.close)
                tooLong.setOnClickListener { finish() }
            }

            !ShizukuManager.requestPermission() -> {
                message.text = getString(R.string.shizuku_request_failed)
                tooLong.isVisible = true
                tooLong.setText(R.string.close)
                tooLong.setOnClickListener { finish() }
            }
        }

        // Purely cosmetic: Shizuku shows its dialog straight away, this just avoids a dead looking
        // screen while the user makes up their mind.
        lifecycleScope.launch {
            var progress = 0
            while (progress < 100) {
                loadingBar.progress = progress
                delay(300)
                progress += 2
            }
        }
    }

    private fun onPermissionResult(granted: Boolean) {
        if (granted) {
            toastLong(getString(R.string.shizuku_permission_granted))
            setResult(Activity.RESULT_OK)
        } else {
            toastLong(getString(R.string.shizuku_permission_denied))
            setResult(Activity.RESULT_CANCELED)
        }
        finish()
    }

    override fun onDestroy() {
        ShizukuManager.removePermissionResultListener(permissionListener)
        super.onDestroy()
    }
}
