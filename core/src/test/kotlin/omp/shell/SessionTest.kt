package omp.shell

import omp.shell.exec.CommandTable
import omp.shell.fs.PathResolver
import omp.term.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What a [Session] says about itself. The one thing worth pinning is `$HOME`: it belongs to the
 * environment, so `export HOME=…` moves it, and a namespace that is not this device's can start
 * with a home of its own instead of the app sandbox.
 */
class SessionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun newSession(): Pair<Session, String> {
        val home = File(folder.root, "sandbox-home").apply { mkdirs() }
        val services = StubPlatformServices(home = home.path, initialDir = folder.root.path)
        return Session(services, Screen(24, 80)) to home.path
    }

    @Test
    fun homePrefersTheEnvironmentOverThePlatform() {
        val (session, platformHome) = newSession()
        // The platform's home is the seed, so an untouched session cannot tell the two apart.
        assertEquals(platformHome, session.env["HOME"])
        assertEquals(platformHome, session.home())

        val moved = File(folder.root, "moved-home").path
        session.env["HOME"] = moved
        assertEquals(moved, session.home())

        // Everything that expands a tilde has to agree, or `cd ~` and `ls ~` would answer with two
        // different directories.
        assertEquals(moved, PathResolver.expandTilde(session, "~"))
        assertEquals(moved + "/notes", PathResolver.expandTilde(session, "~/notes"))
        assertEquals("~", PathResolver.contract(session, moved))
        assertEquals("~/notes", PathResolver.contract(session, moved + "/notes"))
        session.cwd = moved
        assertEquals("~", session.promptCwd())

        // With nothing in the environment there is still an answer: the platform's.
        session.env.remove("HOME")
        assertEquals(platformHome, session.home())
    }

    @Test
    fun aSessionIsOnThePhoneFilesystemAndTheGlobalTable() {
        val (session, _) = newSession()
        // Nothing configured: the session's Vfs is the device root, so what a `File` wrote on the
        // JVM is what the session reads, and the other way round.
        val probe = File(folder.root, "vfs-probe.txt")
        probe.writeText("hi")
        assertEquals("hi", String(session.vfs.readBytes(probe.path), Charsets.UTF_8))
        session.vfs.writeBytes(File(folder.root, "written.txt").path, "out".toByteArray())
        assertEquals("out", File(folder.root, "written.txt").readText())
        assertSame(CommandTable.global, session.table)
    }
}
