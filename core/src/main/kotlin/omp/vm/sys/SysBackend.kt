package omp.vm.sys

import omp.shell.PlatformServices
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.VEntry
import omp.shell.fs.VDiskUsage
import omp.shell.fs.VNodeType
import omp.shell.fs.VStat
import omp.shell.fs.Vfs
import omp.vm.VmHost
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * `/sys` for a userspace VM: the battery and network nodes a phone can really answer, plus one
 * subtree of our own.
 *
 * The units are the kernel's, not the framework's, because that is what a tool reading a sysfs
 * attribute expects: `voltage_now` in microvolts, `temp` in millidegrees, `charge_counter` in
 * microamp-hours. The one deliberate departure is `current_now`, which this reports in milliamps
 * because [PlatformServices.BatteryInfo] only has milliamps — a wrong unit that is documented is
 * better than a right one nobody can check. `capacity` is a percentage of the battery's own scale,
 * so a device reporting 50 of 100 says `50` and one reporting 3 of 4 also says `75`.
 *
 * `/sys/omp` is ours and says so in its own directory name. It is where the app's real identity
 * lives — the uid, the gid, the ABI, the display — because the rest of `/sys` is a view of hardware
 * this VM does not have. Nothing here is read-only in a way the rest of `/sys` is not: it is all
 * read-only, all of it generated at read time, and none of it is AOSP's.
 */
class SysBackend(private val services: PlatformServices) : Vfs {

    private val batteryFiles = listOf(
        "capacity", "charge_counter", "current_now", "health", "present", "status", "technology",
        "temp", "voltage_now",
    )

    override fun readDir(path: String): List<VEntry> {
        val p = trim(path)
        val names = dirNames(p) ?: run {
            if (content(p) != null) throw FsException(FsErrno.NOT_A_DIRECTORY, path)
            throw FsException(FsErrno.NO_SUCH_FILE, path)
        }
        return names.map { name ->
            val child = if (p == "/") "/$name" else "$p/$name"
            VEntry(name, stat(child))
        }
    }

    override fun stat(path: String): VStat {
        val p = trim(path)
        val text = content(p) ?: run {
            if (dirNames(p) != null) return dirStat()
            throw FsException(FsErrno.NO_SUCH_FILE, path)
        }
        return VStat(
            type = VNodeType.FILE,
            size = text.length.toLong(),
            mtimeMillis = 0L,
            mode = FILE_MODE,
            readable = true,
            writable = false,
        )
    }

    override fun openRead(path: String): InputStream {
        val p = trim(path)
        val text = content(p) ?: run {
            if (dirNames(p) != null) throw FsException(FsErrno.IS_A_DIRECTORY, path)
            throw FsException(FsErrno.NO_SUCH_FILE, path)
        }
        return ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
    }

    override fun readBytes(path: String): ByteArray {
        val p = trim(path)
        val text = content(p) ?: run {
            if (dirNames(p) != null) throw FsException(FsErrno.IS_A_DIRECTORY, path)
            throw FsException(FsErrno.NO_SUCH_FILE, path)
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    // Generated from the truth, so there is nothing to write and nowhere to put it.
    override fun openWrite(path: String, append: Boolean): OutputStream = throw FsException(FsErrno.READ_ONLY, path)
    override fun writeBytes(path: String, bytes: ByteArray): Unit = throw FsException(FsErrno.READ_ONLY, path)
    override fun createFile(path: String): Unit = throw FsException(FsErrno.READ_ONLY, path)
    override fun mkdir(path: String): Unit = throw FsException(FsErrno.READ_ONLY, path)
    override fun delete(path: String): Unit = throw FsException(FsErrno.READ_ONLY, path)
    override fun rename(from: String, to: String): Unit = throw FsException(FsErrno.READ_ONLY, from)
    override fun symlink(target: String, link: String): Unit = throw FsException(FsErrno.READ_ONLY, link)
    override fun setModified(path: String, millis: Long): Unit = throw FsException(FsErrno.READ_ONLY, path)
    override fun rmdir(path: String): Unit = throw FsException(FsErrno.READ_ONLY, path)

    override fun readLink(path: String): String = throw FsException(FsErrno.INVALID_ARGUMENT, path)

    override fun realpath(path: String): String = "/sys" + trim(path)

    override fun diskUsage(path: String): VDiskUsage = VDiskUsage(0L, 0L)

    private fun trim(path: String): String {
        if (path.isEmpty()) return "/"
        val trimmed = if (path.length > 1) path.trimEnd('/') else path
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }

    private fun dirStat(): VStat = VStat(VNodeType.DIRECTORY, 4096L, 0L, DIR_MODE, true, false, true)

    private fun dirNames(path: String): List<String>? = when (path) {
        "/" -> listOf("class", "devices", "omp")
        "/class" -> listOf("net", "power_supply")
        "/class/power_supply" -> listOf("battery")
        "/class/power_supply/battery" -> batteryFiles
        "/class/net" -> services.networkInterfaces().map { it.name }.ifEmpty { listOf("lo") }
        "/devices" -> listOf("system")
        "/devices/system" -> listOf("cpu")
        "/devices/system/cpu" -> (0 until VmHost.cpuCount(services)).map { "cpu$it" }
        "/omp" -> listOf("android")
        "/omp/android" -> ompAndroidFiles
        else -> {
            // One directory per CPU, each with the two attributes a tool reads.
            val cpu = path.trim('/').removePrefix("devices/system/cpu/cpu").toIntOrNull()
            if (cpu != null && cpu < VmHost.cpuCount(services)) {
                listOf("online", "cpufreq_khz")
            } else if (ifaceAt(path) != null) {
                netFiles
            } else {
                null
            }
        }
    }

    private fun content(path: String): String? {
        val battery = services.batteryInfo()
        return netContent(path) ?: when (path) {
            "/class/power_supply/battery/capacity" ->
                // A percentage of the battery's own scale, not a raw level: 3 of 4 is 75.
                "${battery.level * 100 / battery.scale.coerceAtLeast(1)}\n"
            "/class/power_supply/battery/status" -> batteryStatus(battery.status) + "\n"
            "/class/power_supply/battery/health" -> batteryHealth(battery.health) + "\n"
            "/class/power_supply/battery/present" -> (if (battery.level >= 0) "1" else "0") + "\n"
            "/class/power_supply/battery/technology" -> battery.technology + "\n"
            // The kernel's own units: microvolts, millidegrees, microamp-hours.
            "/class/power_supply/battery/voltage_now" -> "${battery.voltageMv * 1000L}\n"
            "/class/power_supply/battery/temp" -> "${battery.temperatureTenthsC * 100L}\n"
            "/class/power_supply/battery/charge_counter" -> "${battery.chargeCounterUah}\n"
            "/class/power_supply/battery/current_now" -> "${battery.currentMa}\n"
            "/devices/system/cpu/online" -> online()
            "/omp/android/device" -> VmHost.prop(services, "ro.product.device", "unknown") + "\n"
            "/omp/android/manufacturer" -> VmHost.prop(services, "ro.product.manufacturer", "unknown") + "\n"
            "/omp/android/model" -> VmHost.model(services) + "\n"
            "/omp/android/build_id" -> VmHost.prop(services, "ro.build.id", "unknown") + "\n"
            "/omp/android/sdk_int" -> VmHost.prop(services, "ro.build.version.sdk", "0") + "\n"
            "/omp/android/release" -> VmHost.prop(services, "ro.build.version.release", "unknown") + "\n"
            "/omp/android/abi" ->
                VmHost.prop(services, "ro.product.cpu.abi", VmHost.prop(services, "ro.product.cpu.abilist", "unknown")) + "\n"
            // The app's real identity, which is what the namespace's own uid is not.
            "/omp/android/uid" -> "${services.appUid()}\n"
            "/omp/android/gid" -> "${services.appGid()}\n"
            "/omp/android/display" ->
                "${services.displayWidthPx()}x${services.displayHeightPx()} " +
                    "${services.displayDensityDpi()}dpi ${services.displayRefreshRateHz()}Hz " +
                    services.displayName() + "\n"
            else -> cpuAttribute(path)
        }
    }

    /** The interface whose directory this is, or null — an interface the platform does not report
     *  is not invented here, and `eth9` is the test for that. */
    private fun ifaceAt(path: String): PlatformServices.NetInterface? =
        services.networkInterfaces().firstOrNull { "/class/net/${it.name}" == path }

    /**
     * The three attributes of an interface. The MTU is a documented constant: Android exposes no API
     * for an interface MTU, and 1500 is what wlan0 and rmnet use — a number that changed and meant
     * nothing would be worse than one that is honest about being a default.
     */
    private fun netContent(path: String): String? {
        val iface = ifaceAt(path.substringBeforeLast('/')) ?: return null
        return when (path.substringAfterLast('/')) {
            "address" -> (iface.mac ?: "00:00:00:00:00:00") + "\n"
            // UP and RUNNING are the two flags that mean the link is usable; a phone's other
            // interfaces almost always are not.
            "operstate" -> (if (iface.flags.contains("UP") || iface.flags.contains("RUNNING")) "up" else "down") + "\n"
            "mtu" -> "1500\n"
            else -> null
        }
    }

    private fun cpuAttribute(path: String): String? {
        val cpu = path.trim('/').removePrefix("devices/system/cpu/cpu").substringBefore('/').toIntOrNull()
            ?: return null
        if (cpu >= VmHost.cpuCount(services)) return null
        return when (path.substringAfterLast('/')) {
            "online" -> "1\n"
            // Android exposes no per-CPU clock to an app, so this is a documented constant rather
            // than a number that changes every read and means nothing.
            "cpufreq_khz" -> "0\n"
            else -> null
        }
    }

    private fun online(): String {
        val cpus = VmHost.cpuCount(services)
        return if (cpus == 1) "0\n" else "0-${cpus - 1}\n"
    }

    /** The framework's numeric enums, as the words a sysfs reader expects. */
    private fun batteryStatus(code: String): String = when (code) {
        "1" -> "Unknown"
        "2" -> "Charging"
        "3" -> "Discharging"
        "4" -> "Not charging"
        "5" -> "Full"
        else -> code
    }

    private fun batteryHealth(code: String): String = when (code) {
        "1" -> "Unknown"
        "2" -> "Good"
        "3" -> "Overheat"
        "4" -> "Dead"
        "5" -> "Over voltage"
        "6" -> "Unspecified failure"
        "7" -> "Cold"
        else -> code
    }

    companion object {
        /** 0444, as every read-only sysfs attribute is. */
        const val FILE_MODE = 0x124
        const val DIR_MODE = 0x155

        /** The three attributes a tool reads out of an interface directory. */
        val netFiles = listOf("address", "mtu", "operstate")

        /** Our own subtree. Not AOSP's, and named so that a reader knows. */
        val ompAndroidFiles = listOf(
            "abi", "build_id", "device", "display", "gid", "manufacturer", "model",
            "release", "sdk_int", "uid",
        )
    }
}
