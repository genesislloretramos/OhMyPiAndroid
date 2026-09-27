package omp.shell

/**
 * Everything the shell needs to know about the device.
 *
 * `:core` is a plain JVM library, so every platform capability is reached through this interface and
 * the Android implementation lives in `:app`. The test double is [StubPlatformServices].
 */
interface PlatformServices {

    // ---- environment -------------------------------------------------------------------

    /**
     * The app's private storage, the directory everything this app writes lives under.
     *
     * Always writable, no permission needed; the VM's disk is a subdirectory of it. [homeDir] is
     * another one, so the phone shell's files and a VM's rootfs cannot collide, and `adb shell
     * run-as com.omp.terminal ls files/` shows both.
     */
    fun appFilesDir(): String

    /** `$HOME`; created with a `.profile` on first use. Always writable, no permission needed. */
    fun homeDir(): String

    /** Where the shell starts; the user's home directory. */
    fun initialDirectory(): String

    /** Primary shared storage, or null when it is not mounted. The target of the `/sdcard` alias. */
    fun externalStorageDir(): String?

    /**
     * The app's native library directory — the one the package manager extracts this app's own
     * `.so` files into, which is why `android:extractNativeLibs` exists — or null when the platform
     * has not told us.
     *
     * It is here for one caller: [omp.vm.provision], which has to know where a **native helper** is
     * allowed to live. A downloaded Debian and a downloaded agent do not need it; proot does,
     * because the kernel is the thing that has to exec *something*, and since Android 10 that
     * something may not be a file in the app's own storage. Putting a directory in the shell's
     * platform seam for that one fact is a smaller change than a second seam nobody shares.
     *
     * Null by default, deliberately: a platform that has not answered is not the same as a
     * platform with no such directory, and the code that asks has to say which one it is looking
     * at rather than guess. The Android implementation overrides it.
     */
    fun nativeLibraryDir(): String? = null

    /**
     * True when the app holds "All files access". Re-read at every call: the user can revoke the
     * grant from system settings at any moment, so a cached answer goes stale silently.
     */
    fun isExternalStorageManager(): Boolean

    /** Opens the system screen that grants "All files access" for this package. */
    fun requestAllFilesAccess()

    // ---- clock -------------------------------------------------------------------------

    /** Wall-clock milliseconds since the epoch. */
    fun wallClockMillis(): Long

    /**
     * Monotonic milliseconds since boot. `SystemClock.elapsedRealtime()`; `/proc/uptime` is closed
     * to apps by SELinux, so this is the only honest source.
     */
    fun monotonicMillis(): Long

    fun timeZoneId(): String

    fun locale(): String

    // ---- device ------------------------------------------------------------------------

    /** One `key=value` line from the read-only build property files. */
    fun buildProperties(): Map<String, String>

    /** Readable `Settings.Global` / `Secure` / `System` values, overlaid on the build properties. */
    fun systemPropertyOverrides(): Map<String, String>

    /** User-set device name, or null. */
    fun deviceName(): String?

    /** The app's Linux uid. */
    fun appUid(): Int

    /** The app's process name, e.g. `com.omp.terminal`. */
    fun processName(): String

    /** The app's own pid. Android has no `java.lang.ProcessHandle`, so it comes from the platform. */
    fun processPid(): Int

    // ---- display -----------------------------------------------------------------------

    fun displayWidthPx(): Int
    fun displayHeightPx(): Int
    fun displayDensityDpi(): Int
    fun displayRefreshRateHz(): Float
    fun displayName(): String

    // ---- storage volumes ---------------------------------------------------------------

    fun storageVolumes(): List<StorageVolume>

    // ---- clipboard / window -----------------------------------------------------------

    fun clipboardWrite(text: String)
    fun clipboardRead(): String?
    fun setTitle(title: String)

    // ---- packages ---------------------------------------------------------------------

    fun installedPackages(includeSystem: Boolean, includeDisabled: Boolean, includeUninstalled: Boolean): List<String>

    /** The app's Linux gid; Android gives an app its own uid and uses it as the group too. */
    fun appGid(): Int

    /** `sourceDir` plus split APKs, as `pm path` prints them. */
    fun packagePaths(pkg: String): List<String>

    /** The installing package, or null when unknown. */
    fun installerOf(pkg: String): String?

    /** `KILL_BACKGROUND_PROCESSES` is a normal permission, so this can throw. */
    fun forceStopPackage(pkg: String): String?

    /** Starts an activity; the string is the error message to print, or null on success. */
    fun startActivity(spec: IntentSpec): String?

    // ---- settings ---------------------------------------------------------------------

    fun settingGet(namespace: String, key: String): String?
    fun settingList(namespace: String): Map<String, String>

    // ---- battery ----------------------------------------------------------------------

    fun batteryInfo(): BatteryInfo

    // ---- memory / process -------------------------------------------------------------

    fun systemMemory(): MemoryInfo

    /** This process's PSS in kB, from `Debug.getMemoryInfo()`. There is no honest `RSS` on Android. */
    fun processPssKb(): Long

    /** `(cpuMillis, sinceBootMillis)` for this process, or null when unavailable. */
    fun processCpuTimes(): Pair<Long, Long>?

    // ---- network ----------------------------------------------------------------------

    fun networkInterfaces(): List<NetInterface>
    fun activeRoute(): Route?

    // ---- capture / http ---------------------------------------------------------------

    /** PNG bytes of this app's own window, or null when the capture failed. */
    fun captureScreenPng(): ByteArray?

    fun httpGet(url: String, method: String, headers: List<Pair<String, String>>): HttpResult

    /**
     * Opens a streaming request and answers the first event, so a bad key fails before a prompt is
     * typed.
     *
     * [HttpResult] is the other half of this seam and the wrong shape for a model: it holds a
     * whole body, and a reply that streams arrives a token at a time over minutes. The status is
     * on the [HttpStream] because the two are one fact — a 401 is a failed request, and a second
     * call to ask would be a second chance to race.
     *
     * The returned stream reads through **checkpoints**, not against a timeout: a read timeout of
     * [SseStream.STREAM_POLL_MS] is a place to notice a `close()` from another thread, and
     * [SseStream.STREAM_SILENCE_LIMIT_MS] is the limit. Both are the implementation's decision and
     * neither is a parameter here, because a caller that could set them would be a caller that
     * could set the limit low enough to kill a reasoning model's silence.
     */
    fun httpStream(url: String, method: String, headers: List<Pair<String, String>>, body: ByteArray?): HttpStream

    // ---- settings store ----------------------------------------------------------------

    fun prefInt(key: String, fallback: Int): Int
    fun prefBoolean(key: String, fallback: Boolean): Boolean
    fun prefString(key: String, fallback: String): String
    fun putPrefInt(key: String, value: Int)
    fun putPrefBoolean(key: String, value: Boolean)
    fun putPrefString(key: String, value: String)

    data class StorageVolume(
        val mountPoint: String,
        /** `mounted`, `unmounted`, `checking`, `ejecting` — whatever the framework reports. */
        val state: String,
        val primary: Boolean,
        val removable: Boolean,
        val emulated: Boolean,
        val totalBytes: Long,
        val usableBytes: Long,
    )

    data class BatteryInfo(
        val level: Int,
        val scale: Int,
        val status: String,
        val health: String,
        val plugged: String,
        /** Tenths of a degree Celsius, as the real tool prints it. */
        val temperatureTenthsC: Int,
        val voltageMv: Int,
        val currentMa: Int,
        val chargeCounterUah: Int,
        val technology: String,
    )

    data class MemoryInfo(
        val totalBytes: Long,
        val availableBytes: Long,
    )

    data class NetInterface(
        val name: String,
        val addresses: List<String>,
        val flags: List<String>,
        val mac: String?,
    )

    data class Route(
        val interfaceName: String,
        val addresses: List<String>,
        val dnsServers: List<String>,
    )

    data class IntentSpec(
        val action: String?,
        val data: String?,
        val type: String?,
        val category: String?,
        val component: String?,
    )

    data class HttpResult(
        val code: Int,
        val body: ByteArray,
        val headers: List<Pair<String, String>>,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is HttpResult && code == other.code && body.contentEquals(other.body))

        override fun hashCode(): Int = 31 * code + body.contentHashCode()
    }
}
