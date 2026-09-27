package omp.vm.proc

import omp.shell.PlatformServices
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.VEntry
import omp.shell.fs.VDiskUsage
import omp.shell.fs.VNodeType
import omp.shell.fs.VStat
import omp.shell.fs.Vfs
import omp.vm.MountInfo
import omp.vm.VmArch
import omp.vm.VmHost
import omp.vm.VmProcess
import omp.vm.VmProcessTable
import omp.vm.VmUsers
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * `/proc` for a userspace VM: generated per read, never cached, and read-only.
 *
 * Everything here is computed from [PlatformServices] and the VM's own [VmProcessTable] at the
 * moment the file is read, so a file cannot go stale — which is the one thing a real `/proc` never
 * does and the one thing a generated one has to work hardest at. Nothing is ever written: every
 * mutating method is [FsErrno.READ_ONLY], and a `writeBytes("/proc/...")` has to fail at the seam
 * rather than inside a string builder.
 *
 * Where a number cannot be read from an app, the file either omits it or says the value it used
 * and why. `/proc/meminfo` therefore has five lines and not forty, `/proc/uptime`'s idle field is
 * `0.00`, and `/proc/version` says in the first line that this is an in-process userspace VM and
 * names the Android host it is running on. A generated file that pretended to be the kernel's would
 * be worse than no file.
 *
 * Assumes it is mounted at `/proc`, the way the kernel's does: `/proc/self` and the `cgroup` paths
 * it prints are written out in full because a caller cannot add the prefix for us.
 */
class ProcBackend(
    private val services: PlatformServices,
    private val processes: VmProcessTable,
    private val mounts: () -> List<MountInfo>,
    private val users: () -> VmUsers?,
) : Vfs {

    /** `USER_HZ` is 100 on every Linux an app runs on, so a jiffy is this many milliseconds. */
    private val millisPerJiffy = 10L

    /** Files generated at the root of `/proc`, in the order `ls` is expected to show them. */
    private val rootFiles = listOf(
        "cmdline", "cpuinfo", "devices", "filesystems", "hostname", "loadavg",
        "meminfo", "mounts", "stat", "uname", "uptime", "version",
    )

    /** The five files every pid has, plus the three that are per-process in Linux too. */
    private val pidFiles = listOf(
        "cmdline", "comm", "cgroup", "environ", "exe", "oom_score_adj", "stat", "status",
    )

    // ---- reading ---------------------------------------------------------------------

    override fun readDir(path: String): List<VEntry> {
        val p = trim(path)
        val names = dirNames(p)
        if (names == null) {
            if (exists(p)) throw FsException(FsErrno.NOT_A_DIRECTORY, path)
            throw FsException(FsErrno.NO_SUCH_FILE, path)
        }
        val stamp = stamp()
        return names.map { VEntry(it, statIn(p, it, stamp)) }
    }

    override fun stat(path: String): VStat {
        val p = derefSelf(trim(path))
        if (p == "/self") return selfLinkStat()
        if (dirNames(p) != null) return dirStat()
        val text = content(p) ?: throw FsException(FsErrno.NO_SUCH_FILE, path)
        return VStat(
            type = VNodeType.FILE,
            size = text.length.toLong(),
            mtimeMillis = stamp(),
            mode = FILE_MODE,
            readable = true,
            writable = false,
        )
    }

    override fun openRead(path: String): InputStream {
        val p = derefSelf(trim(path))
        if (p == "/self") throw FsException(FsErrno.IS_A_DIRECTORY, path)
        if (dirNames(p) != null) throw FsException(FsErrno.IS_A_DIRECTORY, path)
        val text = content(p) ?: throw FsException(FsErrno.NO_SUCH_FILE, path)
        return ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
    }

    override fun readBytes(path: String): ByteArray {
        val p = derefSelf(trim(path))
        if (p == "/self" || dirNames(p) != null) throw FsException(FsErrno.IS_A_DIRECTORY, path)
        val text = content(p) ?: throw FsException(FsErrno.NO_SUCH_FILE, path)
        return text.toByteArray(Charsets.UTF_8)
    }

    // /proc is generated from the truth, not from a keystore: there is no state here to write.
    override fun openWrite(path: String, append: Boolean): OutputStream = throw FsException(FsErrno.READ_ONLY, path)
    override fun writeBytes(path: String, bytes: ByteArray): Unit = throw FsException(FsErrno.READ_ONLY, path)
    override fun createFile(path: String): Unit = throw FsException(FsErrno.READ_ONLY, path)
    override fun mkdir(path: String): Unit = throw FsException(FsErrno.READ_ONLY, path)
    override fun delete(path: String): Unit = throw FsException(FsErrno.READ_ONLY, path)
    override fun rename(from: String, to: String): Unit = throw FsException(FsErrno.READ_ONLY, from)
    override fun symlink(target: String, link: String): Unit = throw FsException(FsErrno.READ_ONLY, link)
    override fun setModified(path: String, millis: Long): Unit = throw FsException(FsErrno.READ_ONLY, path)

    override fun rmdir(path: String): Unit = throw FsException(FsErrno.READ_ONLY, path)

    override fun readLink(path: String): String {
        val p = trim(path)
        if (p == "/self") return selfTarget()
        if (dirNames(p) != null) throw FsException(FsErrno.INVALID_ARGUMENT, path)
        if (exeTarget() != null && p.substringAfterLast('/') == "exe" && pidOf(p.substringBeforeLast('/')) != null) {
            return exeTarget()!!
        }
        throw FsException(FsErrno.INVALID_ARGUMENT, path)
    }

    override fun realpath(path: String): String = "/proc" + derefSelf(trim(path))

    override fun diskUsage(path: String): VDiskUsage = VDiskUsage(0L, 0L)

    // ---- shape ------------------------------------------------------------------------

    /** `""` and `/` are the same directory; a path here is always absolute. */
    private fun trim(path: String): String {
        if (path.isEmpty()) return "/"
        val trimmed = if (path.length > 1) path.trimEnd('/') else path
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }

    private fun selfPid(): Int = processes.current()?.pid ?: VmProcessTable.INIT_PID

    private fun selfTarget(): String = "/proc/${selfPid()}"

    /** `/proc/self` is a link to the current pid, as it is in the kernel; so is everything under it. */
    private fun derefSelf(path: String): String =
        if (path == "/self") "/self" else if (path.startsWith("/self/")) "/" + selfPid() + path.substring(5) else path

    private fun pidOf(path: String): Int? = path.trim('/').toIntOrNull()

    private fun pidFile(pid: Int, name: String): String = "/$pid/$name"

    /** @return the entry names of a directory in this filesystem, or null when it is not one. */
    private fun dirNames(path: String): List<String>? = when {
        path == "/" -> buildList {
            addAll(rootFiles)
            add("self")
            add("sys")
            addAll(processes.snapshot().map { it.pid.toString() })
        }
        path == "/sys" -> listOf("kernel")
        path == "/sys/kernel" -> listOf("hostname", "ostype", "random")
        path == "/sys/kernel/random" -> listOf("uuid")
        pidOf(path) != null && processes.of(pidOf(path)!!) != null -> pidFiles
        else -> null
    }

    private fun exists(path: String): Boolean = content(path) != null

    private fun dirStat(): VStat = VStat(VNodeType.DIRECTORY, 4096L, stamp(), DIR_MODE, true, false, true)

    private fun selfLinkStat(): VStat = VStat(
        VNodeType.SYMLINK,
        selfTarget().length.toLong(),
        stamp(),
        0x1FF,
        readable = true,
        writable = false,
        executable = true,
    )

    private fun statIn(dir: String, name: String, stamp: Long): VStat {
        val child = if (dir == "/") "/$name" else "$dir/$name"
        return try {
            stat(child).copy(mtimeMillis = stamp)
        } catch (e: FsException) {
            VStat(VNodeType.FILE, 0L, stamp, FILE_MODE, true, false, false)
        }
    }

    /** The host's boot instant: the best honest mtime for a file this VM just made up. */
    private fun stamp(): Long = services.wallClockMillis() - services.monotonicMillis()

    private fun hostname(): String = services.deviceName() ?: VmHost.model(services)

    private fun release(): String =
        VmHost.prop(services, "ro.build.version.release", "unknown") + "-omp-vm"

    private fun abi(): String =
        // The Debian name, from the one mapping, so `uname -m` and the boot line cannot disagree.
        VmArch.of(services)

    // ---- content ---------------------------------------------------------------------

    /** @return the file's text, generated now, or null when there is no such file here. */
    private fun content(path: String): String? = when {
        path == "/" -> null
        path == "/version" ->
            // Said in the first line, on purpose: a user who pastes this into a bug report should
            // learn from it that there is no kernel here.
            "Linux version ${release()} (omp@android) (in-process userspace VM, no kernel, " +
                "no root; Android host: ${VmHost.model(services)}) #1 SMP\n"
        path == "/hostname" -> hostname() + "\n"
        path == "/uptime" -> String.format(Locale.US, "%.2f %.2f\n", services.monotonicMillis() / 1000.0, 0.0)
        path == "/meminfo" -> meminfo()
        path == "/stat" -> stat()
        path == "/cpuinfo" -> cpuinfo()
        path == "/loadavg" -> loadavg()
        path == "/filesystems" -> FILESYSTEMS
        path == "/devices" -> DEVICES
        path == "/mounts" -> mountText()
        path == "/cmdline" -> "${services.processName()} --omp-vm --root=/\n"
        path == "/sys/kernel/ostype" -> "Linux\n"
        path == "/sys/kernel/hostname" -> hostname() + "\n"
        path == "/sys/kernel/random/uuid" -> VmHost.uuid(services) + "\n"
        // NUL-separated, as the kernel's is, and deliberately not space-separated: a device name
        // may contain a space, and a field that can be split in the wrong place is not a field.
        path == "/uname" -> listOf(
            "Linux", hostname(), release(), "#1 SMP in-process-userspace-vm", abi(), "localhost",
        ).joinToString(SEPARATOR) + "\n"
        else -> pidContent(path)
    }

    private fun pidContent(path: String): String? {
        val pid = pidOf(path.substringBeforeLast('/', ""))
        val name = path.substringAfterLast('/')
        val process = pid?.let { processes.of(it) } ?: return null
        if (name == "comm") return process.comm + "\n"
        if (name == "cmdline") return process.argv.joinToString(" ") + "\n"
        if (name == "stat") return processStat(process)
        if (name == "status") return processStatus(process)
        if (name == "environ") return environ()
        if (name == "cgroup") return "0::/${services.processName()}\n"
        if (name == "exe") return exeTarget() ?: "(the VM has no executable image)\n"
        if (name == "oom_score_adj") return "0\n"
        return null
    }

    /**
     * The five lines this VM can answer honestly. `MemFree` is reported as the framework's
     * available figure, because Android does not expose a real one and a wrong-but-plausible free
     * figure would be read as a fact; the buffers and cache lines a tool expects are left out
     * rather than filled with zeros.
     */
    private fun meminfo(): String {
        val mem = services.systemMemory()
        val totalKb = mem.totalBytes / 1024
        val availKb = mem.availableBytes / 1024
        return buildString {
            appendLine("MemTotal:       ${totalKb.toString().padStart(9)} kB")
            appendLine("MemFree:        ${availKb.toString().padStart(9)} kB")
            appendLine("MemAvailable:   ${availKb.toString().padStart(9)} kB")
            // No swap is visible to an app; the kernel's own number is not readable, and claiming a
            // swap device the app cannot see would be the kind of detail that turns into a bug report.
            appendLine("SwapTotal:      ${0L.toString().padStart(9)} kB")
            appendLine("SwapFree:       ${0L.toString().padStart(9)} kB")
        }
    }

    /**
     * Jiffies, and the only conversion between the two clocks.
     *
     * `USER_HZ` is 100 on every Linux an app runs on, so a jiffy is 10 ms. Everything this backend
     * writes into a jiffy field goes through here: the platform reports milliseconds
     * (`SystemClock.elapsedRealtime()`, `Process.getElapsedCpuTime()`) and `/proc/stat` counts
     * jiffies, and subtracting one from the other is how a `/proc/stat` ends up claiming the machine
     * spent more user time than has elapsed.
     */
    private fun jiffies(millis: Long): Long = millis / millisPerJiffy

    /** Jiffies are derived from the app's own monotonic clock, which is the only counter there is. */
    private fun stat(): String {
        val jiffies = jiffies(services.monotonicMillis())
        val busy = jiffies(processes.hostCpuTimes()?.first ?: 0L)
        return buildString {
            appendLine("cpu  $busy 0 0 ${(jiffies - busy).coerceAtLeast(0)} 0 0 0 0 0 0")
            for (cpu in 0 until VmHost.cpuCount(services)) {
                appendLine("cpu$cpu $busy 0 0 ${(jiffies - busy).coerceAtLeast(0)} 0 0 0 0 0 0")
            }
            appendLine("intr 0")
            appendLine("ctxt ${processes.size()}")
            appendLine("btime ${(services.wallClockMillis() - services.monotonicMillis()) / 1000}")
            appendLine("processes ${processes.size()}")
            appendLine("procs_running ${processes.snapshot().count { it.state == 'R' }}")
            appendLine("procs_blocked 0")
        }
    }

    private fun cpuinfo(): String = buildString {
        val model = VmHost.prop(services, "ro.board.platform", VmHost.model(services))
        for (cpu in 0 until VmHost.cpuCount(services)) {
            appendLine("processor\t: $cpu")
            appendLine("model name\t: $model")
            appendLine("BogoMIPS\t: 0.00")
            appendLine("Features\t: omp-vm userspace")
            appendLine()
        }
        appendLine("cpu cores\t: ${VmHost.cpuCount(services)}")
    }

    /**
     * The load averages are `0.00` because an app cannot read the kernel's run-queue length, and
     * inventing one would be the single most believed line in the whole tree. The `running/total`
     * field is the VM's own process table, which is the truth.
     */
    private fun loadavg(): String {
        val last = processes.snapshot().maxOfOrNull { it.pid } ?: VmProcessTable.INIT_PID
        return "0.00 0.00 0.00 ${processes.loadAvgCounts()} $last\n"
    }

    private fun mountText(): String = mounts().joinToString("") { m ->
        // The last two fields are the dump count and fsck order; both zero, as they are on a phone.
        "${m.source} ${m.target} ${m.fstype} ${m.options} 0 0\n"
    }

    /** The classic 52-field line, truncated where this VM has nothing true to put. */
    private fun processStat(p: VmProcess): String {
        val jiffies = p.cpuMillis / 10
        val startTicks = p.startMillis / 10
        return "${p.pid} (${p.comm}) ${p.state} ${p.ppid} ${p.ppid} ${p.ppid} 0 -1 " +
            "4194304 $jiffies 0 0 0 0 $jiffies 0 0 20 0 1 0 $startTicks 0 0 0 0 0 0 0 0 0 0\n"
    }

    /**
     * `Uid:` and `Gid:` are the VM user's, which is what `ps` and `id` print inside the namespace.
     * The app's real uid is in `/sys/omp/android/uid`, and pretending the VM's answer is the
     * phone's would be the one lie in this file worth avoiding.
     */
    private fun processStatus(p: VmProcess): String {
        val user = users()?.current()
        val uid = user?.uid ?: 0
        val gid = user?.gid ?: 0
        val name = user?.name ?: "root"
        return buildString {
            appendLine("Name:\t${p.comm}")
            appendLine("Umask:\t0022")
            appendLine("State:\t${stateWord(p.state)} (${p.state})")
            appendLine("Tgid:\t${p.pid}")
            appendLine("Ngid:\t0")
            appendLine("Pid:\t${p.pid}")
            appendLine("PPid:\t${p.ppid}")
            appendLine("TracerPid:\t0")
            appendLine("Uid:\t$uid\t$uid\t$uid\t$uid")
            appendLine("Gid:\t$gid\t$gid\t$gid\t$gid")
            appendLine("FDSize:\t64")
            appendLine("Groups:\t$gid")
            appendLine("VmPeak:\t0 kB")
            appendLine("VmSize:\t0 kB")
            appendLine("VmRSS:\t0 kB")
            appendLine("VmData:\t0 kB")
            appendLine("VmStk:\t0 kB")
            appendLine("VmExe:\t0 kB")
            appendLine("VmLib:\t0 kB")
            appendLine("VmPTE:\t0 kB")
            appendLine("VmSwap:\t0 kB")
            appendLine("Threads:\t1")
            appendLine("SigQ:\t0/4096")
            appendLine("SigPnd:\t0000000000000000")
            appendLine("ShdPnd:\t0000000000000000")
            appendLine("SigBlk:\t0000000000000000")
            appendLine("SigIgn:\t0000000000000000")
            appendLine("SigCgt:\t0000000000000000")
            appendLine("NSpid:\t${p.pid}")
            appendLine("Speculation_Store_Bypass:\tthread vulnerable")
            appendLine("Cpus_allowed:\t${"f".repeat(VmHost.cpuCount(services))}")
            appendLine("Mems_allowed:\t1")
            appendLine("voluntary_ctxt_switches:\t0")
            appendLine("nonvoluntary_ctxt_switches:\t0")
        }
    }

    private fun environ(): String {
        val user = users()?.current()
        return buildString {
            appendLine("HOME=${user?.home ?: "/root"}")
            appendLine("USER=${user?.name ?: "root"}")
            appendLine("LOGNAME=${user?.name ?: "root"}")
            appendLine("SHELL=/usr/bin/sh")
            appendLine("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            appendLine("TERM=xterm-256color")
            appendLine("OMP_VM=1")
        }
    }

    /**
     * `/proc/<pid>/exe` on a phone is the APK: there is no separate executable image, and a link
     * to it is more honest than a path to a file that does not exist.
     */
    private fun exeTarget(): String? = services.packagePaths(services.processName()).firstOrNull()

    private fun stateWord(state: Char): String = when (state) {
        'R' -> "running"
        'S' -> "sleeping"
        'Z' -> "zombie"
        else -> "unknown"
    }

    companion object {
        /** The NUL the kernel writes between `/proc/uname` fields; a space would be ambiguous. */
        const val SEPARATOR = "\u0000"

        /** `/proc` files are 0444 to everyone, which is what `ls -l` shows in the kernel's tree. */
        const val FILE_MODE = 0x124

        /** 0555: searchable, not writable, on a filesystem that has no owner. */
        const val DIR_MODE = 0x155

        /** What the host actually has mounted where this app can see it, plus what this VM adds. */
        private val FILESYSTEMS = buildString {
            appendLine("nodev\tsysfs")
            appendLine("nodev\tproc")
            appendLine("nodev\ttmpfs")
            appendLine("\text4")
            appendLine("\t9p")
            appendLine("\tfuse")
            appendLine("nodev\tdevtmpfs")
        }

        private val DEVICES = buildString {
            appendLine("Character devices:")
            appendLine("  1 mem")
            appendLine("  4 /dev/vc/0")
            appendLine("  5 /dev/tty")
            appendLine("  5 /dev/console")
            appendLine("  7 /dev/full")
            appendLine("  7 /dev/null")
            appendLine("  7 /dev/random")
            appendLine("  7 /dev/urandom")
            appendLine("  7 /dev/zero")
            appendLine(" 10 misc")
            appendLine("Block devices:")
            appendLine("  7 loop")
            appendLine("  8 sd")
            appendLine("179 mmcblk")
        }
    }
}
