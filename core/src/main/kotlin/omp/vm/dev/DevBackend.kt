package omp.vm.dev

import omp.shell.PlatformServices
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.VEntry
import omp.shell.fs.VDiskUsage
import omp.shell.fs.VNodeType
import omp.shell.fs.VStat
import omp.shell.fs.Vfs
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Arrays

/**
 * `/dev` for a userspace VM: eight nodes, no `mknod`, and no kernel.
 *
 * Each one is the behaviour a program expects, not a file: `/dev/null` swallows writes and reads
 * empty, `/dev/zero` produces zeros *as they are asked for* (a preallocated buffer would be the
 * one thing it must never be — `head -c 1G /dev/zero` is a legitimate command), `/dev/full` fails
 * every write with [FsErrno.NO_SPACE] the way the real node does, and `/dev/urandom` streams
 * [SecureRandom] output. `/dev/omp-host` is the one node that is not a Unix device at all: it
 * prints the [PlatformServices] facts the bridge exposes, and its first line says so.
 *
 * Creating a node here is [FsErrno.READ_ONLY]. There is no `mknod`, no device major and no kernel
 * to ask, so a `touch /dev/evil` is refused instead of leaving a file that behaves like a device.
 */
class DevBackend(private val services: PlatformServices) : Vfs {

    private val random = SecureRandom()

    override fun readDir(path: String): List<VEntry> {
        requireRoot(path)
        return NODES.map { name ->
            VEntry(name, VStat(VNodeType.DEVICE, 0L, 0L, modeOf(name), readable = true, writable = writable(name)))
        }
    }

    override fun stat(path: String): VStat {
        val name = node(path)
        return VStat(VNodeType.DEVICE, 0L, 0L, modeOf(name), readable = true, writable = writable(name))
    }

    override fun openRead(path: String): InputStream = when (val name = node(path)) {
        "null" -> ByteArrayInputStream(EMPTY)
        "zero", "full" -> ZeroStream()
        "random", "urandom" -> RandomStream(random)
        // A terminal is not a file, and this bridge has no terminal to read from. `cat /dev/tty`
        // has to say so rather than hang.
        "console", "tty" -> throw FsException(FsErrno.INVALID_ARGUMENT, path)
        "omp-host" -> ByteArrayInputStream(hostFacts().toByteArray(Charsets.UTF_8))
        else -> throw FsException(FsErrno.NO_SUCH_FILE, path)
    }

    override fun openWrite(path: String, append: Boolean): OutputStream = when (val name = node(path)) {
        "null", "zero", "console", "tty" -> DiscardOutput
        "full" -> FullOutput
        "random", "urandom", "omp-host" -> throw FsException(FsErrno.READ_ONLY, path)
        else -> throw FsException(FsErrno.NO_SUCH_FILE, path)
    }

    override fun readBytes(path: String): ByteArray = openRead(path).use { it.readBytes() }

    override fun writeBytes(path: String, bytes: ByteArray) {
        openWrite(path, false).use { it.write(bytes) }
    }

    // There is nothing to create, remove or stamp: a device node is a fact about the kernel, and
    // this one has no kernel to ask.
    override fun createFile(path: String): Unit = throw FsException(FsErrno.READ_ONLY, path)
    override fun mkdir(path: String): Unit = throw FsException(FsErrno.READ_ONLY, path)
    override fun delete(path: String): Unit = throw FsException(FsErrno.READ_ONLY, path)
    override fun rename(from: String, to: String): Unit = throw FsException(FsErrno.READ_ONLY, from)
    override fun symlink(target: String, link: String): Unit = throw FsException(FsErrno.READ_ONLY, link)
    override fun setModified(path: String, millis: Long): Unit = throw FsException(FsErrno.READ_ONLY, path)
    override fun rmdir(path: String): Unit = throw FsException(FsErrno.READ_ONLY, path)

    override fun readLink(path: String): String = throw FsException(FsErrno.INVALID_ARGUMENT, path)

    override fun realpath(path: String): String = if (path.isEmpty() || path == "/") "/" else path

    override fun diskUsage(path: String): VDiskUsage = VDiskUsage(0L, 0L)

    /** @return the device name for a path in this filesystem, rejecting everything else. */
    private fun node(path: String): String {
        val name = if (path.length > 1) path.trimEnd('/').substring(1) else ""
        if (name.isEmpty() || name.contains('/') || name !in NODES) throw FsException(FsErrno.NO_SUCH_FILE, path)
        return name
    }

    private fun requireRoot(path: String) {
        if (path.isNotEmpty() && path != "/") throw FsException(FsErrno.NOT_A_DIRECTORY, path)
    }

    /** 0444 for the nodes you may only read, 0666 for the rest — which is what the real tree has. */
    private fun modeOf(name: String): Int = if (READ_ONLY_NODES.contains(name)) 0x1A4 else 0x1B6

    private fun writable(name: String): Boolean = !READ_ONLY_NODES.contains(name)

    /**
     * Everything the bridge knows, read at this moment. Nothing is cached, so a file read after the
     * user revokes all-files access says so — the point of the whole node is that it cannot go
     * quietly stale.
     */
    private fun hostFacts(): String = buildString {
        appendLine("# /dev/omp-host: the escape hatch from this userspace VM to the Android host.")
        appendLine("# Read through PlatformServices as you read this file; it cannot be written, and")
        appendLine("# it grants nothing: every value below is the app's own view of its own device.")
        appendLine("host.app.process=${services.processName()}")
        appendLine("host.app.pid=${services.processPid()}")
        appendLine("host.app.uid=${services.appUid()}")
        appendLine("host.app.gid=${services.appGid()}")
        appendLine("host.app.home=${services.homeDir()}")
        appendLine("host.app.initial_directory=${services.initialDirectory()}")
        appendLine("host.app.external_storage=${services.externalStorageDir() ?: "(none)"}")
        appendLine("host.app.all_files_access=${services.isExternalStorageManager()}")
        appendLine("host.clock.wall_millis=${services.wallClockMillis()}")
        appendLine("host.clock.monotonic_millis=${services.monotonicMillis()}")
        appendLine("host.clock.timezone=${services.timeZoneId()}")
        appendLine("host.locale=${services.locale()}")
        appendLine("host.device.name=${services.deviceName() ?: "(unset)"}")
        for ((key, value) in services.buildProperties().entries.sortedBy { it.key }) {
            appendLine("host.build.$key=$value")
        }
        for ((key, value) in services.systemPropertyOverrides().entries.sortedBy { it.key }) {
            appendLine("host.property.$key=$value")
        }
        appendLine("host.display.name=${services.displayName()}")
        appendLine("host.display.width_px=${services.displayWidthPx()}")
        appendLine("host.display.height_px=${services.displayHeightPx()}")
        appendLine("host.display.density_dpi=${services.displayDensityDpi()}")
        appendLine("host.display.refresh_hz=${services.displayRefreshRateHz()}")
        val mem = services.systemMemory()
        appendLine("host.memory.total_bytes=${mem.totalBytes}")
        appendLine("host.memory.available_bytes=${mem.availableBytes}")
        appendLine("host.process.pss_kb=${services.processPssKb()}")
        services.processCpuTimes()?.let { (cpu, since) ->
            appendLine("host.process.cpu_millis=$cpu")
            appendLine("host.process.since_boot_millis=$since")
        }
        val battery = services.batteryInfo()
        appendLine("host.battery.level=${battery.level}")
        appendLine("host.battery.scale=${battery.scale}")
        appendLine("host.battery.status=${battery.status}")
        appendLine("host.battery.health=${battery.health}")
        appendLine("host.battery.plugged=${battery.plugged}")
        appendLine("host.battery.temperature_tenths_c=${battery.temperatureTenthsC}")
        appendLine("host.battery.voltage_mv=${battery.voltageMv}")
        appendLine("host.battery.current_ma=${battery.currentMa}")
        appendLine("host.battery.charge_counter_uah=${battery.chargeCounterUah}")
        appendLine("host.battery.technology=${battery.technology}")
        for (iface in services.networkInterfaces()) {
            appendLine("host.net.${iface.name}.addresses=${iface.addresses.joinToString(",")}")
            appendLine("host.net.${iface.name}.flags=${iface.flags.joinToString(",")}")
            appendLine("host.net.${iface.name}.mac=${iface.mac ?: "(none)"}")
        }
        services.activeRoute()?.let { route ->
            appendLine("host.route.interface=${route.interfaceName}")
            appendLine("host.route.addresses=${route.addresses.joinToString(",")}")
            appendLine("host.route.dns=${route.dnsServers.joinToString(",")}")
        }
        for (volume in services.storageVolumes()) {
            appendLine("host.storage.${volume.mountPoint}.state=${volume.state}")
            appendLine("host.storage.${volume.mountPoint}.total_bytes=${volume.totalBytes}")
            appendLine("host.storage.${volume.mountPoint}.usable_bytes=${volume.usableBytes}")
            appendLine("host.storage.${volume.mountPoint}.primary=${volume.primary}")
        }
    }

    /** Endless zeros, produced as they are asked for; the limit is only here for a bounded test. */
    private class ZeroStream(private val limit: Long = Long.MAX_VALUE) : InputStream() {
        private var left = limit

        override fun read(): Int {
            if (left <= 0) return -1
            left--
            return 0
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (left <= 0) return -1
            val n = minOf(len.toLong(), left).toInt()
            Arrays.fill(b, off, off + n, 0.toByte())
            left -= n
            return n
        }
    }

    /** Random bytes, streamed. One 4 kB scratch per stream, reused, so a read allocates nothing. */
    private class RandomStream(private val random: SecureRandom) : InputStream() {
        private val scratch = ByteArray(4096)

        override fun read(): Int = random.nextInt(256)

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (off == 0 && len == b.size) {
                random.nextBytes(b)
                return len
            }
            val n = minOf(len, scratch.size)
            random.nextBytes(scratch)
            System.arraycopy(scratch, 0, b, off, n)
            return n
        }
    }

    /** Writes that go nowhere, and cost nothing: `/dev/null` and a terminal this bridge has not got. */
    private object DiscardOutput : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    /**
     * `/dev/full`: the open succeeds, the write does not. That is the real behaviour, and it is
     * also the only way `echo x > /dev/full` can report the failure at the moment the bytes are
     * lost rather than at the moment the file is named.
     */
    private object FullOutput : OutputStream() {
        override fun write(b: Int): Unit = throw FsException(FsErrno.NO_SPACE, "/dev/full")
        override fun write(b: ByteArray, off: Int, len: Int): Unit = throw FsException(FsErrno.NO_SPACE, "/dev/full")
        override fun flush() {}
    }

    companion object {
        val NODES = listOf("null", "zero", "full", "random", "urandom", "console", "tty", "omp-host")
        private val READ_ONLY_NODES = setOf("random", "urandom", "omp-host")
        private val EMPTY = ByteArray(0)
    }
}
