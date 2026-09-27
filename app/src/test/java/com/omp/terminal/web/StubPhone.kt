package com.omp.terminal.web

import omp.shell.HttpStream
import omp.shell.PlatformServices
import java.io.File

/**
 * A [PlatformServices] over a temp directory, for the tests that need [ChatApi] itself.
 *
 * **It is a stub and not a device, and the things it answers are the ones a test can check.** The
 * app's own [omp.shell.fs.RealVfs] is handed the paths it hands out, so a file [ChatApi] writes is a
 * file [java.io.File] can see, and `externalStorageDir()` is a real directory under the test's own
 * temporary folder so `Documents/omp` is a real container. Everything that would need a phone — a
 * battery, a route, a screen, a key store — answers with a fixed value and is never the subject of
 * the test.
 *
 * It is here, in the app's own test source set, rather than reached from `:core`'s tests, because
 * `:core`'s test classes are not on this module's classpath and a second copy of this is the
 * smaller mistake than a build change that puts them there.
 */
class StubPhone(
    /** The app's private storage. Everything the guest is provisioned into lands under here. */
    val files: File,
    /** Shared storage. `Documents/omp` is made under it, as it is on a device. */
    val external: File,
) : PlatformServices {

    /** The properties a device would have; `ro.product.cpu.abilist` is what picks the ABI. */
    val props = HashMap<String, String>()

    /** Whether this install holds the all-files grant, and the flag a test flips. */
    var granted: Boolean = true

    override fun appFilesDir(): String = files.path
    override fun homeDir(): String = File(files, "home").path
    override fun initialDirectory(): String = homeDir()
    override fun externalStorageDir(): String = external.path
    override fun isExternalStorageManager(): Boolean = granted
    override fun requestAllFilesAccess() {
        granted = true
    }

    override fun wallClockMillis(): Long = 1_700_000_000_000L
    override fun monotonicMillis(): Long = 3_600_000L
    override fun timeZoneId(): String = "UTC"
    override fun locale(): String = "en_US"
    override fun buildProperties(): Map<String, String> = props
    override fun systemPropertyOverrides(): Map<String, String> = emptyMap()
    override fun deviceName(): String? = "stub device"
    override fun appUid(): Int = 10123
    override fun processName(): String = "omp.test"
    override fun processPid(): Int = 4242

    override fun displayWidthPx(): Int = 1080
    override fun displayHeightPx(): Int = 2400
    override fun displayDensityDpi(): Int = 420
    override fun displayRefreshRateHz(): Float = 60f
    override fun displayName(): String = "stub display"

    override fun storageVolumes(): List<PlatformServices.StorageVolume> = emptyList()
    override fun clipboardWrite(text: String) = Unit
    override fun clipboardRead(): String? = null
    override fun setTitle(title: String) = Unit
    override fun installedPackages(
        includeSystem: Boolean,
        includeDisabled: Boolean,
        includeUninstalled: Boolean,
    ): List<String> = emptyList()

    override fun appGid(): Int = 10123
    override fun packagePaths(pkg: String): List<String> = emptyList()
    override fun installerOf(pkg: String): String? = null
    override fun forceStopPackage(pkg: String): String? = null
    override fun startActivity(spec: PlatformServices.IntentSpec): String? = null
    override fun settingGet(namespace: String, key: String): String? = null
    override fun settingList(namespace: String): Map<String, String> = emptyMap()
    override fun batteryInfo(): PlatformServices.BatteryInfo =
        PlatformServices.BatteryInfo(50, 100, "2", "2", "0", 250, 4000, 0, 1_000_000, "Li-ion")

    override fun systemMemory(): PlatformServices.MemoryInfo = PlatformServices.MemoryInfo(2L * 1024 * 1024 * 1024, 512L * 1024 * 1024)
    override fun processPssKb(): Long = 12_345L
    override fun processCpuTimes(): Pair<Long, Long> = 0L to 0L
    override fun networkInterfaces(): List<PlatformServices.NetInterface> = emptyList()
    override fun activeRoute(): PlatformServices.Route? = null
    override fun captureScreenPng(): ByteArray? = null
    override fun httpGet(url: String, method: String, headers: List<Pair<String, String>>): PlatformServices.HttpResult =
        PlatformServices.HttpResult(0, ByteArray(0), emptyList())

    override fun httpStream(
        url: String,
        method: String,
        headers: List<Pair<String, String>>,
        body: ByteArray?,
    ): HttpStream = throw UnsupportedOperationException("no stream in a stub")

    private val prefs = HashMap<String, String>()

    override fun prefInt(key: String, fallback: Int): Int = prefs[key]?.toIntOrNull() ?: fallback
    override fun prefBoolean(key: String, fallback: Boolean): Boolean = prefs[key]?.toBoolean() ?: fallback
    override fun prefString(key: String, fallback: String): String = prefs[key] ?: fallback
    override fun putPrefInt(key: String, value: Int) {
        prefs[key] = value.toString()
    }

    override fun putPrefBoolean(key: String, value: Boolean) {
        prefs[key] = value.toString()
    }

    override fun putPrefString(key: String, value: String) {
        prefs[key] = value
    }
}
