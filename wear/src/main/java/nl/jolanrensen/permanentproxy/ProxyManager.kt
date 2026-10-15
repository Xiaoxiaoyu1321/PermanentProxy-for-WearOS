package nl.jolanrensen.permanentproxy

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import nl.jolanrensen.permanentproxy.Constants.logE

/**
 * Everything that reads or writes the system-wide HTTP proxy.
 *
 * Two execution paths, in this order of preference:
 *
 *  1. **Direct** — if the app already holds `WRITE_SECURE_SETTINGS` (e.g. the user granted it once
 *     from a PC with `adb shell pm grant ...`), write `Settings.Global` directly. Fastest, and it
 *     keeps working on devices where Shizuku cannot run.
 *  2. **Shizuku** — otherwise run the equivalent `settings put/delete global ...` commands with
 *     shell/root identity through [ShizukuManager]. This is the path that replaces the old
 *     "ADB over Bluetooth" implementation and is what makes the app work on far more devices.
 *
 * ## How the system actually picks these settings up
 *
 * `ConnectivityService.registerSettingsCallbacks()` registers a `ContentObserver` on
 * `Settings.Global.HTTP_PROXY` **only**; `global_http_proxy_host` / `_port` / `_exclusion_list` /
 * `global_proxy_pac_url` are not observed. On every change of `http_proxy`,
 * `ProxyTracker.loadGlobalProxy()` runs:
 *
 * ```java
 * public boolean loadDeprecatedGlobalHttpProxy() {
 *     final String proxy = Settings.Global.getString(cr, HTTP_PROXY);
 *     if (!TextUtils.isEmpty(proxy)) {
 *         String data[] = proxy.split(":");
 *         ...
 *         setGlobalProxy(new ProxyInfo(proxyHost, proxyPort, ""));
 *         return true;
 *     }
 *     return false;
 * }
 * ```
 *
 * `setGlobalProxy()` then normalises the values back into the other four keys, updates the
 * in-memory `mGlobalProxy` and sends the sticky `Proxy.PROXY_CHANGE_ACTION` broadcast that makes
 * apps (HttpURLConnection, OkHttp, WebView, ...) actually use the proxy.
 *
 * This is why [enableProxy] writes `http_proxy` *and* the host/port keys: `http_proxy` is what the
 * framework listens to, the other two are what it writes back and what we read the state from.
 */
object ProxyManager {

    /** Same keys the `settings` command line tool writes. */
    private const val KEY_HTTP_PROXY = "http_proxy"
    private const val KEY_HOST = "global_http_proxy_host"
    private const val KEY_PORT = "global_http_proxy_port"
    private const val KEY_EXCLUSION_LIST = "global_http_proxy_exclusion_list"
    private const val KEY_PAC_URL = "global_proxy_pac_url"

    /** Removing every one of them leaves no stale proxy state behind. */
    private val ALL_PROXY_KEYS = listOf(
        KEY_HTTP_PROXY, KEY_HOST, KEY_PORT, KEY_EXCLUSION_LIST, KEY_PAC_URL
    )

    /**
     * Value written to `http_proxy` to switch the global proxy **off**.
     *
     * Deleting the settings is *not* enough. `ProxyTracker.loadGlobalProxy()` only ever *sets*
     * `mGlobalProxy`; it never clears it when the settings are empty. The single place where the
     * in-memory global proxy becomes null is the else-branch of `ProxyTracker.setGlobalProxy()`:
     *
     * ```java
     * if (proxyInfo != null && (!TextUtils.isEmpty(proxyInfo.getHost())
     *         || !Uri.EMPTY.equals(proxyInfo.getPacFileUrl()))) {
     *     mGlobalProxy = new ProxyInfo(proxyInfo);
     * } else {
     *     mGlobalProxy = null;          // <- the only place this happens
     * }
     * ```
     *
     * …and `loadDeprecatedGlobalHttpProxy()` reaches that branch only when `http_proxy` holds a
     * **non-empty** string whose host part is empty. `":0"` is exactly that:
     *
     *  - non-empty, so `TextUtils.isEmpty(proxy)` does not short-circuit,
     *  - `":0".split(":")` yields `["", "0"]`, so the `data.length == 0` early return is not hit,
     *  - `proxyHost` is `""`, so `setGlobalProxy()` takes the else-branch and clears `mGlobalProxy`,
     *  - `sendProxyBroadcast()` then tells every app to go back to a direct connection.
     *
     * Do **not** use `":"`: `":".split(":")` returns a zero-length array, which makes
     * `loadDeprecatedGlobalHttpProxy()` bail out before it ever calls `setGlobalProxy()`.
     *
     * Without this, "Disable proxy" would only delete the persisted settings while the proxy kept
     * being used until the next reboot — which is why the 4.x implementation simply rebooted the
     * watch. Setting this value makes disabling take effect immediately.
     */
    private const val CLEAR_PROXY_VALUE = ":0"

    /**
     * Time given to system_server to process one settings change before the next one is written.
     * `ContentObserver` notifications are coalesced, so writing `":0"` and deleting the keys back
     * to back could be delivered as a single notification whose state is already "deleted" — and
     * that notification would not clear the in-memory proxy.
     */
    private const val SETTLE_MS = 700L

    const val DEFAULT_ADB_PORT = 5555

    /** Result of an operation, ready to be shown to the user. */
    data class Result(
        val success: Boolean,
        val message: String,
        val viaShizuku: Boolean = false
    )

    /** True when the app itself may write secure settings and does not need Shizuku. */
    fun hasDirectWriteAccess(context: Context): Boolean = runCatching {
        context.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** True when *something* is able to change the proxy right now. */
    fun canControlProxy(context: Context): Boolean =
        hasDirectWriteAccess(context) || (ShizukuManager.isPermissionGranted && ShizukuManager.isBinderAvailable)

    /**
     * Currently configured proxy as `host:port`, or null when no proxy is set.
     * Reading global settings never requires a permission.
     */
    fun currentProxy(context: Context): String? = runCatching {
        val host = Settings.Global.getString(context.contentResolver, KEY_HOST)
        val port = Settings.Global.getString(context.contentResolver, KEY_PORT)
        if (host.isNullOrEmpty() || port.isNullOrEmpty()) null else "$host:$port"
    }.onFailure { logE("Could not read the current proxy", it as? Exception) }.getOrNull()

    // ---------------------------------------------------------------- set / clear

    /**
     * Enables the system proxy.
     * Runs `settings put global http_proxy host:port` — the exact command a user would run
     * through `adb shell`.
     */
    suspend fun enableProxy(context: Context, host: String, port: Int): Result =
        withContext(Dispatchers.IO) {
            val address = "$host:$port"

            if (hasDirectWriteAccess(context)) {
                return@withContext runCatching {
                    Settings.Global.putString(context.contentResolver, KEY_HTTP_PROXY, address)
                    Settings.Global.putString(context.contentResolver, KEY_HOST, host)
                    Settings.Global.putString(context.contentResolver, KEY_PORT, port.toString())
                    Result(true, "Proxy set to $address", viaShizuku = false)
                }.getOrElse { Result(false, "Could not write the proxy setting: ${it.message}") }
            }

            val commands = listOf(
                "settings put global $KEY_HTTP_PROXY $address",
                "settings put global $KEY_HOST $host",
                "settings put global $KEY_PORT $port"
            )

            runCommands(commands, "Proxy set to $address")
        }

    /**
     * Disables the system proxy.
     *
     * Two steps, in this order:
     *
     *  1. write [CLEAR_PROXY_VALUE] to `http_proxy`, which is the only thing that makes
     *     system_server drop the global proxy it holds in memory (see [CLEAR_PROXY_VALUE]);
     *  2. delete every proxy related key, so no stale state is left behind for the next boot.
     *
     * @param reboot reboot afterwards anyway. Only needed on very old builds; kept as an explicit
     *   opt-in for those, but no longer forced the way the 4.x implementation did.
     */
    suspend fun disableProxy(context: Context, reboot: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            val result = disableProxyInternal(context)

            if (reboot && result.success) {
                val rebootResult = rebootDevice(context)
                return@withContext if (rebootResult.success) {
                    result.copy(message = "${result.message}, rebooting…")
                } else {
                    result.copy(message = "${result.message} (reboot failed: ${rebootResult.message})")
                }
            }
            result
        }

    private suspend fun disableProxyInternal(context: Context): Result {
        if (hasDirectWriteAccess(context)) {
            return runCatching {
                Settings.Global.putString(context.contentResolver, KEY_HTTP_PROXY, CLEAR_PROXY_VALUE)
                delay(SETTLE_MS)
                ALL_PROXY_KEYS.forEach {
                    Settings.Global.putString(context.contentResolver, it, null)
                }
                Result(true, "Proxy disabled", viaShizuku = false)
            }.getOrElse {
                logE("Could not clear the proxy setting", it)
                Result(false, "Could not clear the proxy setting: ${it.message}", viaShizuku = false)
            }
        }

        return try {
            val neutralise = ShizukuManager.exec(
                "settings put global $KEY_HTTP_PROXY $CLEAR_PROXY_VALUE"
            )
            if (!neutralise.isSuccess) {
                return Result(
                    false,
                    "Could not disable the proxy (exit ${neutralise.exitCode}): ${neutralise.output}",
                    viaShizuku = true
                )
            }

            delay(SETTLE_MS)

            val deleteScript = ALL_PROXY_KEYS
                .joinToString(separator = "; ") { "settings delete global $it" } + "; true"
            val cleanup = ShizukuManager.exec(deleteScript)
            if (cleanup.isSuccess) {
                Result(true, "Proxy disabled", viaShizuku = true)
            } else {
                Result(
                    false,
                    "Proxy was switched off but could not be cleaned up (exit ${cleanup.exitCode}): ${cleanup.output}",
                    viaShizuku = true
                )
            }
        } catch (e: ShizukuUnavailableException) {
            Result(false, e.message ?: "Shizuku is not available", viaShizuku = true)
        } catch (e: Exception) {
            logE("Disabling the proxy failed", e)
            Result(false, "Unexpected error: ${e.message}", viaShizuku = true)
        }
    }

    /** Reboots the watch. Ignored when the caller has no privileged access. */
    suspend fun rebootDevice(context: Context): Result = withContext(Dispatchers.IO) {
        if (hasDirectWriteAccess(context)) {
            // PowerManager#reboot needs REBOOT which normal apps do not have; use the shell path.
            return@withContext Result(false, "Reboot requires Shizuku")
        }
        runCommands(listOf("reboot"), "Rebooting…")
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun runCommands(commands: List<String>, successMessage: String): Result {
        // `;` keeps going after a failing sub-command and `true` guarantees exit code 0 for the
        // overall shell invocation, because `settings delete` on a missing key is a no-op error.
        val script = commands.joinToString(separator = "; ") + "; true"
        return try {
            val result = ShizukuManager.exec(script)
            if (result.isSuccess) {
                Result(true, successMessage, viaShizuku = true)
            } else {
                Result(false, "Command failed (exit ${result.exitCode}): ${result.output}", viaShizuku = true)
            }
        } catch (e: ShizukuUnavailableException) {
            Result(false, e.message ?: "Shizuku is not available", viaShizuku = true)
        } catch (e: Exception) {
            logE("Proxy command failed", e)
            Result(false, "Unexpected error: ${e.message}", viaShizuku = true)
        }
    }

    /** Exposed for diagnostics: `id` through whatever privileged path is currently available. */
    suspend fun describePrivilege(context: Context): String = withContext(Dispatchers.IO) {
        when {
            hasDirectWriteAccess(context) -> "direct (WRITE_SECURE_SETTINGS)"
            !ShizukuManager.isSupported -> "none (Shizuku not installed)"
            !ShizukuManager.isBinderAvailable -> "none (Shizuku not running)"
            !ShizukuManager.isPermissionGranted -> "none (Shizuku permission denied)"
            else -> {
                val uid = ShizukuManager.serverUid
                val label = if (uid == 0) "root" else "shell"
                runCatching {
                    val out = ShizukuManager.exec("id").output
                    "shizuku ($label, $out)"
                }.getOrElse { "shizuku ($label)" }
            }
        }
    }
}
