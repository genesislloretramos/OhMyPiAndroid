package omp.shell

import java.io.File

/**
 * A JVM implementation of [PlatformServices] for tests: a temp directory as `$HOME`, fixed
 * properties, and no device behind any of it.
 */
class StubPlatformServices(
    val home: String,
    val initialDir: String,
    val external: String? = null,
    allFilesGranted: Boolean = true,

    /**
     * The app's private storage, i.e. the directory `home` sits in — which is how the real
     * implementation reads, `home` being `files/home`. Defaulted from `home` so a test that only
     * cares about `$HOME` says nothing about this, and a test that cares passes it explicitly.
     */
    val appFiles: String = File(home).absoluteFile.parentFile?.absolutePath
        ?: File(home).absolutePath,
) : PlatformServices {

    var homeOverride: String? = null
    var storageManager: Boolean = allFilesGranted
    var grantedRequests = 0
    val props = HashMap<String, String>()
    val propertyOverrides = HashMap<String, String>()
    val settings = HashMap<String, HashMap<String, String>>()
    val volumes = ArrayList<PlatformServices.StorageVolume>()
    val packages = ArrayList<String>()
    val battery = PlatformServices.BatteryInfo(50, 100, "2", "2", "0", 250, 4000, 0, 1000000, "Li-ion")
    val memory = PlatformServices.MemoryInfo(2L * 1024 * 1024 * 1024, 512L * 1024 * 1024)
    val interfaces = ArrayList<PlatformServices.NetInterface>()
    val route = PlatformServices.Route("wlan0", listOf("192.168.1.0/24"), listOf("8.8.8.8"))
    var captured: ByteArray? = null
    var clipboard: String? = null
    var windowTitle: String = ""
    var http: PlatformServices.HttpResult? = null

    /**
     * The streaming seam, as a field a test sets. There is no sensible default for it: a stream is
     * a socket and a script of events, and a stub that invented one would answer a question nobody
     * asked. A test that wants one points this at a real server.
     */
    var stream: ((String, String, List<Pair<String, String>>, ByteArray?) -> HttpStream)? = null
    val prefs = HashMap<String, String>()
    var pssKb = 12345L
    var cpuTimes = 0L to 0L
    var clock = 1_700_000_000_000L
    var monotonic = 3_600_000L

    override fun homeDir(): String = home

    override fun appFilesDir(): String = appFiles
    override fun initialDirectory(): String = initialDir
    override fun externalStorageDir(): String? = external

    override fun isExternalStorageManager(): Boolean = storageManager

    override fun requestAllFilesAccess() {
        grantedRequests++
    }

    override fun wallClockMillis(): Long = clock
    override fun monotonicMillis(): Long = monotonic
    override fun timeZoneId(): String = "UTC"
    override fun locale(): String = "en_US"

    override fun buildProperties(): Map<String, String> = props
    override fun systemPropertyOverrides(): Map<String, String> = propertyOverrides
    override fun deviceName(): String? = "stub device"
    override fun appUid(): Int = 10123
    override fun appGid(): Int = 10123
    override fun processName(): String = "omp.test"
    override fun processPid(): Int = 4242

    override fun displayWidthPx(): Int = 1080
    override fun displayHeightPx(): Int = 2400
    override fun displayDensityDpi(): Int = 420
    override fun displayRefreshRateHz(): Float = 60f
    override fun displayName(): String = "stub display"

    override fun storageVolumes(): List<PlatformServices.StorageVolume> = volumes

    override fun clipboardWrite(text: String) {
        clipboard = text
    }

    override fun clipboardRead(): String? = clipboard
    override fun setTitle(title: String) {
        windowTitle = title
    }

    override fun installedPackages(includeSystem: Boolean, includeDisabled: Boolean, includeUninstalled: Boolean): List<String> =
        packages.filter { includeSystem || it.startsWith("app.") }

    override fun packagePaths(pkg: String): List<String> =
        if (packages.contains(pkg)) listOf("/data/app/$pkg/base.apk") else emptyList()

    override fun installerOf(pkg: String): String? = null
    override fun forceStopPackage(pkg: String): String? = null
    override fun startActivity(spec: PlatformServices.IntentSpec): String? = null

    override fun settingGet(namespace: String, key: String): String? = settings[namespace]?.get(key)
    override fun settingList(namespace: String): Map<String, String> = settings[namespace] ?: emptyMap()

    override fun batteryInfo(): PlatformServices.BatteryInfo = battery
    override fun systemMemory(): PlatformServices.MemoryInfo = memory
    override fun processPssKb(): Long = pssKb

    override fun processCpuTimes(): Pair<Long, Long> {
        cpuTimes = cpuTimes.first + 100 to cpuTimes.second + 1000
        return cpuTimes
    }

    override fun networkInterfaces(): List<PlatformServices.NetInterface> = interfaces
    override fun activeRoute(): PlatformServices.Route = route

    override fun captureScreenPng(): ByteArray? = captured
    override fun httpGet(url: String, method: String, headers: List<Pair<String, String>>): PlatformServices.HttpResult =
        http ?: PlatformServices.HttpResult(0, ByteArray(0), emptyList())

    override fun httpStream(url: String, method: String, headers: List<Pair<String, String>>, body: ByteArray?): HttpStream =
        stream?.invoke(url, method, headers, body)
            ?: throw UnsupportedOperationException("no stream configured")

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
