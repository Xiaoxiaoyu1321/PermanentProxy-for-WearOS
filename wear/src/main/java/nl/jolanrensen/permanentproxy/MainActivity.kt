package nl.jolanrensen.permanentproxy

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.jolanrensen.permanentproxy.Constants.currentProxy
import nl.jolanrensen.permanentproxy.Constants.getCurrentIP
import nl.jolanrensen.permanentproxy.Constants.logE
import nl.jolanrensen.permanentproxy.Constants.startProxy
import nl.jolanrensen.permanentproxy.Constants.stopProxy
import nl.jolanrensen.permanentproxy.Constants.toastLong

class MainActivity : ComponentActivity() {


    private val PERMISSION = 23

    private lateinit var status: TextView
    private lateinit var extIp: TextView
    private lateinit var showMeHow: TextView
    private lateinit var requestPermission: TextView
    private lateinit var setProxy: TextView
    private lateinit var disableProxy: TextView
    private lateinit var proxyAddress: TextView
    private lateinit var proxyPort: TextView
    private lateinit var addressInput: EditText
    private lateinit var portInput: EditText
    private lateinit var mainMenu: ViewGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        extIp = findViewById(R.id.ext_ip)
        showMeHow = findViewById(R.id.show_me_how)
        requestPermission = findViewById(R.id.request_permission)
        setProxy = findViewById(R.id.set_proxy)
        disableProxy = findViewById(R.id.disable_proxy)
        proxyAddress = findViewById(R.id.proxy_address)
        proxyPort = findViewById(R.id.proxy_port)
        addressInput = findViewById(R.id.address_input)
        portInput = findViewById(R.id.port_input)
        mainMenu = findViewById(R.id.main_menu)

        setupStatus(true)

        /*
            Turn on proxy with:
            settings put global http_proxy address:port
            for example 50.116.3.101:3128

            Somehow a proxy survives a reboot lol


            Turn off proxy with:
            adb shell settings delete global http_proxy
            adb shell settings delete global global_http_proxy_host
            adb shell settings delete global global_http_proxy_port

            settings delete global http_proxy; settings delete global global_http_proxy_host; settings delete global global_http_proxy_port; settings delete global global_http_proxy_exclusion_list; settings delete global global_proxy_pac_url

            adb over wifi port: 5555
            adb over bluetooth port: 7272

         */

        // first check if app has permission to write Secure Settings, else ask to turn on adb over bluetooth
        if (checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
            == PackageManager.PERMISSION_GRANTED
        ) {
            continueSetup()
        }

        showMeHow.setOnClickListener {
            startActivity(
                Intent(this, EnableADBBluetoothActivity::class.java)
            )
        }

        requestPermission.setOnClickListener {
            startActivityForResult(
                Intent(this, RequestPermissionActivity::class.java),
                PERMISSION
            )
            onPause()
        }

        findViewById<TextView>(R.id.donate).setOnClickListener {
            startActivity(
                Intent(this, DonateActivity::class.java)
            )
        }

        findViewById<TextView>(R.id.old_watch).setOnClickListener {
            startActivity(
                Intent(this, OldWatchActivity::class.java)
            )
        }

        findViewById<ScrollView>(R.id.scroll_view).requestFocus()
    }

    private fun setupStatus(wait: Boolean = false): Boolean {
        if (wait) {
            GlobalScope.launch {
                delay(1000)
                runOnUiThread {
                    val proxy = currentProxy
                    status.text = proxy ?: getString(R.string.not_enabled)
                }
                getCurrentIP { ip, country ->
                    logE("current IP is $ip, $country")
                    runOnUiThread {
                        extIp.text =
                            if (ip == null) getString(R.string.NA) else "$ip, $country"
                    }
                }

            }
            return false
        }
        val proxy = currentProxy
        status.text = proxy ?: getString(R.string.not_enabled)
        getCurrentIP { ip, country ->
            logE("current IP is $ip, $country")
            runOnUiThread {
                extIp.text = if (ip == null) getString(R.string.NA) else "$ip, $country"
            }
        }
        return proxy != null
    }

    private fun updateSetProxyIsEnabled() {
        setProxy.isEnabled = addressInput.text.isNotEmpty() && portInput.text.isNotEmpty()
    }

    private fun continueSetup() {
        setAllEnabled(mainMenu)
        showMeHow.isVisible = false
        requestPermission.isVisible = false

        val enabled: Boolean = setupStatus()

        setProxy.apply {
            isVisible = true

            setOnClickListener {
                val address = addressInput.text.toString()
                val port = portInput.text.toString().toInt()

                logE("setting proxy")
                startProxy(
                    address = address,
                    port = port,
                    updateGooglePay = true
                )
                toastLong(getString(R.string.other_apps))
                disableProxy.isVisible = true
                setupStatus(wait = true)
            }
        }

        disableProxy.apply {
            isVisible = enabled
            setOnClickListener {
                logE("turning off proxy")
                toastLong(getString(R.string.turning_off))
                stopProxy(
                    onFailure = {
                        runOnUiThread {
                            toastLong(getString(R.string.something_wrong))
                        }
                    }
                )
            }
        }

        proxyAddress.isVisible = true
        proxyPort.isVisible = true

        val (currentAddress, currentPort) = currentProxy?.split(":") ?: listOf(null, null)

        addressInput.apply {
            isVisible = true
            setText(currentAddress ?: "")
            doOnTextChanged { _, _, _, _ -> updateSetProxyIsEnabled() }
        }

        portInput.apply {
            isVisible = true
            setText(currentPort ?: "")
            doOnTextChanged { _, _, _, _ -> updateSetProxyIsEnabled() }
        }

        updateSetProxyIsEnabled()
    }

    private fun setAllEnabled(parent: ViewGroup, vararg except: Int) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.id in except) continue
            child.isEnabled = true

            if (child is ViewGroup) setAllEnabled(child, *except)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        onResume()
        when {
            requestCode == PERMISSION && resultCode == RESULT_OK -> {
                continueSetup()
            }
        }
    }
}
