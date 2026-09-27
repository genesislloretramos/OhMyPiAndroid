package omp.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Who the session thinks it is, and what `sudo` and `su` do about it.
 *
 * The point of every test here is the honesty requirement: `sudo id` really does print uid 0, and
 * the commands that do it say in their own output that no boundary was crossed — because there is
 * none to cross in a namespace where every file is already the app's uid.
 */
class VmIdentityTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var h: VmHarness

    @Before
    fun setUp() {
        h = VmHarness(folder.root)
    }

    @Test
    fun theSessionIsUbuntuByDefault() {
        assertEquals("ubuntu\n", h.stdout("whoami"))
        assertEquals("/home/ubuntu\n", h.stdout("echo \$HOME"))
    }

    @Test
    fun idPrintsTheUidGidAndTheGroupsTheFilesReallyDefine() {
        val out = h.stdout("id")
        assertTrue(out, out.startsWith("uid=1000(ubuntu) gid=1000(ubuntu) groups="))
        // ubuntu is in ubuntu, sudo, cdrom and dip: the primary group first, then the rest in file order.
        assertTrue(out, out.contains("1000(ubuntu)"))
        assertTrue(out, out.contains("27(sudo)"))
        assertTrue(out, out.contains("24(cdrom)"))
        assertTrue(out, out.contains("30(dip)"))

        // The order is /etc/group's own, which is what a real `groups` prints.
        assertEquals("ubuntu : ubuntu cdrom sudo dip\n", h.stdout("groups"))
        assertEquals("root : root\n", h.stdout("groups root"))
        assertEquals(1, h.run("id nosuchuser").status)
    }

    @Test
    fun sudoIdReportsUidZeroAndChangesNothingAfterwards() {
        val result = h.run("sudo id")
        assertTrue("out=${result.out} err=${result.err}", result.out.startsWith("uid=0(root) gid=0(root) groups=0(root)"))

        // The identity lasts for exactly one command, which is the whole contract.
        assertEquals("ubuntu\n", h.stdout("whoami"))
        assertEquals(1000, h.system.users.uid())
    }

    @Test
    fun sudoWithAUserRunsTheCommandAsThatUser() {
        val result = h.run("sudo -u nobody id")
        assertTrue("out=${result.out} err=${result.err}", result.out.contains("uid=65534(nobody)"))
        assertEquals("ubuntu\n", h.stdout("whoami"))
    }

    @Test
    fun bareSudoPrintsTheHonestList() {
        val out = h.stdout("sudo")
        assertTrue(out, out.contains("User ubuntu may run the following commands"))
        assertTrue(out, out.contains("(ALL : ALL) ALL"))
        // The line that makes this command trustworthy.
        assertTrue(out, out.contains("enforces none of this"))
    }

    @Test
    fun suSwitchesTheSessionAndSaysSo() {
        val switched = h.stdout("su root")
        assertTrue(switched, switched.startsWith("switched to root\n"))
        // The line that keeps a root prompt from implying a privilege the namespace does not have.
        assertTrue(switched, switched.contains("changes the name the session answers to and nothing else"))
        assertTrue(switched, switched.contains("/sys/omp/android/uid"))
        assertEquals("root\n", h.stdout("whoami"))
        assertEquals("/root\n", h.stdout("echo \$HOME"))
        assertEquals(1, h.run("su nosuchuser").status)
        assertTrue(h.run("su nosuchuser").err.contains("does not exist"))
    }

    @Test
    fun loginPrintsTheBannerAndBecomesThatUser() {
        val out = h.stdout("login ubuntu")
        assertTrue(out, out.contains("Ubuntu 24.04.1 LTS"))
        assertTrue(out, out.contains("This is the omp userland"))
        assertTrue(out, out.contains("You are now logged in as ubuntu"))
        assertEquals("ubuntu\n", h.stdout("whoami"))
    }

    @Test
    fun theAppUidIsNotTheVmUidAndSaysSo() {
        assertEquals(1000, h.system.users.uid())
        assertEquals(10123, h.services.appUid())
        // The real one is one cat away, and /etc/sudoers says what sudo is.
        assertEquals("10123\n", h.text("/sys/omp/android/uid"))
        assertTrue(h.text("/etc/sudoers").contains("is not an oversight"))
    }
}
