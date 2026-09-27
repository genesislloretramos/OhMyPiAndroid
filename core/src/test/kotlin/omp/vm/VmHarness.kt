package omp.vm

import omp.shell.HttpStream
import omp.shell.PlatformServices
import omp.shell.StubPlatformServices
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.term.Screen
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * [StubPlatformServices] with the one thing a `/proc` test has to be able to do: change a fact
 * *after* boot, which is the only way to catch a generated file that was computed once. Everything
 * else — props, clocks, battery, interfaces — is the shared stub, reached through [stub].
 */
class VmServices(
    home: String,
    initialDir: String,
    external: String?,
    granted: Boolean,
) : PlatformServices {
    /** The shared stub underneath; `props`, `monotonic` and `battery` are its own fields. */
    val stub = StubPlatformServices(home, initialDir, external, granted)

    var memoryFacts: PlatformServices.MemoryInfo = stub.memory

    var batteryFacts: PlatformServices.BatteryInfo = stub.batteryInfo()

    var uidFacts: Int = stub.appUid()

    init {
        // Enough of a build for /proc/version, uname and /sys/omp/android to have something true to
        // say; a stub with an empty property map would only prove the fallbacks. Seeded here and not
        // in the harness because the VM reads the ABI at boot as well as at query time, and a dpkg
        // database seeded before the property existed would carry the wrong architecture.
        stub.props["ro.product.model"] = "Pixel Stub"
        stub.props["ro.product.manufacturer"] = "Google"
        stub.props["ro.product.device"] = "stub"
        stub.props["ro.build.version.release"] = "14"
        stub.props["ro.build.version.sdk"] = "34"
        stub.props["ro.build.id"] = "UP1A.231005.007"
        stub.props["ro.product.cpu.abi"] = "arm64-v8a"
        stub.props["ro.build.fingerprint"] = "google/stub/stub:14/UP1A.231005.007/1:user/release-keys"
    }

    /** Set by [clearRoute]; the shared stub has no such switch of its own. */
    @Volatile
    var routeWithheld: Boolean = false

    /** Withdraws the route, which is how a test makes the platform look like it has no DNS. */
    fun clearRoute() {
        routeWithheld = true
    }

    override fun systemMemory(): PlatformServices.MemoryInfo = memoryFacts

    override fun batteryInfo(): PlatformServices.BatteryInfo = batteryFacts

    override fun appUid(): Int = uidFacts

    // ---- everything else, delegated ----------------------------------------------------
    override fun homeDir(): String = stub.homeDir()
    override fun appFilesDir(): String = stub.appFilesDir()
    override fun initialDirectory(): String = stub.initialDirectory()
    override fun externalStorageDir(): String? = stub.externalStorageDir()
    override fun isExternalStorageManager(): Boolean = stub.isExternalStorageManager()
    override fun requestAllFilesAccess() = stub.requestAllFilesAccess()
    override fun wallClockMillis(): Long = stub.wallClockMillis()
    override fun monotonicMillis(): Long = stub.monotonicMillis()
    override fun timeZoneId(): String = stub.timeZoneId()
    override fun locale(): String = stub.locale()
    override fun buildProperties(): Map<String, String> = stub.buildProperties()
    override fun systemPropertyOverrides(): Map<String, String> = stub.systemPropertyOverrides()
    override fun deviceName(): String? = stub.deviceName()
    override fun processName(): String = stub.processName()
    override fun processPid(): Int = stub.processPid()
    override fun displayWidthPx(): Int = stub.displayWidthPx()
    override fun displayHeightPx(): Int = stub.displayHeightPx()
    override fun displayDensityDpi(): Int = stub.displayDensityDpi()
    override fun displayRefreshRateHz(): Float = stub.displayRefreshRateHz()
    override fun displayName(): String = stub.displayName()
    override fun storageVolumes(): List<PlatformServices.StorageVolume> = stub.storageVolumes()
    override fun clipboardWrite(text: String) = stub.clipboardWrite(text)
    override fun clipboardRead(): String? = stub.clipboardRead()
    override fun setTitle(title: String) = stub.setTitle(title)
    override fun installedPackages(a: Boolean, b: Boolean, c: Boolean): List<String> =
        stub.installedPackages(a, b, c)
    override fun appGid(): Int = stub.appGid()
    override fun packagePaths(pkg: String): List<String> = stub.packagePaths(pkg)
    override fun installerOf(pkg: String): String? = stub.installerOf(pkg)
    override fun forceStopPackage(pkg: String): String? = stub.forceStopPackage(pkg)
    override fun startActivity(spec: PlatformServices.IntentSpec): String? = stub.startActivity(spec)
    override fun settingGet(namespace: String, key: String): String? = stub.settingGet(namespace, key)
    override fun settingList(namespace: String): Map<String, String> = stub.settingList(namespace)
    override fun processPssKb(): Long = stub.processPssKb()
    override fun processCpuTimes(): Pair<Long, Long> = stub.processCpuTimes()
    override fun networkInterfaces(): List<PlatformServices.NetInterface> = stub.networkInterfaces()
    override fun activeRoute(): PlatformServices.Route? = if (routeWithheld) null else stub.activeRoute()
    override fun captureScreenPng(): ByteArray? = stub.captureScreenPng()
    override fun httpGet(url: String, method: String, headers: List<Pair<String, String>>): PlatformServices.HttpResult =
        stub.httpGet(url, method, headers)
    override fun httpStream(url: String, method: String, headers: List<Pair<String, String>>, body: ByteArray?): HttpStream =
        stub.httpStream(url, method, headers, body)
    override fun prefInt(key: String, fallback: Int): Int = stub.prefInt(key, fallback)
    override fun prefBoolean(key: String, fallback: Boolean): Boolean = stub.prefBoolean(key, fallback)
    override fun prefString(key: String, fallback: String): String = stub.prefString(key, fallback)
    override fun putPrefInt(key: String, value: Int) = stub.putPrefInt(key, value)
    override fun putPrefBoolean(key: String, value: Boolean) = stub.putPrefBoolean(key, value)
    override fun putPrefString(key: String, value: String) = stub.putPrefString(key, value)
}

/**
 * A booted VM over a temp directory, with a shell that speaks its namespace. The assertions in the
 * VM tests go through the same three doors a user has — the [omp.shell.fs.Vfs], a command line and
 * the mount table — because a namespace that only works when you call its internals is not a
 * namespace.
 */
class VmHarness(
    val folder: File,
    val granted: Boolean = true,
    val externalDir: File? = File(folder, "external").apply { mkdirs() },
) {
    val services = VmServices(
        home = File(folder, "app-home").path,
        initialDir = File(folder, "app-home").path,
        external = externalDir?.path,
        granted = granted,
    )

    /** The shared stub's own fields: `props`, `monotonic`, `battery`, `interfaces`. */
    val stub: StubPlatformServices = services.stub

    /** The whole VM: a kernel, the userland table and the session factory the app calls. */
    val system = VmSystem(File(folder, "vm"), services) { services.monotonicMillis() }

    val kernel: VmKernel = system.kernel

    val boot: List<BootLine> = system.boot()

    val table: omp.shell.exec.CommandTable = system.table

    /**
     * The session the app would hand a user: a real [omp.shell.ShellSession] with the VM's
     * environment, driving the same [omp.shell.exec.Shell] any session has. Using the real one is the
     * point — a test that built its own Session would prove nothing about the path a user takes.
     */
    val shellSession: omp.shell.ShellSession = system.openSession(Screen(24, 80), omp.shell.InputChannel())

    val session = shellSession.session

    val shell = shellSession.shell

    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()

    init {
        File(folder, "app-home").mkdirs()
    }

    data class Result(val status: Int, val out: String, val err: String)

    fun run(line: String, stdin: String = "", tty: Boolean = true): Result {
        out.reset()
        err.reset()
        val status = shell.executeLine(line, ByteArrayInputStream(stdin.toByteArray()), out, err, tty)
        return Result(status, String(out.toByteArray(), Charsets.UTF_8), String(err.toByteArray(), Charsets.UTF_8))
    }

    /**
     * One line with the REPL's own [omp.shell.InputChannel] as stdin, which is what a command that
     * asks a question needs: `ctx.stdin` is then the very channel the line editor reads, and an
     * answer fed with [feed] arrives where `less` and `omp` both read it. [tty] is what the
     * command believes about its stdout, and it is the second half of whether a question is asked
     * at all — `omp` asks only of a terminal that is this session's own.
     */
    fun runInteractive(line: String, tty: Boolean = true): Result {
        out.reset()
        err.reset()
        val status = shell.executeLine(line, shellSession.stdin, out, err, tty)
        return Result(status, String(out.toByteArray(), Charsets.UTF_8), String(err.toByteArray(), Charsets.UTF_8))
    }

    /** One line with a pipe on stdin, as a script or `vm exec` gives a command. */
    fun runPiped(line: String, stdin: String = ""): Result = run(line, stdin, tty = false)

    /** Keystrokes, as the IME and the hardware keyboard deliver them. */
    fun feed(vararg lines: String) {
        shellSession.input.feed(lines.joinToString("\r").plus("\r").toByteArray(Charsets.UTF_8))
    }

    fun stdout(line: String): String = run(line).out

    fun stderr(line: String): String = run(line).err

    fun text(path: String): String = String(kernel.vfs.readBytes(path), Charsets.UTF_8)

    fun names(path: String): List<String> = kernel.vfs.readDir(path).map { it.name }.sorted()

    /** The errno a call refused with; a call that succeeds fails the test instead. */
    /** A second, independent REPL in the namespace, for the session tests. */
    fun openSession(): omp.shell.ShellSession = system.openSession(Screen(24, 80), omp.shell.InputChannel())

    fun errnoOf(body: () -> Unit): FsErrno = try {
        body()
        throw AssertionError("expected an FsException")
    } catch (e: FsException) {
        e.errno
    }
}

/**
 * The phone-side shell: the session that owns the `vm` command, with the screen, the channel and
 * the host the Activity would give it.
 *
 * It is a real [omp.shell.ShellSession] over [omp.shell.exec.CommandTable.global], so `vm` here is
 * the command that ships and the namespace it opens is a second [VmSystem] over the same disk the
 * harness booted. That is the whole point of having both in one test: `harness` is a REPL *inside*
 * the namespace and this is the shell that is out here holding the door, and a feature the user
 * reaches through `vm mount` is only honest if both ends of it are driven the way each is in real
 * use.
 */
class VmPhone(
    val services: omp.shell.PlatformServices,
    val table: omp.shell.exec.CommandTable = omp.shell.exec.CommandTable.global,
) {
    val screen = Screen(120, 200, 8000)
    val input = omp.shell.InputChannel()
    val shell = omp.shell.ShellSession(services, screen, input, table)
    val host = SessionRecorder(shell.session)
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()

    init {
        shell.session.host = host
    }

    fun run(line: String, stdin: String = "", tty: Boolean = true): Result {
        out.reset()
        err.reset()
        val status = shell.shell.executeLine(line, ByteArrayInputStream(stdin.toByteArray()), out, err, tty)
        return Result(status, String(out.toByteArray(), Charsets.UTF_8), String(err.toByteArray(), Charsets.UTF_8))
    }

    /**
     * One line with the REPL's own [omp.shell.InputChannel] as stdin, which is what a command that
     * asks a question needs: `ctx.stdin` is then the very channel the line editor reads, so an
     * answer fed with [feed] arrives where `less` and `omp` both read it.
     */
    fun runInteractive(line: String, tty: Boolean = true): Result {
        out.reset()
        err.reset()
        val status = shell.shell.executeLine(line, shell.stdin, out, err, tty)
        return Result(status, String(out.toByteArray(), Charsets.UTF_8), String(err.toByteArray(), Charsets.UTF_8))
    }

    /** One line with a pipe on stdin, as a script or `vm exec` gives a command. */
    fun runPiped(line: String, stdin: String = ""): Result = run(line, stdin, tty = false)

    fun stdout(line: String): String = run(line).out

    /** Keystrokes, as the IME and the hardware keyboard deliver them. */
    fun feed(vararg lines: String) {
        input.feed(lines.joinToString("\r").plus("\r").toByteArray(Charsets.UTF_8))
    }

    fun screenText(): String = screen.snapshot().joinToString("\n") { it.text() }

    /** @return false if the nested REPL never took the terminal, rather than hanging the suite. */
    fun awaitPush(): Boolean {
        val deadline = System.currentTimeMillis() + 5000
        while (host.pushed.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        return host.pushed.isNotEmpty()
    }

    data class Result(val status: Int, val out: String, val err: String)
}

/** The one-deep session stack the Activity keeps, in a form a JVM test can look at. */
class SessionRecorder(private val root: omp.shell.Session) : omp.shell.SessionHost {
    val pushed = ArrayList<omp.shell.Session>()
    val popped = ArrayList<omp.shell.Session>()
    var front: omp.shell.Session = root

    override fun sessionPushed(session: omp.shell.Session) {
        pushed += session
        front = session
    }

    override fun sessionPopped(session: omp.shell.Session) {
        popped += session
        front = root
    }
}
