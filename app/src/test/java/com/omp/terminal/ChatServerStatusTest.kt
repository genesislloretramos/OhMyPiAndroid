package com.omp.terminal

import com.omp.terminal.web.StubPhone
import omp.shell.fs.RealVfs
import omp.vm.doctor.Doctor
import omp.vm.provision.Abi
import omp.vm.provision.ProvisionPaths
import omp.vm.web.ChatServerState
import omp.vm.web.ChatServerStatusHolder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A chat server that would not start is a **state with a reason**, and both the app and the shell
 * can read it.
 *
 * ### What this is holding in place
 *
 * A `startForeground` refused by the platform used to throw out of `Service.onCreate`, which ends
 * the process — so the app showed nothing at all, and no report in the world said why. The service
 * now catches that, records the sentence the platform gave it, and stops itself. That is only half
 * of a fix: a reason nobody can read is the same as no reason, which is what these tests are for.
 * They pin two things, and the second is the one that is easy to lose —
 *
 * 1. **the degraded path is named.** A boolean would have been enough to compile and useless to
 *    whoever has the phone: `not running` is the symptom they already reported.
 * 2. **the reason survives the stop that follows it.** The service stops itself in the same breath
 *    as the refusal, so any rule that cleared the slot on the way out would erase the only record
 *    the process will ever have — and the question it answers is asked *after* it stopped.
 *
 * The last test drives [Doctor], because `omp doctor` is the one report a person pastes into a
 * bug, and "the chat service has published no url at all" on its own cannot be told apart from
 * "the platform refused it". The doctor double is the app's own [StubPhone] and a real
 * [RealVfs] over a temp directory, as `DoctorTest` does it: the report reads files, and this test
 * is about what it says about a service, not about stubs.
 */
class ChatServerStatusTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var files: File
    private lateinit var exec: File
    private lateinit var phone: StubPhone

    /** The sentence the platform gives when the type's permission is not in the manifest. */
    private val platformSentence =
        "SecurityException: Starting FGS with type specialUse callerApp=ProcessRecord{...} " +
            "targetSDK=35 requires permissions: all of the permissions allOf=true " +
            "[android.permission.FOREGROUND_SERVICE_SPECIAL_USE]"

    @Before
    fun setUp() {
        ChatServerStatusHolder.clear()
        files = folder.newFolder("files")
        exec = folder.newFolder("lib", Abi.ARM64.abiName)
        phone = StubPhone(files, folder.newFolder("sdcard"))
        phone.props[Doctor.FINGERPRINT] = "google/stub/stub:14/UP1A.231005.007/1:user/release-keys"
        phone.props[Doctor.ABILIST] = Abi.ARM64.abiName
    }

    @After
    fun tearDown() {
        ChatServerStatusHolder.clear()
    }

    // ---- the degraded path is a state, not a boolean -----------------------------------------

    @Test
    fun aRefusedServiceIsANamedStateAndNotJustNotRunning() {
        ChatServerStatusHolder.refused(platformSentence)

        val refused = ChatServerStatusHolder.current
        assertEquals(
            "'not running' is the symptom the user already reported; the state has to say which " +
                "of the four it is",
            ChatServerState.REFUSED,
            refused.state,
        )
        assertTrue("a refusal is the degraded case", refused.degraded)
        assertFalse("and nothing is listening", refused.running)
        assertEquals(platformSentence, refused.refusal)
    }

    @Test
    fun aServiceThatHasNotBeenAskedForIsNeitherRunningNorDegraded() {
        val idle = ChatServerStatusHolder.current
        assertEquals(ChatServerState.NOT_STARTED, idle.state)
        assertFalse(idle.degraded)
        assertNull("there is no sentence to print about a device that has not opened the app", idle.line())
    }

    @Test
    fun aStopIsNotAReason() {
        // The user stopping the service from the notification is what they asked for. It has to
        // read as that and not as a fault, or every `web stop` looks like a bug report.
        ChatServerStatusHolder.bound("http://127.0.0.1:8731/login?t=a-token")
        ChatServerStatusHolder.stopped()

        val stopped = ChatServerStatusHolder.current
        assertEquals(ChatServerState.STOPPED, stopped.state)
        assertFalse(stopped.degraded)
        assertNull("a stop is not something to explain", stopped.line())
    }

    // ---- the reason outlives the stop that follows it ----------------------------------------

    @Test
    fun aRefusalIsStillTheReasonAfterTheServiceThatRecordedItHasStopped() {
        ChatServerStatusHolder.refused(platformSentence)
        // What onDestroy does, in the order it does it: the service stops itself.
        ChatServerStatusHolder.stopped()

        val after = ChatServerStatusHolder.current
        assertEquals(
            "the stop that follows a refusal erased it, so nothing in the process knows why the " +
                "server is not running",
            ChatServerState.REFUSED,
            after.state,
        )
        assertEquals(platformSentence, after.refusal)
    }

    @Test
    fun aServerThatBindsAfterARefusalIsRunningAndSaysNothingAboutTheRefusal() {
        ChatServerStatusHolder.refused(platformSentence)
        ChatServerStatusHolder.bound("http://127.0.0.1:8731/login?t=a-token")

        val bound = ChatServerStatusHolder.current
        assertEquals(ChatServerState.RUNNING, bound.state)
        assertTrue(bound.running)
        assertNull("a server that is up is not carrying an old failure around", bound.line())
    }

    // ---- the sentence a reporter prints --------------------------------------------------------

    @Test
    fun theLineSaysItIsNotRunningAndQuotesThePlatformsOwnWords() {
        ChatServerStatusHolder.refused(platformSentence)

        val line = ChatServerStatusHolder.current.line()!!
        assertTrue(
            "the line has to say the server is not running, and it says '$line'",
            line.startsWith("not running:"),
        )
        assertTrue(
            "the permission name is the whole content of the sentence, and it is missing from " +
                "'$line'",
            line.contains(ChatService.SPECIAL_USE_PERMISSION),
        )
    }

    // ---- and the two reporters can read it -----------------------------------------------------

    @Test
    fun ompDoctorNamesTheRefusalInsteadOfOnlySayingThereIsNoUrl() {
        ChatServerStatusHolder.refused(platformSentence)

        val report = doctor().report().text()

        assertTrue(
            "the chat section did not name the refusal:\n$report",
            report.contains(ChatService.SPECIAL_USE_PERMISSION),
        )
        assertTrue(
            "the report has to say the service was refused, not merely that nothing is " +
                "listening:\n$report",
            report.contains("refused:"),
        )
    }

    @Test
    fun aDeviceWithNothingWrongSaysNothingAboutARefusal() {
        // The other half of the line above: a report that always printed a `refused` row would
        // teach a reader to skip it, and the whole value of this line is that it is not there.
        val report = doctor().report().text()

        assertFalse(
            "a device that has refused nothing has no refusal line to print:\n$report",
            report.contains("refused:"),
        )
    }

    /** The same report `omp doctor` builds, over this app's own stub phone and a real filesystem. */
    private fun doctor(): Doctor = Doctor(
        phone,
        RealVfs(),
        ProvisionPaths(files.path, exec.path, files.path),
        probe = Doctor.Probe { false },
        osArch = "arm64",
    )
}
