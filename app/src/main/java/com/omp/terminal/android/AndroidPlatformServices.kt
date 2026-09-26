package com.omp.terminal.android

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.storage.StorageManager
import android.os.storage.StorageVolume as AndroidStorageVolume
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.PixelCopy
import android.view.Window
import android.view.WindowManager
import omp.shell.PlatformServices
import omp.shell.PlatformServices.IntentSpec
import omp.shell.PlatformServices.BatteryInfo
import omp.shell.PlatformServices.HttpResult
import omp.shell.PlatformServices.MemoryInfo
import omp.shell.PlatformServices.NetInterface
import omp.shell.PlatformServices.Route
import omp.shell.PlatformServices.StorageVolume
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The one place the shell touches Android. Everything a command can see goes through
 * [PlatformServices], so `:core` stays a plain JVM library and no core command ever imports
 * `android.*`.
 *
 * Two rules shape every method here:
 *  * **No hidden API.** `SystemProperties` reflection has been blocked since API 28, so build
 *    properties are parsed from the `*.prop` files the framework itself ships and overlaid with
 *    the readable `Settings` tables. Where the only source is `@hide` (`ActivityManager
 *    .forceStopPackage`, `Debug.getCpuUsageNanos`) the public equivalent is used and named.
 *  * **No fabricated value.** Where the platform refuses an ordinary app something, the method
 *    returns a precise `null` or an error string, and the command turns that into a diagnostic.
 *
 * @param context an application context: the shell outlives any Activity, so nothing here may
 *   capture one strongly.
 * @param activity the window `screencap` copies, held weakly. Optional, so the same implementation
 *   can back a shell that has no window yet.
 */
class AndroidPlatformServices(
    context: Context,
    activity: Activity? = null,
) : PlatformServices {

    private val app: Context = context.applicationContext
    private val activityRef: WeakReference<Activity>? = activity?.let { WeakReference(it) }
    private val prefs by lazy { app.getSharedPreferences("omp", Context.MODE_PRIVATE) }

    /** Set by the Activity so an `OSC 0;title` sequence reaches the window title. */
    var titleListener: ((String) -> Unit)? = null

    private var title = ""

    init {
        AndroidRuntime.bind(app)
    }

    // ---- environment -------------------------------------------------------------------

    /**
     * Internal storage, not the external app directory: writable with no permission on every
     * device, and `run-as com.omp.terminal cat files/home/...` can read what the shell wrote.
     * `ShellSession` creates the directory and its `.profile` on first run.
     */
    override fun homeDir(): String = File(app.filesDir, "home").absolutePath

    override fun initialDirectory(): String = homeDir()

    override fun externalStorageDir(): String? {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return null
        return Environment.getExternalStorageDirectory().absolutePath
    }

    /** Re-read on every call: the user can revoke the grant from system settings at any moment. */
    override fun isExternalStorageManager(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else false

    override fun requestAllFilesAccess() {
        // "All files access" does not exist below API 30; there is no screen to open and the
        // answer to isExternalStorageManager() is already false there.
        if (Build.VERSION.SDK_INT < 30) return
        val perApp = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:" + app.packageName),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(perApp)
        } catch (e: ActivityNotFoundException) {
            // Some OEM images ship without the per-app screen; the all-apps list is the fallback.
            val allApps = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                app.startActivity(allApps)
            } catch (e2: ActivityNotFoundException) {
                // Nothing on this device opens it; the status row keeps showing the hint.
            }
        }
    }

    // ---- clock -------------------------------------------------------------------------

    override fun wallClockMillis(): Long = System.currentTimeMillis()

    override fun monotonicMillis(): Long = SystemClock.elapsedRealtime()

    override fun timeZoneId(): String = TimeZone.getDefault().id

    override fun locale(): String = Locale.getDefault().toString()

    // ---- device ------------------------------------------------------------------------

    override fun buildProperties(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (path in BUILD_PROP_FILES) {
            val file = File(path)
            if (!file.canRead()) continue
            try {
                file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed[0] == '#') continue
                        val eq = trimmed.indexOf('=')
                        if (eq <= 0) continue
                        out[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
                    }
                }
            } catch (e: Exception) {
                // A vendor prop file can be sealed on a locked-down build; the next file wins.
            }
        }
        out.putAll(buildFields())
        return out
    }

    /**
     * From Android 10 the prop files are mode 0600 root:root and no app can read them, so the
     * `ro.build.*` names are reconstructed from [Build] — which is the same source the framework
     * itself populates them from, and the only one an ordinary app can reach.
     */
    private fun buildFields(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun put(key: String, value: String?) {
            if (!value.isNullOrEmpty()) out[key] = value
        }
        put("ro.build.version.release", Build.VERSION.RELEASE)
        put("ro.build.version.sdk", Build.VERSION.SDK_INT.toString())
        put("ro.build.version.security_patch", Build.VERSION.SECURITY_PATCH)
        put("ro.build.version.preview_sdk", Build.VERSION.PREVIEW_SDK_INT.takeIf { it > 0 }?.toString())
        put("ro.build.version.incremental", Build.VERSION.INCREMENTAL)
        put("ro.build.version.codename", Build.VERSION.CODENAME)
        put("ro.build.fingerprint", Build.FINGERPRINT)
        put("ro.build.description", Build.DISPLAY)
        put("ro.build.display.id", Build.ID)
        put("ro.build.type", Build.TYPE)
        put("ro.build.tags", Build.TAGS)
        put("ro.product.model", Build.MODEL)
        put("ro.product.brand", Build.BRAND)
        put("ro.product.manufacturer", Build.MANUFACTURER)
        put("ro.product.name", Build.PRODUCT)
        put("ro.product.device", Build.DEVICE)
        put("ro.product.board", Build.BOARD)
        put("ro.product.cpu.abi", Build.SUPPORTED_ABIS.firstOrNull())
        put("ro.product.cpu.abilist", Build.SUPPORTED_ABIS.joinToString(","))
        put("ro.hardware", Build.HARDWARE)
        put("ro.build.host", Build.HOST)
        put("ro.build.user", Build.USER)
        return out
    }

    /**
     * Readable `Settings` values, prefixed with their namespace so a `getprop` merge cannot let
     * `Settings.Secure` shadow a build property of the same name. A table the provider refuses is
     * skipped, which leaves its keys absent rather than wrong.
     */
    override fun systemPropertyOverrides(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (namespace in SETTINGS_NAMESPACES) {
            val rows = try {
                querySettings(namespace)
            } catch (e: SecurityException) {
                continue
            } catch (e: Exception) {
                continue
            }
            for ((key, value) in rows) out["$namespace:$key"] = value
        }
        return out
    }

    override fun deviceName(): String? = try {
        Settings.Global.getString(app.contentResolver, DEVICE_NAME_SETTING)
    } catch (e: Exception) {
        null
    }

    override fun appUid(): Int = Process.myUid()

    /** Android gives an app its own uid and uses it as the group, so the two are the same number. */
    override fun appGid(): Int = Process.myUid()

    override fun processPid(): Int = Process.myPid()

    override fun processName(): String =
        if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() ?: app.packageName else app.packageName

    // ---- display -----------------------------------------------------------------------

    override fun displayWidthPx(): Int = displayMetrics().widthPixels

    override fun displayHeightPx(): Int = displayMetrics().heightPixels

    override fun displayDensityDpi(): Int = displayMetrics().densityDpi

    override fun displayRefreshRateHz(): Float = display()?.refreshRate ?: 0f

    override fun displayName(): String = display()?.name ?: "unknown"

    private fun displayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val d = display()
        if (d == null) {
            metrics.setTo(app.resources.displayMetrics)
            return metrics
        }
        d.getRealMetrics(metrics)
        return metrics
    }

    @Suppress("DEPRECATION")
    private fun display(): Display? {
        val activity = activityRef?.get()
        if (activity != null) {
            val activityManager = activity.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (activityManager != null) return activityManager.defaultDisplay
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val fromContext = app.display
            if (fromContext != null) return fromContext
        }
        val appManager = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        return appManager?.defaultDisplay
    }

    // ---- storage volumes ---------------------------------------------------------------

    /**
     * `StorageVolume.getDirectory()` — the only public way to learn where a volume is mounted —
     * arrived in API 30. Below that the framework exposes the volume list but none of its paths,
     * so this reports the one volume whose path the public environment API does give: primary
     * shared storage, with the same real numbers `df` would stat.
     */
    override fun storageVolumes(): List<StorageVolume> {
        if (Build.VERSION.SDK_INT < 30) return listOfNotNull(primarySharedVolume())
        val sm = app.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return emptyList()
        val volumes = try {
            sm.storageVolumes
        } catch (e: Exception) {
            return emptyList()
        }
        return volumes.mapNotNull { v ->
            val dir = try {
                // StorageVolume.getDirectory() hands back the per-user emulated path; the primary
                // external volume is /storage/emulated/<userId>, which is what users and /sdcard use.
                if (v.isPrimary && Environment.getExternalStorageDirectory() != null) {
                    Environment.getExternalStorageDirectory()
                } else {
                    v.directory
                }
            } catch (e: Exception) {
                null
            } ?: return@mapNotNull null
            volumeOf(dir, state = volumeState(v), primary = v.isPrimary, removable = v.isRemovable, emulated = isEmulated(v))
        }
    }

    private fun primarySharedVolume(): StorageVolume? {
        val path = externalStorageDir() ?: return null
        val state = try {
            Environment.getExternalStorageState()
        } catch (e: Exception) {
            Environment.MEDIA_UNKNOWN
        }
        return volumeOf(
            File(path),
            state = when (state) {
                Environment.MEDIA_MOUNTED -> "mounted"
                Environment.MEDIA_MOUNTED_READ_ONLY -> "mounted_ro"
                Environment.MEDIA_REMOVED -> "removed"
                Environment.MEDIA_UNMOUNTED -> "unmounted"
                else -> state
            },
            primary = true,
            removable = false,
            emulated = true,
        )
    }

    private fun isEmulated(v: AndroidStorageVolume): Boolean =
        if (Build.VERSION.SDK_INT >= 31) v.isEmulated else false

    private fun volumeState(v: AndroidStorageVolume): String = try {
        v.state
    } catch (e: Exception) {
        "unknown"
    }

    private fun volumeOf(file: File, state: String, primary: Boolean, removable: Boolean, emulated: Boolean) =
        StorageVolume(
            mountPoint = file.absolutePath,
            state = state,
            primary = primary,
            removable = removable,
            emulated = emulated,
            totalBytes = file.totalSpace,
            usableBytes = file.usableSpace,
        )

    override fun clipboardWrite(text: String) {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        try {
            cm.setPrimaryClip(ClipData.newPlainText("omp", text))
        } catch (e: Exception) {
            // The clipboard is unavailable while this app is not focused; there is nothing to say.
        }
    }

    override fun clipboardRead(): String? {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        return try {
            if (!cm.hasPrimaryClip()) null else cm.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString()
        } catch (e: Exception) {
            null
        }
    }

    override fun setTitle(title: String) {
        this.title = title
        titleListener?.invoke(title)
    }

    /** The title the shell last set, for a status row that wants to show it. */
    fun currentTitle(): String = title

    // ---- packages ---------------------------------------------------------------------

    /**
     * `getInstalledPackages` returns every package the app may see, system and user, enabled and
     * disabled, so the `include*` flags are applied here rather than through a `MATCH_*` constant
     * the SDK does not expose.
     *
     * `includeUninstalled` cannot be honoured: `MATCH_UNINSTALLED_PACKAGES` is `@hide`, and no
     * public API reaches a package whose APK has been removed. `pm list packages -u` says so on
     * screen rather than passing this list off as complete.
     */
    @Suppress("DEPRECATION")
    override fun installedPackages(
        includeSystem: Boolean,
        includeDisabled: Boolean,
        includeUninstalled: Boolean,
    ): List<String> {
        val pm = app.packageManager
        val installed = try {
            pm.getInstalledPackages(0)
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<String>(installed.size)
        for (info in installed) {
            val appInfo = info.applicationInfo ?: continue
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem && !includeSystem) continue
            if (!includeDisabled && !appInfo.enabled) continue
            out += info.packageName
        }
        return out
    }

    @Suppress("DEPRECATION")
    override fun packagePaths(pkg: String): List<String> {
        val info = try {
            app.packageManager.getPackageInfo(pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
        val appInfo = info.applicationInfo ?: return emptyList()
        val splits = appInfo.splitSourceDirs
        val out = ArrayList<String>(1 + (splits?.size ?: 0))
        out += "package:${appInfo.sourceDir}"
        if (splits != null) {
            for (split in splits) out += "package:$split"
        }
        return out
    }

    override fun installerOf(pkg: String): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            app.packageManager.getInstallSourceInfo(pkg).installingPackageName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * `ActivityManager.forceStopPackage` is `@hide`, so the closest public call is
     * [ActivityManager.killBackgroundProcesses] under `KILL_BACKGROUND_PROCESSES` — a normal
     * permission. It kills the package's background processes but does not bar the package from
     * being restarted, which is the difference the command reports on success.
     *
     * @return null on success, otherwise the diagnostic to print.
     */
    override fun forceStopPackage(pkg: String): String? {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return "Error: ActivityManager is unavailable"
        try {
            app.packageManager.getApplicationInfo(pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return "Error: package $pkg not found"
        } catch (e: Exception) {
            return "Error: package $pkg is not visible to this app"
        }
        return try {
            am.killBackgroundProcesses(pkg)
            null
        } catch (e: SecurityException) {
            "Error: force-stop requires the KILL_BACKGROUND_PROCESSES permission"
        } catch (e: Exception) {
            "Error: killing $pkg failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    override fun startActivity(spec: IntentSpec): String? {
        val intent = Intent()
        spec.action?.let { intent.action = it }
        spec.data?.let { intent.data = Uri.parse(it) }
        spec.type?.let { intent.type = it }
        spec.category?.let { intent.addCategory(it) }
        val componentName = spec.component
        if (componentName != null) {
            val component = ComponentName.unflattenFromString(componentName)
                ?: return "Error: '$componentName' is not a valid component name"
            intent.component = component
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            app.startActivity(intent)
            null
        } catch (e: ActivityNotFoundException) {
            "Error: Activity not started, unable to resolve Intent { ${describeIntent(spec)} }"
        } catch (e: SecurityException) {
            "Error: ${e.message ?: "not permitted to start this activity"}"
        }
    }

    // ---- settings ---------------------------------------------------------------------

    /**
     * Null for an unknown namespace, an unset key, or a key the app may not read — the three are
     * indistinguishable through this interface, and `settings get` prints an empty line for each,
     * exactly as the real tool does.
     */
    override fun settingGet(namespace: String, key: String): String? {
        val resolver = app.contentResolver
        return try {
            when (namespace.lowercase(Locale.ROOT)) {
                "global" -> Settings.Global.getString(resolver, key)
                "secure" -> Settings.Secure.getString(resolver, key)
                "system" -> Settings.System.getString(resolver, key)
                else -> null
            }
        } catch (e: IllegalArgumentException) {
            // A key the table does not define at all; the provider rejects the name outright.
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Every key the provider will show this app, read with a plain query on the namespace URI:
     * the SDK exposes no `getNames()`, and the constants on the classes are the well-known keys
     * rather than the whole table.
     *
     * @throws SecurityException when the provider refuses the whole table, so `settings list` can
     *   say so instead of printing an empty list.
     */
    override fun settingList(namespace: String): Map<String, String> {
        val rows = try {
            querySettings(namespace)
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            return emptyMap()
        }
        val out = LinkedHashMap<String, String>(rows.size)
        for ((key, value) in rows) out[key] = value
        return out
    }

    private fun querySettings(namespace: String): List<Pair<String, String>> {
        val uri = when (namespace.lowercase(Locale.ROOT)) {
            "global" -> Settings.Global.CONTENT_URI
            "secure" -> Settings.Secure.CONTENT_URI
            "system" -> Settings.System.CONTENT_URI
            else -> return emptyList()
        }
        val out = ArrayList<Pair<String, String>>()
        val cursor = app.contentResolver.query(
            uri,
            arrayOf(Settings.NameValueTable.NAME, Settings.NameValueTable.VALUE),
            null,
            null,
            Settings.NameValueTable.NAME + " ASC",
        ) ?: return out
        cursor.use {
            val nameIndex = it.getColumnIndex(Settings.NameValueTable.NAME)
            val valueIndex = it.getColumnIndex(Settings.NameValueTable.VALUE)
            if (nameIndex < 0 || valueIndex < 0) return out
            while (it.moveToNext()) {
                val key = it.getString(nameIndex) ?: continue
                if (it.isNull(valueIndex)) continue
                out += key to it.getString(valueIndex)
            }
        }
        return out
    }

    // ---- battery ----------------------------------------------------------------------

    /**
     * Level, status, health, current and charge counter come from [BatteryManager]; temperature,
     * voltage, technology and the plugged source come from the sticky `ACTION_BATTERY_CHANGED`
     * broadcast, the only public source for them.
     */
    override fun batteryInfo(): BatteryInfo {
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        // A null receiver reads the sticky broadcast without registering anything. API 33 added
        // the export flag, and Android 14 refuses a receiver registered without one.
        val sticky = try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(null, filter)
            }
        } catch (e: Exception) {
            null
        }

        fun prop(key: Int): Int = try {
            bm?.getIntProperty(key) ?: UNDEFINED_PROPERTY
        } catch (e: Exception) {
            UNDEFINED_PROPERTY
        }

        val statusCode = prop(BatteryManager.BATTERY_PROPERTY_STATUS)
        val healthCode = sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val pluggedCode = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val capacity = prop(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val current = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val counter = prop(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

        return BatteryInfo(
            level = when {
                capacity != UNDEFINED_PROPERTY -> capacity
                else -> sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            },
            scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100,
            status = batteryStatusName(statusCode),
            health = batteryHealthName(healthCode),
            plugged = pluggedName(pluggedCode),
            temperatureTenthsC = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0,
            voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0,
            // getIntProperty reports an unsupported property as Int.MIN_VALUE; a device that does
            // not measure current is 0, not a sentinel every reader would have to know about.
            currentMa = if (current == UNDEFINED_PROPERTY) 0 else current,
            chargeCounterUah = if (counter == UNDEFINED_PROPERTY) -1 else counter,
            technology = sticky?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "unknown",
        )
    }

    private fun batteryStatusName(code: Int): String = when (code) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_UNKNOWN -> "unknown"
        else -> "code:$code"
    }

    private fun batteryHealthName(code: Int): String = when (code) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "unspecified failure"
        else -> "code:$code"
    }

    private fun pluggedName(code: Int): String {
        val names = ArrayList<String>()
        if (code and BatteryManager.BATTERY_PLUGGED_AC != 0) names += "ac"
        if (code and BatteryManager.BATTERY_PLUGGED_USB != 0) names += "usb"
        if (code and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0) names += "wireless"
        if (Build.VERSION.SDK_INT >= 33 && code and BatteryManager.BATTERY_PLUGGED_DOCK != 0) names += "dock"
        return if (names.isEmpty()) "none" else names.joinToString(",")
    }

    // ---- memory / process -------------------------------------------------------------

    override fun systemMemory(): MemoryInfo {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return MemoryInfo(0, 0)
        return try {
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            MemoryInfo(info.totalMem, info.availMem)
        } catch (e: Exception) {
            MemoryInfo(0, 0)
        }
    }

    override fun processPssKb(): Long {
        return try {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            info.totalPss.toLong()
        } catch (e: Exception) {
            0
        }
    }

    /**
     * `Debug.getCpuUsageNanos()` is `@hide`; [Process.getElapsedCpuTime] is the public source of
     * the same quantity, in milliseconds, and pairs with the monotonic clock so a caller can take
     * a delta the way `ps` and `top` do.
     */
    override fun processCpuTimes(): Pair<Long, Long>? = try {
        Process.getElapsedCpuTime() to SystemClock.elapsedRealtime()
    } catch (e: Exception) {
        null
    }

    // ---- network ----------------------------------------------------------------------

    /**
     * Netlink through [java.net.NetworkInterface], which is why this works at all:
     * `app_neverallows.te` keeps untrusted apps out of `/proc/net` entirely. Each address is read
     * with its prefix length from [java.net.InterfaceAddress]; Android reports `-1` for an IPv4
     * prefix, so those addresses are emitted without one rather than with a made-up `/24`.
     */
    override fun networkInterfaces(): List<NetInterface> {
        val root = try {
            java.net.NetworkInterface.getNetworkInterfaces()
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val out = ArrayList<NetInterface>()
        while (root.hasMoreElements()) {
            val ni = try {
                root.nextElement()
            } catch (e: Exception) {
                continue
            }
            out += NetInterface(
                name = ni.name,
                addresses = addressStrings(ni),
                flags = interfaceFlags(ni),
                mac = try {
                    ni.hardwareAddress?.joinToString(":") { byte -> "%02x".format(byte) }
                } catch (e: Exception) {
                    null
                },
            )
        }
        return out
    }

    private fun addressStrings(ni: java.net.NetworkInterface): List<String> {
        val out = ArrayList<String>()
        val withPrefix = try {
            ni.interfaceAddresses
        } catch (e: Exception) {
            null
        }
        if (withPrefix != null && withPrefix.isNotEmpty()) {
            for (ia in withPrefix) out += formatAddress(ia.address, ia.networkPrefixLength.toInt())
            return out.filter { it.isNotEmpty() }
        }
        val bound = try {
            ni.inetAddresses
        } catch (e: Exception) {
            null
        }
        while (bound != null && bound.hasMoreElements()) {
            out += formatAddress(bound.nextElement(), null)
        }
        return out.filter { it.isNotEmpty() }
    }

    /** `2001:db8::1/64`, or `10.0.0.5` when the prefix length is not known. */
    private fun formatAddress(addr: java.net.InetAddress, prefix: Int?): String {
        val text = try {
            addr.hostAddress ?: return ""
        } catch (e: Exception) {
            return ""
        }
        if (prefix == null || prefix <= 0) return text
        return "$text/$prefix"
    }

    /**
     * The flags the public API can actually determine. `IFF_RUNNING` and `IFF_BROADCAST` are not
     * among them: `NetworkInterface` exposes no accessor, so they are absent rather than guessed.
     */
    private fun interfaceFlags(ni: java.net.NetworkInterface): List<String> {
        val out = ArrayList<String>()
        fun add(name: String, value: Boolean) {
            if (value) out += name
        }
        add("UP", ni.isUp)
        add("LOOPBACK", ni.isLoopback)
        add("POINTOPOINT", ni.isPointToPoint)
        add("MULTICAST", ni.supportsMulticast())
        add("VIRTUAL", ni.isVirtual)
        return out
    }

    /**
     * The default route and its DNS servers, from [ConnectivityManager.getLinkProperties].
     *
     * `getLinkProperties(Network)` is API 21, but the only public way to name *the* active network
     * is `getActiveNetwork()`, which is API 29. Below that the reachable alternative is
     * `getAllNetworks()`, which returns nothing to an app without `ACCESS_NETWORK_STATE` and
     * carries no active-network guarantee anyway. Rather than invent a route, this returns null and
     * `ip route` prints nothing.
     */
    override fun activeRoute(): Route? {
        if (Build.VERSION.SDK_INT < 29) return null
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val network = try {
            cm.activeNetwork
        } catch (e: Exception) {
            null
        } ?: return null
        val link = try {
            cm.getLinkProperties(network)
        } catch (e: Exception) {
            null
        } ?: return null

        val addresses = ArrayList<String>()
        for (linkAddress in link.linkAddresses) {
            val text = formatAddress(linkAddress.address, linkAddress.prefixLength)
            if (text.isNotEmpty()) addresses += text
        }
        val dns = ArrayList<String>()
        for (server in link.dnsServers) {
            val text = formatAddress(server, null)
            if (text.isNotEmpty() && text !in dns) dns += text
        }
        return Route(link.interfaceName ?: "unknown", addresses, dns)
    }

    // ---- capture / http ---------------------------------------------------------------

    /**
     * `PixelCopy` of this app's own window. Without MediaProjection there is no way to capture
     * anything else, and no other process can capture this window either, so the result is whatever
     * is on this window at this moment.
     *
     * The copy is asynchronous and the shell thread blocks on it; the listener is delivered on the
     * main looper, so the window has to be drawing for this to succeed at all.
     */
    override fun captureScreenPng(): ByteArray? {
        val window: Window = activityRef?.get()?.window ?: return null
        // peekDecorView, not getDecorView: this runs on the shell thread, and getDecorView
        // installs decor state that may only be touched from the main thread. Null means the
        // Activity has not drawn anything yet, and there is nothing to copy.
        val view = window.peekDecorView() ?: return null
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return null

        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            return null
        }
        val latch = CountDownLatch(1)
        val outcome = intArrayOf(PixelCopy.ERROR_UNKNOWN)
        try {
            PixelCopy.request(
                window,
                bitmap,
                { result ->
                    outcome[0] = result
                    latch.countDown()
                },
                Handler(Looper.getMainLooper()),
            )
            if (!latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
            if (outcome[0] != PixelCopy.SUCCESS) return null
            val out = ByteArrayOutputStream(width * height / 4)
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
            return out.toByteArray()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (e: Exception) {
            return null
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * One request through [HttpURLConnection] with the platform trust manager, so TLS is exactly
     * the TLS this device trusts. The status code comes back for an error response too, which is
     * what lets `curl` print the body of a 404.
     *
     * Connection failures are deliberately not caught: `curl` maps `UnknownHostException`,
     * `ConnectException` and `SocketTimeoutException` onto its own wording, which needs the
     * exception type.
     */
    override fun httpGet(url: String, method: String, headers: List<Pair<String, String>>): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            conn.requestMethod = method
            for ((key, value) in headers) conn.setRequestProperty(key, value)
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            val collected = ArrayList<Pair<String, String>>()
            for ((key, values) in conn.headerFields) {
                if (key == null) continue
                collected += key to values.joinToString(", ")
            }
            return HttpResult(code, body, collected)
        } finally {
            conn.disconnect()
        }
    }

    // ---- settings store ----------------------------------------------------------------

    override fun prefInt(key: String, fallback: Int): Int = prefs.getInt(key, fallback)

    override fun prefBoolean(key: String, fallback: Boolean): Boolean = prefs.getBoolean(key, fallback)

    override fun prefString(key: String, fallback: String): String = prefs.getString(key, fallback) ?: fallback

    override fun putPrefInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun putPrefBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun putPrefString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    companion object {
        private const val HTTP_TIMEOUT_MS = 15_000
        private const val CAPTURE_TIMEOUT_MS = 3_000L

        /** `BatteryManager.getIntProperty` reports an unsupported property as `Int.MIN_VALUE`. */
        private const val UNDEFINED_PROPERTY = Int.MIN_VALUE

        /** `Settings.Global.DEVICE_NAME` is `@hide`; this is the key it names. */
        private const val DEVICE_NAME_SETTING = "device_name"

        private val SETTINGS_NAMESPACES = arrayOf("global", "secure", "system")

        /** In order: a later file overrides an earlier one, as the framework itself layers them. */
        private val BUILD_PROP_FILES = listOf(
            "/system/build.prop",
            "/vendor/build.prop",
            "/odm/etc/build.prop",
            "/system/etc/prop.default",
        )
    }
}

/** The `am start` line and its failure text describe the intent the same way. */
internal fun describeIntent(spec: IntentSpec): String {
    val parts = ArrayList<String>()
    spec.action?.let { parts += "act=$it" }
    spec.data?.let { parts += "dat=$it" }
    spec.type?.let { parts += "typ=$it" }
    spec.category?.let { parts += "cat=$it" }
    spec.component?.let { parts += "cmp=$it" }
    parts += "flg=0x10000000"
    return parts.joinToString(" ")
}

/**
 * The application context, for the one thing [PlatformServices] deliberately does not carry: a
 * [Display], which `dumpsys display` needs for its id and HDR capabilities. Bound when
 * [AndroidPlatformServices] is constructed, which the Activity does before any command can run.
 */
internal object AndroidRuntime {
    @Volatile
    var appContext: Context? = null
        private set

    fun bind(context: Context) {
        appContext = context.applicationContext
    }
}
