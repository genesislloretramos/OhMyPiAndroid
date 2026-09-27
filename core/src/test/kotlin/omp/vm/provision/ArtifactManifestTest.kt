package omp.vm.provision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The measured table, pinned one ABI at a time, and the sentence a user is asked to agree to.
 *
 * These numbers are the ones measured against the URLs beside them, and a test per ABI is the
 * whole reason a change upstream is a red build here rather than 30 MB of somebody's mobile data
 * on a phone. A failure in one of these says exactly which artifact moved, which is the only
 * useful thing a failure in a table can say.
 */
class ArtifactManifestTest {

    @Test
    fun arm64DownloadsADebianRootfsAndTheArm64Agent() {
        val arm64 = ArtifactManifest.of(Abi.ARM64)
        val rootfs = arm64.rootfs!!
        assertEquals("debian-trixie-rootfs-arm64", rootfs.name)
        assertEquals(58_379_360L, rootfs.sizeBytes)
        assertEquals(
            "https://deb.debian.org/debian/netboot/debian-13.1.0/arm64/linux",
            rootfs.url,
        )
        assertNull("no digest is pinned for the netboot image in this build", rootfs.sha256)
        val agent = arm64.agent!!
        assertEquals("omp-linux-arm64", agent.name)
        assertEquals(234_866_984L, agent.sizeBytes)
        assertEquals(
            "https://github.com/can1357/oh-my-pi/releases/download/v18.3.4/omp-linux-arm64",
            agent.url,
        )
        assertEquals("bb058d49dde5bb84e6f1b96aa798d7393d3bce1a6927392694e49120ba2c2619", agent.sha256)
        assertNull(arm64.gap)
        // The LAMP closure, measured from the Debian trixie arm64 index, and the reason it is on
        // this manifest at all: the guest installs it for itself, and those bytes cross the same
        // radio and take the same disk.
        assertEquals("LAMP", arm64.guest.name)
        assertEquals(57_211_704L, arm64.guest.downloadBytes)
        assertEquals(385_689L, arm64.guest.installedKib)
        assertTrue(arm64.guestIsMeasured)
        assertEquals(
            listOf("apache2-bin", "libapache2-mod-php8.4", "php8.4-cli", "mariadb-server"),
            arm64.guest.packages,
        )
        // 293,246,344 without the guest's own transfer, which is what a user would have been told.
        assertEquals(350_458_048L, arm64.totalBytes)
        assertEquals(350_458_048L + 385_689L * 1024L, arm64.requiredBytes)
    }

    @Test
    fun x8664DownloadsTheAmd64AgentAndItsOwnRootfs() {
        val x64 = ArtifactManifest.of(Abi.X86_64)
        val rootfs = x64.rootfs!!
        assertEquals("debian-trixie-rootfs-amd64", rootfs.name)
        assertEquals(55_459_886L, rootfs.sizeBytes)
        assertEquals(
            "https://deb.debian.org/debian/netboot/debian-13.1.0/amd64/linux",
            rootfs.url,
        )
        val agent = x64.agent!!
        assertEquals("omp-linux-x64", agent.name)
        assertEquals(284_861_920L, agent.sizeBytes)
        assertEquals(
            "https://github.com/can1357/oh-my-pi/releases/download/v18.3.4/omp-linux-x64",
            agent.url,
        )
        assertEquals("e8d13ea8da6281f183108bc25ae94c4a1a5159a2072d188ea8c30bbfce4735b8", agent.sha256)
        assertNull(x64.gap)
        // No LAMP figure was measured for amd64, and the row says so rather than borrowing arm64's.
        assertNull(x64.guest.downloadBytes)
        assertNull(x64.guest.installedKib)
        assertFalse(x64.guestIsMeasured)
        assertTrue(x64.describe().contains("Nothing has been measured for the LAMP packages"))
    }

    @Test
    fun armv7GetsADebianAndNoAgent() {
        val arm = ArtifactManifest.of(Abi.ARMEABI_V7A)
        val rootfs = arm.rootfs!!
        assertEquals("debian-trixie-rootfs-armhf", rootfs.name)
        assertEquals(39_098_143L, rootfs.sizeBytes)
        assertEquals(
            "the netboot directory is Debian's name for this port, not the enum's",
            "https://deb.debian.org/debian/netboot/debian-13.1.0/armhf/linux",
            rootfs.url,
        )
        assertNull("there is no 32-bit agent build", arm.agent)
        assertFalse(arm.needsAgent)
        assertEquals(39_098_143L, arm.totalBytes)
        assertNull(arm.guest.installedKib)
    }

    @Test
    fun x86GetsNothingAtAllAndSaysWhy() {
        val x86 = ArtifactManifest.of(Abi.X86)
        assertNull("debian.debian.org answers 404 for an i386 netboot image", x86.rootfs)
        assertNull(x86.agent)
        assertEquals(0L, x86.totalBytes)
        assertEquals(ArtifactManifest.NO_ROOTFS, x86.gap)
        assertTrue(x86.describe().contains(ArtifactManifest.NO_ROOTFS))
    }

    @Test
    fun theSentenceNamesTheWireCostTheRoomAndWhereLampComesFrom() {
        val arm64 = ArtifactManifest.of(Abi.ARM64)

        val sentence = arm64.describe()

        assertEquals(
            "arm64-v8a: 334.2 MiB (350,458,048 bytes) over the network — 55.7 MiB (58,379,360 " +
                "bytes) of Debian trixie rootfs and 224.0 MiB (234,866,984 bytes) of the omp v18.3.4 " +
                "agent binary, and 54.6 MiB (57,211,704 bytes) of LAMP that apt fetches inside the " +
                "Debian after the rootfs is unpacked. It needs 710.9 MiB (745,403,584 bytes) of " +
                "room on the device, of which 376.6 MiB (394,945,536 bytes) is LAMP installed.",
            sentence,
        )
    }

    @Test
    fun theRoomAFirstRunNeedsIsTheWirePlusTheMeasuredInstall() {
        val arm64 = ArtifactManifest.of(Abi.ARM64)
        val wire = arm64.totalBytes
        val installed = arm64.installedKib!! * 1024L

        assertEquals(wire + installed, arm64.requiredBytes)
        assertTrue(
            "a check against the download alone would pass a phone that then fills up",
            arm64.requiredBytes > wire,
        )
    }

    @Test
    fun a32BitSentenceSaysTheAgentIsNeverComing() {
        val arm = ArtifactManifest.of(Abi.ARMEABI_V7A)

        val sentence = arm.describe()

        assertEquals(
            "armeabi-v7a: 37.3 MiB (39,098,143 bytes) for the Debian trixie rootfs, and no agent " +
                "on top of it — the agent publishes no 32-bit Linux build.",
            sentence,
        )
        assertEquals(
            "there is no real omp agent for armeabi-v7a: the agent publishes no 32-bit Linux build",
            arm.gap,
        )
    }

    @Test
    fun aByteCountBelowAMebibyteIsNotRoundedIntoAFractionOfNothing() {
        assertEquals("0 bytes", ArtifactManifest.humanBytes(0L))
        assertEquals("1023 bytes", ArtifactManifest.humanBytes(1023L))
        assertEquals("1.0 MiB (1,048,576 bytes)", ArtifactManifest.humanBytes(1_048_576L))
    }
}
