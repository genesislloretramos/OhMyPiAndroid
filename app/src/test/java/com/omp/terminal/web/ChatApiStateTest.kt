package com.omp.terminal.web

import omp.agent.json.Json
import omp.vm.provision.Phase
import omp.vm.provision.Progress
import omp.vm.provision.ProvisionOutcome
import omp.vm.provision.ProvisionPaths
import omp.vm.provision.ProvisionStatusHolder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * `GET /api/state`, over a real loopback socket, with the provisioning fields a run publishes.
 *
 * **The route is asked, not a helper of it.** The claim under test is that a page which polls
 * `/api/state` can see that an `omp provision` started in a terminal is going and what phase it is
 * in — and the only way to know the answer reaches the wire is to bind a port, send a request with
 * the token and read the JSON that comes back. A test that called a field-building function would
 * pass against a route that had stopped sending it.
 *
 * **The values are the point, not the presence of the keys.** Every assertion is a number or a word
 * this app measured and published: a byte count that is the count the downloader reached, a phase
 * that is the one it is in, and an outcome that is how it ended.
 */
class ChatApiStateTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var phone: StubPhone
    private lateinit var paths: ProvisionPaths
    private lateinit var server: LocalServer

    @Before
    fun setUp() {
        ProvisionStatusHolder.clear()
        phone = StubPhone(folder.newFolder("files"), folder.newFolder("sdcard"))
        File(phone.files, "home").mkdirs()
        paths = ProvisionPaths.inAppStorage(phone)
        val api = ChatApi(phone, AssetSource { null }, TOKEN, "1.0")
        server = LocalServer(api.routes(), wantedPort = 0)
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
        ProvisionStatusHolder.clear()
    }

    // ---- a device that has provisioned nothing ---------------------------------------------------

    @Test
    fun aColdDeviceSaysSoWithEveryFieldAndNoRunGoing() {
        val state = state()

        assertEquals(false, state.flag(ChatApi.PROVISIONED))
        assertEquals(false, state.flag(ChatApi.PROVISION_ROOTFS))
        assertEquals(false, state.flag(ChatApi.PROVISION_AGENT))
        assertEquals(false, state.flag(ChatApi.PROVISION_RUNNING))
        assertEquals("", state.text(ChatApi.PROVISION_ABI))
        assertEquals("", state.text(ChatApi.PROVISION_PHASE))
        assertEquals("", state.text(ChatApi.PROVISION_ARTIFACT))
        assertEquals("", state.text(ChatApi.PROVISION_OUTCOME))
        assertEquals("", state.text(ChatApi.PROVISION_LINE))
        assertEquals(0L, state.count(ChatApi.PROVISION_RECEIVED))
        assertEquals(0L, state.count(ChatApi.PROVISION_TOTAL))
    }

    // ---- a run in progress -------------------------------------------------------------------------

    @Test
    fun aRunInProgressArrivesWithItsPhaseItsArtifactAndItsTwoByteCounts() {
        ProvisionStatusHolder.begin("arm64-v8a")
        ProvisionStatusHolder.progress(
            "arm64-v8a",
            Progress("omp-linux-arm64", Phase.DOWNLOADING, 12_582_912L, 234_866_984L),
        )

        val state = state()

        assertEquals(true, state.flag(ChatApi.PROVISION_RUNNING))
        assertEquals("arm64-v8a", state.text(ChatApi.PROVISION_ABI))
        assertEquals("DOWNLOADING", state.text(ChatApi.PROVISION_PHASE))
        assertEquals("omp-linux-arm64", state.text(ChatApi.PROVISION_ARTIFACT))
        // The counts, as the integers they are: `Json.Num` keeps its raw text, so a byte count is
        // not rounded into a double on the way to a page.
        assertEquals(12_582_912L, state.count(ChatApi.PROVISION_RECEIVED))
        assertEquals(234_866_984L, state.count(ChatApi.PROVISION_TOTAL))
        assertEquals(
            "provisioning: downloading omp-linux-arm64 — 12.0 MiB (12,582,912 bytes) of " +
                "224.0 MiB (234,866,984 bytes)",
            state.text(ChatApi.PROVISION_LINE),
        )
        // Nothing is installed yet, whatever is downloading.
        assertEquals(false, state.flag(ChatApi.PROVISIONED))
    }

    // ---- after the run -----------------------------------------------------------------------------

    @Test
    fun aRunThatEndedReportsItsOutcomeAndTheRealAgentIsOnThePhone() {
        // The two marks the Provisioner leaves, made on the disk: the marker is inside the tree it
        // describes and the agent is a file beside it.
        File(paths.rootfsDir).mkdirs()
        File(paths.rootfsMarker).writeText("omp-provisioned\n")
        File(paths.agentDir).mkdirs()
        File(paths.agentBinary).writeBytes("#!glibc\nomp".toByteArray())
        ProvisionStatusHolder.begin("arm64-v8a")
        ProvisionStatusHolder.progress(
            "arm64-v8a",
            Progress("omp-linux-arm64", Phase.INSTALLED, 0L, 234_866_984L),
        )
        ProvisionStatusHolder.finish(ProvisionOutcome.PROVISIONED)

        val state = state()

        assertTrue("a provisioned device is provisioned", state.flag(ChatApi.PROVISIONED))
        assertTrue(state.flag(ChatApi.PROVISION_ROOTFS))
        assertTrue(state.flag(ChatApi.PROVISION_AGENT))
        assertFalse("and no run is going", state.flag(ChatApi.PROVISION_RUNNING))
        assertEquals("PROVISIONED", state.text(ChatApi.PROVISION_OUTCOME))
        // A finished run has no line: the notification goes back to what it says the rest of the
        // time, and a persistent "provisioning: PROVISIONED" would be a second copy of the terminal.
        assertEquals("", state.text(ChatApi.PROVISION_LINE))
    }

    @Test
    fun aCancelledRunKeepsItsOutcomeAndSaysNothingIsRunning() {
        ProvisionStatusHolder.begin("arm64-v8a")
        ProvisionStatusHolder.progress(
            "arm64-v8a",
            Progress("omp-linux-arm64", Phase.DOWNLOADING, 6_710_886L, 234_866_984L),
        )
        ProvisionStatusHolder.finish(ProvisionOutcome.CANCELLED)

        val state = state()

        assertEquals("CANCELLED", state.text(ChatApi.PROVISION_OUTCOME))
        assertFalse(state.flag(ChatApi.PROVISION_RUNNING))
        assertEquals(6_710_886L, state.count(ChatApi.PROVISION_RECEIVED))
        assertFalse("a cancelled download installed nothing", state.flag(ChatApi.PROVISIONED))
    }

    @Test
    fun theRootfsWithoutTheAgentIsNotAProvisionedDevice() {
        // The agent on its own runs nothing: a glibc binary with no Debian to find a loader in is a
        // download and not an answer, which is why the route publishes the rootfs flag separately.
        File(paths.rootfsDir).mkdirs()
        File(paths.rootfsMarker).writeText("omp-provisioned\n")

        val state = state()

        assertTrue(state.flag(ChatApi.PROVISION_ROOTFS))
        assertFalse(state.flag(ChatApi.PROVISION_AGENT))
        assertFalse("and the whole thing is therefore not provisioned", state.flag(ChatApi.PROVISIONED))
    }

    @Test
    fun theFieldsAreBesideTheOnesTheRouteAlreadyHadAndDoNotDisturbThem() {
        ProvisionStatusHolder.begin("arm64-v8a")

        val state = state()

        // Everything `/api/state` answered before this change is still answered, with the same
        // values: a page that reads `conversations` must not have to learn a new protocol.
        assertEquals("1.0", state.text("version"))
        assertEquals("0.1.0", state.text("agent"))
        assertTrue(state.text("tools")!!.contains("read_file"))
        assertTrue(state.text("container")!!.endsWith("Documents/omp"))
        assertEquals(true, state.flag("storageGranted"))
        assertEquals(0, (state.field("conversations") as Json.Arr).items.size)
        assertEquals(0, (state.field("providers") as Json.Arr).items.size)
    }

    // ---- over a socket -----------------------------------------------------------------------------

    /** One `GET /api/state` with the token, and the object that came back. */
    private fun state(): Json.Obj {
        val socket = Socket()
        socket.connect(InetSocketAddress(LocalServer.LOOPBACK, server.port), 5000)
        socket.soTimeout = 10_000
        val request = "GET /api/state HTTP/1.1\r\nHost: x\r\n" +
            "Cookie: ${TokenGate.COOKIE}=$TOKEN\r\n\r\n"
        socket.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))
        socket.getOutputStream().flush()
        val raw = socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        socket.close()

        val head = raw.substringBefore("\r\n\r\n")
        assertTrue("the state route answered:\n$head", head.startsWith("HTTP/1.1 200"))
        return Json.parse(raw.substringAfter("\r\n\r\n")) as Json.Obj
    }

    private fun Json.Obj.flag(name: String): Boolean = (field(name) as Json.Bool).value

    private fun Json.Obj.text(name: String): String = (field(name) as Json.Str).value

    private fun Json.Obj.count(name: String): Long = (field(name) as Json.Num).raw.toLong()

    private companion object {
        const val TOKEN = "test-token"
    }
}
