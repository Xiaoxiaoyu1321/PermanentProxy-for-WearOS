package nl.jolanrensen.permanentproxy

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Single entry point for everything that needs Shizuku.
 *
 * Responsibilities:
 *  - track the Shizuku binder (received / died),
 *  - request and query the Shizuku runtime permission,
 *  - bind / unbind the privileged [ShizukuShellService] user service,
 *  - expose a simple `suspend fun exec(command)` to the rest of the app.
 *
 * ## Why a user service and not `Shizuku.newProcess`?
 * `Shizuku.newProcess` is a hidden API and the Shizuku project has announced its removal
 * (see RikkaApps/Shizuku-API#276). The user-service route is the officially supported one and
 * needs no hidden-API bypass.
 *
 * ## Why not Binder calls to IPackageManager / IAppOpsService?
 * The app needs to run `settings put/delete global ...`, i.e. arbitrary shell commands. A user
 * service with shell/root identity can just do that, and it automatically covers both the
 * "Shizuku started with adb" and the "Shizuku/Sui started with root" cases.
 */
object ShizukuManager {

    /** Request code used for [Shizuku.requestPermission]. Must be != 0. */
    const val PERMISSION_REQUEST_CODE = 1001

    private const val TAG = Constants.LOGTAG

    /** Result of a permission request, delivered through [addPermissionResultListener]. */
    fun interface PermissionResultListener {
        fun onPermissionResult(granted: Boolean, requestCode: Int)
    }

    private val permissionListeners = mutableListOf<PermissionResultListener>()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        binderAlive = true
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died")
        binderAlive = false
        service = null
    }

    private val requestPermissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "Shizuku permission result: requestCode=$requestCode granted=$granted")
        // The binder may only become usable now.
        binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val snapshot = synchronized(permissionListeners) { permissionListeners.toList() }
        snapshot.forEach { it.onPermissionResult(granted, requestCode) }
    }

    @Volatile
    private var binderAlive = false

    @Volatile
    private var service: IShizukuShellService? = null

    private val serviceMutex = Mutex()
    private var pendingBind: CompletableDeferred<Boolean>? = null

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                service = IShizukuShellService.Stub.asInterface(binder)
                Log.i(TAG, "Shizuku user service connected (uid=${runCatching { service?.shellUid }})")
                pendingBind?.complete(true)
            } else {
                Log.w(TAG, "Shizuku user service returned an unusable binder")
                service = null
                pendingBind?.complete(false)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Shizuku user service disconnected")
            service = null
        }
    }

    private lateinit var userServiceArgs: Shizuku.UserServiceArgs

    private fun args(): Shizuku.UserServiceArgs {
        if (!::userServiceArgs.isInitialized) {
            userServiceArgs = Shizuku.UserServiceArgs(
                ComponentName(BuildConfig.APPLICATION_ID, ShizukuShellService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("shell")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE)
        }
        return userServiceArgs
    }

    /** Call once from `Application.onCreate()` / the first activity. */
    fun init() {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
        }.onFailure { Log.e(TAG, "Could not register Shizuku listeners", it) }

        binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        Log.i(TAG, "ShizukuManager initialised, binderAlive=$binderAlive, version=${version}")
    }

    /** Removes the listeners again; Shizuku API 13.1.1+ is safe to call this on Android 7.1 too. */
    fun release() {
        runCatching {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
        }
    }

    fun addPermissionResultListener(listener: PermissionResultListener) {
        synchronized(permissionListeners) { permissionListeners.add(listener) }
    }

    fun removePermissionResultListener(listener: PermissionResultListener) {
        synchronized(permissionListeners) { permissionListeners.remove(listener) }
    }

    // ---------------------------------------------------------------- state queries

    /** True when the Shizuku app is installed *and* its service is currently running. */
    val isBinderAvailable: Boolean
        get() = runCatching { binderAlive && Shizuku.pingBinder() }.getOrDefault(false)

    /** False when no Shizuku (or Sui) is installed at all. */
    val isSupported: Boolean
        get() = runCatching { !Shizuku.isPreV11() }.getOrDefault(false)

    val isPermissionGranted: Boolean
        get() = runCatching {
            isBinderAvailable && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /** True when the user chose "deny and don't ask again". */
    val shouldShowRequestPermissionRationale: Boolean
        get() = runCatching {
            isBinderAvailable && Shizuku.shouldShowRequestPermissionRationale()
        }.getOrDefault(false)

    val version: Int
        get() = runCatching { Shizuku.getVersion() }.getOrDefault(-1)

    /** uid of the Shizuku server: 0 for root, 2000 for adb/shell. */
    val serverUid: Int
        get() = runCatching { Shizuku.getUid() }.getOrDefault(-1)

    /** Human readable description of the privilege level Shizuku currently has. */
    val privilegeDescription: String
        get() = when (serverUid) {
            0 -> "root"
            2000 -> "adb (shell)"
            -1 -> "unknown"
            else -> "uid $serverUid"
        }

    // ---------------------------------------------------------------- permission

    /**
     * Asks Shizuku for permission. The answer arrives through [addPermissionResultListener].
     * Returns false when Shizuku is not usable at all.
     */
    fun requestPermission(): Boolean {
        if (!isBinderAvailable || !isSupported) return false
        return runCatching {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            true
        }.onFailure { Log.e(TAG, "requestPermission failed", it) }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- command execution

    /**
     * Runs [command] with shell/root identity.
     *
     * @throws ShizukuUnavailableException when Shizuku is missing, not running or not permitted.
     */
    suspend fun exec(command: String, bindTimeoutMs: Long = BIND_TIMEOUT_MS): ShellResult {
        val svc = awaitService(bindTimeoutMs)
            ?: throw ShizukuUnavailableException(
                when {
                    !isSupported -> "Shizuku is not installed or is unsupported."
                    !isBinderAvailable -> "Shizuku is not running. Start Shizuku and try again."
                    !isPermissionGranted -> "Permanent Proxy has not been granted Shizuku permission."
                    else -> "Could not connect to the Shizuku service."
                }
            )
        return try {
            svc.exec(command)
        } catch (e: RemoteException) {
            // The service process died (e.g. Shizuku was restarted); drop it so the next call rebinds.
            service = null
            throw ShizukuUnavailableException("Shizuku service connection was lost: ${e.message}", e)
        }
    }

    private suspend fun awaitService(timeoutMs: Long): IShizukuShellService? {
        service?.let { return it }
        if (!isBinderAvailable || !isPermissionGranted) return null

        return serviceMutex.withLock {
            service?.let { return@withLock it }

            val deferred = CompletableDeferred<Boolean>()
            pendingBind = deferred
            try {
                Shizuku.bindUserService(args(), userServiceConnection)
            } catch (t: Throwable) {
                Log.e(TAG, "bindUserService failed", t)
                pendingBind = null
                return@withLock null
            }

            val connected = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
            pendingBind = null
            if (!connected) Log.w(TAG, "Timed out waiting for the Shizuku user service")
            service
        }
    }

    /** Unbinds and destroys the user service process (it calls `System.exit` on destroy). */
    fun unbind() {
        runCatching { Shizuku.unbindUserService(args(), userServiceConnection, true) }
            .onFailure { Log.w(TAG, "unbindUserService failed", it) }
        service = null
    }

    /** Cheap liveness probe used by the UI. */
    suspend fun isShellUsable(): Boolean = withContext(Dispatchers.IO) {
        runCatching { exec("id").isSuccess }.getOrDefault(false)
    }

    private const val BIND_TIMEOUT_MS = 15_000L
}

/** Thrown when a privileged command cannot be executed because Shizuku is unavailable. */
class ShizukuUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
