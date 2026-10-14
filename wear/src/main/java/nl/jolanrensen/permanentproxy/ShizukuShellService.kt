package nl.jolanrensen.permanentproxy

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit

/**
 * The privileged half of Permanent Proxy.
 *
 * This class is **not** instantiated by the app itself. `ShizukuManager` asks the Shizuku server
 * to bind it as a "user service"; the server then creates an instance of this class inside a
 * process that runs with shell (uid 2000, when Shizuku was started with adb / wireless debugging)
 * or root (uid 0, when Shizuku/Sui was started with root) identity.
 *
 * That is exactly the privilege `adb shell` has, which is what the old implementation obtained by
 * talking the ADB protocol over Bluetooth. Here we get the same privilege through Shizuku, which
 * works on far more devices and does not require the "Debug over Bluetooth" developer option.
 */
class ShizukuShellService @JvmOverloads constructor(
    @Suppress("UNUSED_PARAMETER") context: Context? = null
) : IShizukuShellService.Stub() {

    init {
        Log.i(TAG, "ShizukuShellService created, uid=${Process.myUid()}")
    }

    /**
     * Reserved destroy hook. The Shizuku server calls this when the service is unbound with
     * `remove = true`; the process is not killed automatically, so we have to exit ourselves.
     */
    override fun destroy() {
        Log.i(TAG, "ShizukuShellService destroyed, exiting")
        System.exit(0)
    }

    override fun getShellUid(): Int = Process.myUid()

    override fun exec(command: String): ShellResult {
        Log.i(TAG, "exec($command)")

        val process = try {
            ProcessBuilder("/system/bin/sh", "-c", command)
                // Merge stderr into stdout so a single reader thread is enough.
                .redirectErrorStream(true)
                .apply {
                    // The user-service process may have inherited a minimal environment.
                    environment()["PATH"] = DEFAULT_PATH
                }
                .start()
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start shell", t)
            return ShellResult(-1, "Could not start shell: ${t.message}")
        }

        // We never write to stdin; closing it makes commands that read stdin terminate.
        runCatching { process.outputStream.close() }

        // Read in a separate thread: the pipe buffer is small and could otherwise block the
        // child process while we are waiting for it to finish.
        var output = ""
        val reader = Thread {
            output = runCatching {
                process.inputStream.bufferedReader().use { it.readText() }
            }.getOrDefault("")
        }.apply {
            name = "shizuku-shell-reader"
            isDaemon = true
            start()
        }

        val finished = try {
            process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (!finished) {
            Log.w(TAG, "Command timed out after ${TIMEOUT_SECONDS}s: $command")
            runCatching { process.destroyForcibly() }
        }

        reader.join(READER_JOIN_MS)

        val exitCode = if (finished) {
            runCatching { process.exitValue() }.getOrDefault(-1)
        } else {
            TIMEOUT_EXIT_CODE
        }

        val trimmed = output.trim()
        Log.i(TAG, "exec done, exit=$exitCode, output=${trimmed.take(200)}")
        return ShellResult(exitCode, trimmed)
    }

    companion object {
        private const val TAG = "PermanentProxy.Shell"
        private const val DEFAULT_PATH = "/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin"
        private const val TIMEOUT_SECONDS = 60L
        private const val READER_JOIN_MS = 2_000L

        /** Not a real shell exit code; signals that the command had to be killed. */
        const val TIMEOUT_EXIT_CODE = -2
    }
}
