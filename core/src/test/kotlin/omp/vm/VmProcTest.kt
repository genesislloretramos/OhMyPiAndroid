package omp.vm

import omp.shell.fs.FsErrno
import omp.vm.proc.ProcBackend
import omp.shell.fs.VNodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `/proc` is generated per read, and every one of these tests exists to catch the failure mode that
 * matters: a file that was computed once at boot and never again. So each assertion that can be
 * stale is made twice, with the stub changed in between.
 */
class VmProcTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: VmHarness

    @Before
    fun setUp() {
        h = VmHarness(folder.root)
    }

    @Test
    fun meminfoFollowsThePlatformAndChangesWithIt() {
        h.services.memoryFacts = omp.shell.PlatformServices.MemoryInfo(4L * 1024 * 1024 * 1024, 3L * 1024 * 1024 * 1024)
        val first = h.text("/proc/meminfo")
        assertTrue(first, first.contains("MemTotal:         4194304 kB"))
        assertTrue(first, first.contains("MemAvailable:     3145728 kB"))
        assertTrue(first, first.contains("SwapTotal:"))

        // Through a command, because that is how it is read: free and cat must not disagree.
        assertTrue(h.stdout("cat /proc/meminfo").contains("MemTotal:         4194304 kB"))
        assertTrue(h.stdout("free").contains("4194304"))

        // Change the platform and the file changes with it. A cached /proc is worse than no /proc.
        h.services.memoryFacts = omp.shell.PlatformServices.MemoryInfo(1024L * 1024 * 1024, 512L * 1024 * 1024)
        val second = h.text("/proc/meminfo")
        assertTrue(second, second.contains("MemTotal:         1048576 kB"))
        assertTrue(second, second.contains("MemAvailable:      524288 kB"))
        assertTrue(h.stdout("cat /proc/meminfo").contains("MemTotal:         1048576 kB"))
    }

    @Test
    fun uptimeGrowsWithTheMonotonicClock() {
        h.stub.monotonic = 1_000L
        assertEquals("1.00 0.00\n", h.text("/proc/uptime"))
        h.stub.monotonic = 3_600_250L
        assertEquals("3600.25 0.00\n", h.text("/proc/uptime"))
        assertEquals("3600.25 0.00\n", h.stdout("cat /proc/uptime"))
        assertTrue(h.stdout("uptime").contains("up 1:00,"))
    }

    @Test
    fun everyProcessHasADirectoryUnderProc() {
        val process = h.kernel.processes.register(listOf("/usr/bin/sleep", "30"))
        val names = h.names("/proc")
        assertTrue("no pid dir: $names", names.contains(process.pid.toString()))
        assertTrue("no self: $names", names.contains("self"))
        assertTrue("no meminfo: $names", names.contains("meminfo"))
        assertTrue("no version: $names", names.contains("version"))

        assertEquals("/usr/bin/sleep 30\n", h.text("/proc/${process.pid}/cmdline"))
        assertEquals("sleep\n", h.text("/proc/${process.pid}/comm"))

        val status = h.text("/proc/${process.pid}/status")
        assertTrue(status, status.contains("Name:\tsleep"))
        // The namespace's own user, not the phone's: the real one is in /sys/omp/android/uid.
        assertTrue(status, status.contains("Uid:\t1000\t1000\t1000\t1000"))
        assertTrue(status, status.contains("Gid:\t1000\t1000\t1000\t1000"))
        assertTrue(status, status.contains("State:\trunning (R)"))
    }

    @Test
    fun aReleasedProcessBecomesAZombieAndStaysListed() {
        val process = h.kernel.processes.register(listOf("cat"))
        h.kernel.processes.release(process.pid)
        val status = h.text("/proc/${process.pid}/status")
        assertTrue(status, status.contains("State:\tzombie (Z)"))
        assertTrue(h.stdout("ps").contains("Z "))
        // A zombie is still a directory: it is waiting to be reaped, and its files still answer.
        assertTrue(h.names("/proc").contains(process.pid.toString()))
        assertEquals("cat\n", h.text("/proc/${process.pid}/comm"))
    }

    @Test
    fun selfIsALinkToTheCurrentProcess() {
        val process = h.kernel.processes.register(listOf("df"))
        assertEquals(VNodeType.SYMLINK, h.kernel.vfs.stat("/proc/self").type)
        assertEquals("/proc/${process.pid}", String(h.kernel.vfs.readLink("/proc/self").toByteArray(), Charsets.UTF_8))
        // And everything under it is that process's.
        assertEquals("df\n", h.text("/proc/self/comm"))
        assertEquals(VNodeType.FILE, h.kernel.vfs.stat("/proc/self/oom_score_adj").type)
        assertEquals("0\n", h.text("/proc/self/oom_score_adj"))
        assertEquals("0::/omp.test\n", h.text("/proc/self/cgroup"))
    }

    @Test
    fun versionSaysOutLoudlyWhatThisIs() {
        val version = h.text("/proc/version")
        assertTrue(version, version.startsWith("Linux version 14-omp-vm"))
        assertTrue(version, version.contains("in-process userspace VM"))
        assertTrue(version, version.contains("Pixel Stub"))
        assertTrue(version, version.contains("no kernel"))
    }

    @Test
    fun unameAndHostnameAndCmdlineAgree() {
        val uname = h.text("/proc/uname").trimEnd('\n').split(ProcBackend.SEPARATOR)
        assertEquals("Linux", uname[0])
        assertEquals("stub device", uname[1])
        assertEquals("14-omp-vm", uname[2])
        // The Debian name for the host ABI, from the one mapping: ro.product.cpu.abi is
        // arm64-v8a and Debian calls that aarch64.
        assertEquals("aarch64", uname[4])

        assertEquals("stub device\n", h.text("/proc/hostname"))
        assertEquals("Linux stub device 14-omp-vm #1 SMP in-process-userspace-vm aarch64 localhost\n", h.stdout("uname -a"))
        assertEquals("aarch64\n", h.stdout("uname -m"))
        assertTrue(h.text("/proc/cmdline").contains("--omp-vm"))
    }

    @Test
    fun mountsIsTheMountTable() {
        val text = h.text("/proc/mounts")
        for (mount in h.kernel.mountTable()) {
            assertTrue(text, text.contains("${mount.source} ${mount.target} ${mount.fstype} ${mount.options} 0 0"))
        }
    }

    @Test
    fun aProcFileReportsTheSizeItWouldHandYou() {
        val stat = h.kernel.vfs.stat("/proc/uptime")
        assertEquals(VNodeType.FILE, stat.type)
        // A proc file must report the size of the text it would hand you, and a non-zero one: a
        // generated file that reports nothing is a file no tool can seek in.
        assertTrue("a generated file cannot report no size", stat.size > 0)
        val rendered = h.text("/proc/uptime")
        assertEquals("the reported size must be the text's own length", rendered.length.toLong(), stat.size)
        assertEquals(0x124, stat.mode)
        assertTrue(stat.readable)
        assertEquals(false, stat.writable)
    }

    @Test
    fun theGeneratedTreeIsReadOnlyIncludingItsDirectories() {
        assertEquals(FsErrno.READ_ONLY, h.errnoOf { h.kernel.vfs.mkdir("/proc/1234") })
        assertEquals(FsErrno.READ_ONLY, h.errnoOf { h.kernel.vfs.symlink("/proc/version", "/proc/link") })
        // A directory is still a directory to `readDir`, and a file is still not one.
        assertEquals(FsErrno.NOT_A_DIRECTORY, h.errnoOf { h.kernel.vfs.readDir("/proc/version") })
        assertEquals(FsErrno.IS_A_DIRECTORY, h.errnoOf { h.kernel.vfs.readBytes("/proc/sys") })
        assertEquals(FsErrno.NO_SUCH_FILE, h.errnoOf { h.kernel.vfs.readBytes("/proc/nothing-here") })
        assertEquals(FsErrno.INVALID_ARGUMENT, h.errnoOf { h.kernel.vfs.readLink("/proc/version") })
    }

    @Test
    fun cpuinfoAndLoadavgSayWhatTheyUsed() {
        // One core, because PlatformServices has no way to count them, and the file says so.
        val one = h.text("/proc/cpuinfo")
        assertTrue(one, one.contains("processor\t: 0"))
        assertTrue(one, one.contains("cpu cores\t: 1"))
        assertEquals(false, one.contains("processor\t: 1"))

        // One core by default, and a build property turns the lie into the truth.
        h.stub.props["ro.config.cpu_count"] = "4"
        val four = h.text("/proc/cpuinfo")
        assertTrue(four, four.contains("processor\t: 3"))
        assertTrue(four, four.contains("cpu cores\t: 4"))
        assertTrue(h.text("/proc/stat").contains("cpu3 "))

        val load = h.text("/proc/loadavg").trim().split(" ")
        assertEquals(listOf("0.00", "0.00", "0.00"), load.take(3))
        assertTrue(load[3].contains("/"))
    }

    @Test
    fun theKernelIdentifiesItselfAndItsRandomUuidIsStable() {
        h.stub.props["ro.product.model"] = "Pixel Stub"
        val first = h.text("/proc/sys/kernel/random/uuid").trim()
        assertEquals(36, first.length)
        assertEquals(first, h.text("/proc/sys/kernel/random/uuid").trim())
        assertEquals("Linux\n", h.text("/proc/sys/kernel/ostype"))
        assertEquals("stub device\n", h.text("/proc/sys/kernel/hostname"))
        // It is derived from the app's own identity, so a different identity is a different id.
        h.stub.props["ro.build.fingerprint"] = "google/stub/stub:15/other:1:user/release-keys"
        assertTrue(first != h.text("/proc/sys/kernel/random/uuid").trim())
    }
}
