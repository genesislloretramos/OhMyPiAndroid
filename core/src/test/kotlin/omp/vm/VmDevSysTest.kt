package omp.vm

import omp.shell.PlatformServices
import omp.shell.fs.FsErrno
import omp.shell.fs.VNodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The device and sysfs trees. These are the files a user reaches for when they want to know whether
 * the VM is a toy: `/dev/zero` has to be a real stream and `/dev/full` has to fail like the real
 * one, and the battery numbers have to be the platform's in the units a sysfs reader expects.
 */
class VmDevSysTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: VmHarness

    @Before
    fun setUp() {
        h = VmHarness(folder.root)
    }

    @Test
    fun zeroStreamsAndStopsWhenSomethingStopsReadingIt() {
        // Driven through the shell, because the point of /dev/zero is that a *pipeline* can read it:
        // an endless stream is only useful if something bounds it.
        val counted = h.run("head -c 10 /dev/zero | wc -c")
        assertEquals(counted.err, 0, counted.status)
        assertEquals("10\n", counted.out)

        // And the bytes really are zeros, not a buffer of whatever was there before.
        h.run("sh -c 'head -c 4 /dev/zero > /tmp/zeros'")
        assertEquals(listOf(0, 0, 0, 0), File(folder.root, "vm/tmp/zeros").readBytes().map { it.toInt() })
    }

    @Test
    fun fullTakesTheWriteAndLosesTheBytes() {
        val result = h.run("sh -c 'echo x > /dev/full'")
        assertEquals(1, result.status)
        assertEquals("sh: /dev/full: No space left on device\n", result.err)
        // Nothing landed anywhere.
        assertEquals(FsErrno.NO_SUCH_FILE, h.errnoOf { h.kernel.vfs.readBytes("/dev/full/scratch") })
    }

    @Test
    fun nullSwallowsEverythingAndSaysNothing() {
        val result = h.run("sh -c 'echo hi > /dev/null'")
        assertEquals(0, result.status)
        assertEquals("", result.out)
        assertEquals(0, h.text("/dev/null").length)
        // A second write to null still succeeds: there is no state to fill up.
        assertEquals(0, h.run("sh -c 'echo again > /dev/null'").status)
    }

    @Test
    fun randomStreamsBytesRatherThanRefusing() {
        val counted = h.run("head -c 8 /dev/urandom | wc -c")
        assertEquals(counted.err, 0, counted.status)
        assertEquals("8\n", counted.out)
        h.run("sh -c 'head -c 32 /dev/random > /tmp/r'")
        assertEquals(32, File(folder.root, "vm/tmp/r").length())
    }

    @Test
    fun aTerminalIsNotAFileThisBridgeCanRead() {
        assertEquals(FsErrno.INVALID_ARGUMENT, h.errnoOf { h.kernel.vfs.readBytes("/dev/tty") })
        assertEquals(FsErrno.INVALID_ARGUMENT, h.errnoOf { h.kernel.vfs.readBytes("/dev/console") })
        val result = h.run("cat /dev/tty")
        assertEquals(1, result.status)
        assertTrue(result.err, result.err.contains("Invalid argument"))
        // It takes a write, though: that is the direction a shell actually uses.
        assertEquals(0, h.run("sh -c 'echo hi > /dev/console'").status)
    }

    @Test
    fun ompHostIsTheEscapeHatchAndSaysSo() {
        val text = h.text("/dev/omp-host")
        val lines = text.lines()
        assertTrue(lines[0], lines[0].startsWith("# /dev/omp-host"))
        assertTrue(text, text.contains("host.app.uid=10123"))
        assertTrue(text, text.contains("host.app.pid=4242"))
        assertTrue(text, text.contains("host.memory.total_bytes=${h.services.systemMemory().totalBytes}"))
        assertTrue(text, text.contains("host.battery.level=50"))
        assertTrue(text, text.contains("host.build.ro.product.model=Pixel Stub"))
        assertTrue(text, text.contains("host.app.all_files_access=true"))

        // Live like everything else in here: change the platform, change the file.
        h.services.uidFacts = 4242
        assertTrue(h.text("/dev/omp-host").contains("host.app.uid=4242"))
    }

    @Test
    fun deviceNodesAreDevicesWithTheModesUnixGivesThem() {
        for (name in listOf("null", "zero", "full", "random", "urandom", "console", "tty", "omp-host")) {
            val stat = h.kernel.vfs.stat("/dev/$name")
            assertEquals(name, VNodeType.DEVICE, stat.type)
        }
        assertEquals(0x1B6, h.kernel.vfs.stat("/dev/null").mode)
        assertEquals(0x1B6, h.kernel.vfs.stat("/dev/zero").mode)
        assertEquals(0x1A4, h.kernel.vfs.stat("/dev/urandom").mode)
        assertEquals(0x1A4, h.kernel.vfs.stat("/dev/random").mode)
        assertEquals(false, h.kernel.vfs.stat("/dev/urandom").writable)
        assertTrue(h.kernel.vfs.stat("/dev/null").writable)
        assertEquals(
            listOf("console", "full", "null", "omp-host", "random", "tty", "urandom", "zero"),
            h.names("/dev"),
        )
    }

    @Test
    fun devHasNoMknod() {
        assertEquals(FsErrno.READ_ONLY, h.errnoOf { h.kernel.vfs.createFile("/dev/evil") })
        assertEquals(FsErrno.READ_ONLY, h.errnoOf { h.kernel.vfs.mkdir("/dev/thing") })
        assertEquals(FsErrno.READ_ONLY, h.errnoOf { h.kernel.vfs.openWrite("/dev/urandom", false) })
        assertEquals(FsErrno.NO_SUCH_FILE, h.errnoOf { h.kernel.vfs.stat("/dev/evil") })
    }

    @Test
    fun batteryCapacityIsAPercentageOfItsOwnScale() {
        h.services.batteryFacts = PlatformServices.BatteryInfo(50, 100, "2", "2", "0", 250, 4000, 0, 1000000, "Li-ion")
        assertEquals("50\n", h.text("/sys/class/power_supply/battery/capacity"))
        // A battery that reports 3 of 4 is 75 percent, and 3 is not the answer.
        h.services.batteryFacts = PlatformServices.BatteryInfo(3, 4, "3", "2", "0", 250, 4000, 0, 1000000, "Li-ion")
        assertEquals("75\n", h.text("/sys/class/power_supply/battery/capacity"))
    }

    @Test
    fun batteryNumbersAreInTheUnitsSysfsUses() {
        h.services.batteryFacts = PlatformServices.BatteryInfo(80, 100, "2", "2", "1", 315, 4321, 250, 1234567, "Li-ion")
        // 4321 mV is 4 321 000 microvolts, and 31.5 degrees is 31 500 millidegrees.
        assertEquals("4321000\n", h.text("/sys/class/power_supply/battery/voltage_now"))
        assertEquals("31500\n", h.text("/sys/class/power_supply/battery/temp"))
        assertEquals("1234567\n", h.text("/sys/class/power_supply/battery/charge_counter"))
        assertEquals("250\n", h.text("/sys/class/power_supply/battery/current_now"))
        // The framework's numeric enums, as the words a sysfs reader expects.
        assertEquals("Charging\n", h.text("/sys/class/power_supply/battery/status"))
        assertEquals("Good\n", h.text("/sys/class/power_supply/battery/health"))
        assertEquals("1\n", h.text("/sys/class/power_supply/battery/present"))
        assertEquals("Li-ion\n", h.text("/sys/class/power_supply/battery/technology"))
    }

    @Test
    fun networkInterfacesAreOneDirectoryEach() {
        h.stub.interfaces.add(PlatformServices.NetInterface("rmnet0", listOf("10.0.0.2/32"), listOf("UP"), "00:11:22:33:44:55"))
        h.stub.interfaces.add(PlatformServices.NetInterface("wlan0", listOf("192.168.1.5/24"), listOf("BROADCAST", "MULTICAST"), null))
        // Two interfaces, two directories, and a name that is not there is not invented.
        assertEquals(listOf("rmnet0", "wlan0"), h.names("/sys/class/net"))
        // wlan0 reports no MAC in this stub, and the kernel's answer for that is all zeroes.
        assertEquals("00:00:00:00:00:00\n", h.text("/sys/class/net/wlan0/address"))
        assertEquals("down\n", h.text("/sys/class/net/wlan0/operstate"))
        assertEquals("00:11:22:33:44:55\n", h.text("/sys/class/net/rmnet0/address"))
        assertEquals("up\n", h.text("/sys/class/net/rmnet0/operstate"))
        assertEquals("1500\n", h.text("/sys/class/net/rmnet0/mtu"))
        assertEquals(FsErrno.NO_SUCH_FILE, h.errnoOf { h.kernel.vfs.readBytes("/sys/class/net/eth9/address") })
    }

    @Test
    fun cpusAreListedAndOursIsOurs() {
        assertEquals("0\n", h.text("/sys/devices/system/cpu/online"))
        h.stub.props["ro.config.cpu_count"] = "4"
        assertEquals("0-3\n", h.text("/sys/devices/system/cpu/online"))
        assertEquals(listOf("cpu0", "cpu1", "cpu2", "cpu3"), h.names("/sys/devices/system/cpu"))
        assertEquals("1\n", h.text("/sys/devices/system/cpu/cpu2/online"))
    }

    @Test
    fun theOmpSubtreeIsOursAndHoldsTheRealIdentity() {
        assertEquals("10123\n", h.text("/sys/omp/android/uid"))
        assertEquals("10123\n", h.text("/sys/omp/android/gid"))
        assertEquals("Pixel Stub\n", h.text("/sys/omp/android/model"))
        assertEquals("Google\n", h.text("/sys/omp/android/manufacturer"))
        assertEquals("stub\n", h.text("/sys/omp/android/device"))
        assertEquals("UP1A.231005.007\n", h.text("/sys/omp/android/build_id"))
        assertEquals("34\n", h.text("/sys/omp/android/sdk_int"))
        assertEquals("14\n", h.text("/sys/omp/android/release"))
        assertEquals("arm64-v8a\n", h.text("/sys/omp/android/abi"))
        assertTrue(h.text("/sys/omp/android/display").contains("1080x2400 420dpi"))
        // The VM's own uid is 1000 and the app's real one is here; the namespace does not blur them.
        assertEquals(1000, h.kernel.users.uid())
        assertEquals(10123, h.services.appUid())
    }
}
