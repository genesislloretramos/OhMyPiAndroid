package omp.shell

import omp.shell.exec.CommandTable
import omp.shell.exec.ExecContext
import omp.shell.fs.RealVfs
import omp.term.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The REPL with a namespace-shaped constructor: the three defaults must still be the phone's own
 * session, and a session that brings its own [omp.shell.fs.Vfs] must not have the phone's start-up
 * side effects run inside someone else's filesystem.
 */
class ShellSessionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun services(): PlatformServices {
        val home = File(folder.root, "home").apply { mkdirs() }
        return StubPlatformServices(home = home.path, initialDir = home.path)
    }

    private fun home(): File = File(folder.root, "home")

    @Test
    fun thePhoneSessionPreparesItsHomeAndSaysOmp() {
        val session = ShellSession(services(), Screen(24, 80), InputChannel())
        assertTrue(File(home(), ".profile").isFile)
        assertTrue(session.prompt().contains("omp:"))
    }

    @Test
    fun aSessionWithItsOwnFilesystemIsLeftAlone() {
        home().mkdirs()
        File(home(), ".profile").writeText("mine")
        val elsewhere = File(folder.root, "vm-home").apply { mkdirs() }

        val session = ShellSession(
            services(),
            Screen(24, 80),
            InputChannel(),
            CommandTable.global,
            RealVfs(elsewhere.path),
            "ubuntu",
        )
        // Nothing is created in the phone's home, and nothing is created in the namespace either:
        // a namespace that is not this device's brings its own home.
        assertEquals("mine", File(home(), ".profile").readText())
        assertEquals(0, elsewhere.list()!!.size)
        assertTrue(session.prompt().contains("ubuntu:"))
    }

    /**
     * The screen as text, so a test can see what is on the prompt line rather than what was
     * returned: the half-typed line is the point of the second test below.
     */
    private fun screenText(screen: Screen): String = buildString {
        for (row in screen.snapshot()) {
            for (code in row.codes) if (code > 0) append(code.toChar())
            append('\n')
        }
    }

    @Test
    fun aSessionPoppedFromOutsideEndsWithoutAKeyEverArriving() {
        val input = InputChannel()
        val pushed = ShellSession(services(), Screen(24, 80), input, namespaceName = "ubuntu")
        val ended = java.util.concurrent.CountDownLatch(1)
        var status = -1
        val repl = Thread { status = pushed.run(); ended.countDown() }
        repl.start()
        // Nobody feeds a byte. Back sets the flag from the UI thread and nothing else, which is the
        // whole reason the editor has to poll it rather than block on the channel.
        Thread.sleep(50)
        pushed.session.exitRequested = true
        assertTrue("the REPL did not end on exitRequested", ended.await(10, java.util.concurrent.TimeUnit.SECONDS))
        repl.join(2_000)
        assertEquals(ExecContext.EXIT_OK, status)
        // Proof that no key was needed: the channel is still empty.
        assertEquals(null, input.pollByte(0))
    }

    @Test
    fun poppingASessionKeepsTheHalfTypedLineAndStillEnds() {
        val input = InputChannel()
        val screen = Screen(24, 80)
        val pushed = ShellSession(services(), screen, input, namespaceName = "ubuntu")
        input.feed("ls -l".toByteArray(Charsets.UTF_8))
        val ended = java.util.concurrent.CountDownLatch(1)
        var status = -1
        val repl = Thread { status = pushed.run(); ended.countDown() }
        repl.start()
        // Long enough for the editor to have decoded the line into its buffer and rendered it.
        Thread.sleep(200)
        pushed.session.exitRequested = true
        assertTrue("the REPL did not end on exitRequested", ended.await(10, java.util.concurrent.TimeUnit.SECONDS))
        repl.join(2_000)
        assertEquals(ExecContext.EXIT_OK, status)
        // The characters are still there. Back used to feed a Ctrl-D, which reached deleteForward
        // on a non-empty line and silently ate the character under the cursor.
        assertTrue(screenText(screen), screenText(screen).contains("ls -l"))
        // And nothing was run: the line was never submitted, so the history is untouched.
        assertEquals(0, pushed.session.history.size())
        assertEquals(null, pushed.session.history.last())
    }

    @Test
    fun aNestedSessionTakesOverCtrlCAndHandsItBack() {
        val input = InputChannel()
        val outer: () -> Unit = {}
        input.onInterrupt = outer

        val inner = ShellSession(services(), Screen(24, 80), input, namespaceName = "ubuntu")
        // Still the outer handler before the loop starts: taking over is a property of running.
        assertSame(outer, input.onInterrupt)

        input.close()
        assertEquals(ExecContext.EXIT_OK, inner.run())
        assertSame(outer, input.onInterrupt)
    }
}
