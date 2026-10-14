package nl.jolanrensen.permanentproxy

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.jolanrensen.permanentproxy.Constants.getCurrentIP
import nl.jolanrensen.permanentproxy.Constants.logD
import nl.jolanrensen.permanentproxy.Constants.toastLong

/**
 * Permanent Proxy, Shizuku edition.
 *
 * The old version of this app obtained the `WRITE_SECURE_SETTINGS` privilege by talking the ADB
 * protocol over Bluetooth to `localhost:7272`, which only works on devices whose developer options
 * still expose "Debug over Bluetooth". This version gets the very same privilege from
 * [ShizukuManager] instead, so it also works on Wear OS 3/4/5 and on any other Android 6.0+ device.
 *
 * There is a second, optional path: if the user granted `WRITE_SECURE_SETTINGS` manually from a PC,
 * the proxy is written directly through [ProxyManager] and Shizuku is not needed at all.
 */
class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var extIp: TextView
    private lateinit var privilegeStatus: TextView
    private lateinit var showMeHow: TextView
    private lateinit var requestPermission: TextView
    private lateinit var setProxy: TextView
    private lateinit var disableProxy: TextView
    private lateinit var rebootCheckbox: CheckBox
    private lateinit var proxyAddress: TextView
    private lateinit var proxyPort: TextView
    private lateinit var addressInput: EditText
    private lateinit var portInput: EditText
    private lateinit var mainMenu: ViewGroup

    /** Result of the Shizuku permission screen; whatever happened, refresh the status. */
    private val shizukuPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        extIp = findViewById(R.id.ext_ip)
        privilegeStatus = findViewById(R.id.privilege_status)
        showMeHow = findViewById(R.id.show_me_how)
        requestPermission = findViewById(R.id.request_permission)
        setProxy = findViewById(R.id.set_proxy)
        disableProxy = findViewById(R.id.disable_proxy)
        rebootCheckbox = findViewById(R.id.reboot_checkbox)
        proxyAddress = findViewById(R.id.proxy_address)
        proxyPort = findViewById(R.id.proxy_port)
        addressInput = findViewById(R.id.address_input)
        portInput = findViewById(R.id.port_input)
        mainMenu = findViewById(R.id.main_menu)

        showMeHow.setOnClickListener {
            startActivity(Intent(this, ShizukuSetupActivity::class.java))
        }

        requestPermission.setOnClickListener {
            shizukuPermissionLauncher.launch(
                Intent(this, RequestPermissionActivity::class.java)
            )
        }

        findViewById<TextView>(R.id.donate).setOnClickListener {
            startActivity(Intent(this, DonateActivity::class.java))
        }

        findViewById<TextView>(R.id.old_watch).setOnClickListener {
            startActivity(Intent(this, AdbFallbackActivity::class.java))
        }

        findViewById<ScrollView>(R.id.scroll_view).requestFocus()

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ------------------------------------------------------------------ state

    /** True when the proxy can actually be changed right now. */
    private fun hasPrivilegedAccess(): Boolean = ProxyManager.canControlProxy(this)

    private fun refresh() {
        status.text = ProxyManager.currentProxy(this) ?: getString(R.string.proxy_not_enabled)
        refreshPrivilegeStatus()
        refreshExternalIp()

        if (hasPrivilegedAccess()) {
            showProxyControls()
        } else {
            showSetupControls()
        }
    }

    private fun refreshPrivilegeStatus() {
        privilegeStatus.text = when {
            ProxyManager.hasDirectWriteAccess(this) ->
                getString(R.string.privilege_direct)
            !ShizukuManager.isSupported ->
                getString(R.string.shizuku_not_installed)
            !ShizukuManager.isBinderAvailable ->
                getString(R.string.shizuku_not_running)
            !ShizukuManager.isPermissionGranted ->
                getString(R.string.shizuku_permission_missing)
            else ->
                getString(R.string.privilege_shizuku, ShizukuManager.privilegeDescription)
        }
    }

    private fun refreshExternalIp() {
        getCurrentIP { ip, country ->
            runOnUiThread {
                extIp.text = if (ip == null) getString(R.string.NA) else "$ip, $country"
            }
        }
    }

    // ------------------------------------------------------------------ ui states

    private fun showSetupControls() {
        showMeHow.isVisible = true
        // Requesting the Shizuku permission only makes sense when the Shizuku service is up.
        requestPermission.isVisible =
            ShizukuManager.isBinderAvailable && !ShizukuManager.isPermissionGranted
        setProxy.isVisible = false
        disableProxy.isVisible = false
        rebootCheckbox.isVisible = false
        proxyAddress.isVisible = false
        proxyPort.isVisible = false
        addressInput.isVisible = false
        portInput.isVisible = false
    }

    private fun showProxyControls() {
        showMeHow.isVisible = false
        requestPermission.isVisible = false
        setAllEnabled(mainMenu)

        val enabled = ProxyManager.currentProxy(this) != null

        setProxy.apply {
            isVisible = true
            setOnClickListener { onSetProxyClicked() }
        }

        disableProxy.apply {
            isVisible = enabled
            setOnClickListener { onDisableProxyClicked() }
        }

        rebootCheckbox.isVisible = enabled

        proxyAddress.isVisible = true
        proxyPort.isVisible = true

        val (currentAddress, currentPort) =
            ProxyManager.currentProxy(this)?.split(":") ?: listOf(null, null)

        addressInput.apply {
            isVisible = true
            if (text.isNullOrEmpty()) setText(currentAddress ?: "")
            doOnTextChanged { _, _, _, _ -> updateSetProxyIsEnabled() }
        }

        portInput.apply {
            isVisible = true
            if (text.isNullOrEmpty()) setText(currentPort ?: "")
            doOnTextChanged { _, _, _, _ -> updateSetProxyIsEnabled() }
        }

        updateSetProxyIsEnabled()
    }

    private fun updateSetProxyIsEnabled() {
        setProxy.isEnabled = addressInput.text.isNotEmpty() && portInput.text.isNotEmpty()
    }

    private fun setAllEnabled(parent: ViewGroup, vararg except: Int) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.id in except) continue
            child.isEnabled = true
            if (child is ViewGroup) setAllEnabled(child, *except)
        }
    }

    // ------------------------------------------------------------------ actions

    private fun onSetProxyClicked() {
        val host = addressInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull()

        if (host.isEmpty() || port == null) {
            toastLong(getString(R.string.valid_port))
            return
        }

        logD("setting proxy to $host:$port")
        toastLong(getString(R.string.setting_proxy))

        lifecycleScope.launch {
            val result = ProxyManager.enableProxy(this@MainActivity, host, port)
            if (result.success) {
                // Give the connectivity service a moment, then nudge Google Pay into re-checking.
                delay(1_000)
                withContext(Dispatchers.Main) {
                    runCatching { sendBroadcast(Intent("android.server.checkin.CHECKIN")) }
                    toastLong(getString(R.string.other_apps))
                }
            } else {
                toastLong(result.message)
            }
            refresh()
        }
    }

    private fun onDisableProxyClicked() {
        val reboot = rebootCheckbox.isChecked
        logD("turning off proxy, reboot=$reboot")
        toastLong(getString(R.string.turning_off))

        lifecycleScope.launch {
            val result = ProxyManager.disableProxy(this@MainActivity, reboot = reboot)
            toastLong(result.message)
            refresh()
        }
    }
}
