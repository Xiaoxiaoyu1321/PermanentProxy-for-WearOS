package nl.jolanrensen.permanentproxy

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.jolanrensen.permanentproxy.Constants.logE
import java.util.concurrent.TimeUnit

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
 */
object ProxyManager {

    /** Same keys the `settings` command line tool writes. */
    private const val KEY_HTTP_PROXY = "http_proxy"
    private const val KEY_HOST = "global_http_proxy_host"
    private const val KEY_PORT = "global_http_proxy_port"
    private const val KEY_EXCLUSION_LIST = "global_http_proxy_exclusion_list"
    private const val KEY_PAC_URL = "global_proxy_pac_url"

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
     * Disables the system proxy by deleting every proxy related global setting.
     *
     * @param reboot when true the device is rebooted afterwards. Some (older) Android versions only
     *   pick up a removed proxy after a restart, so this stays available as an explicit choice —
     *   but it is no longer forced, unlike in the old implementation.
     */
    suspend fun disableProxy(context: Context, reboot: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            val result = if (hasDirectWriteAccess(context)) {
                runCatching {
                    listOf(KEY_HTTP_PROXY, KEY_HOST, KEY_PORT, KEY_EXCLUSION_LIST, KEY_PAC_URL)
                        .forEach { Settings.Global.putString(context.contentResolver, it, null) }
                    Result(true, "Proxy disabled", viaShizuku = false)
                }.getOrElse { Result(false, "Could not clear the proxy setting: ${it.message}") }
            } else {
                runCommands(
                    listOf(
                        "settings delete global $KEY_HTTP_PROXY",
                        "settings delete global $KEY_HOST",
                        "settings delete global $KEY_PORT",
                        "settings delete global $KEY_EXCLUSION_LIST",
                        "settings delete global $KEY_PAC_URL"
                    ),
                    "Proxy disabled"
                )
            }

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

    /** Human readable "time since" helper used by the status text. */
    internal fun timeSince(timestampMs: Long): String {
        val elapsed = System.currentTimeMillis() - timestampMs
        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            else -> "${TimeUnit.MILLISECONDS.toHours(elapsed)} h ago"
        }
    }
}
