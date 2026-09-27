package com.omp.terminal.vm

import android.util.Log
import omp.shell.PlatformServices
import omp.shell.fs.RealVfs
import omp.vm.doctor.Doctor
import omp.vm.guestapi.AgentUpdate
import omp.vm.launcher.Containers
import omp.vm.provision.Abi
import omp.vm.provision.ArtifactManifest
import omp.vm.provision.GuestPackages
import omp.vm.provision.GuestStart
import omp.vm.provision.GuestStartReport
import omp.vm.provision.GuestWeb
import omp.vm.provision.ProotCommand
import omp.vm.provision.ProvisionPaths
import omp.vm.provision.notStarted
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The boot composition, on a device: everything the guest needs wired together once, run on its own
 * thread, and held where two callers can read it.
 *
 * ### What this class is for, and what it is not
 *
 * **It is the wiring.** [omp.vm.provision.GuestStart] decides the order, the six states and every
 * sentence of the report; [omp.vm.provision.GuestWeb] owns the port; and this class is the only
 * place that knows which platform object each of those needs a device's version of. Before it
 * existed none of them had a caller in main code: `ProotProcessLauncher`, `NativeProot` and
 * `GuestPackages` were constructed in tests and nowhere else, so the app could provision a Debian
 * and then go on serving a chat out of its own Kotlin server while the guest sat on the disk.
 *
 * **It is not a lifecycle.** It holds no Activity, no Service and no `Context`; it does not stop
 * what it started; and it does not survive this process. A rotation asks again and gets the same
 * answer from the same [start]'s guard, and a reboot leaves the record on the disk and no process
 * behind — which is the honest shape for a path emulator rather than a service, and one this build
 * has never watched die.
 *
 * ### Why it runs on a thread, and why the caller waits for it rather than blocking on it
 *
 * **[start] returns immediately; [resolved] becomes true when it is done.** The sequence contains a
 * bounded 60-second `omp update` inside a proot guest, and the screen whose origin it decides has to
 * exist throughout — the terminal, which is what this app shows anyway until there is a guest to
 * show. Blocking `onCreate` on it would mean a window with nothing in it for up to a minute on a
 * phone whose radio is slow.
 *
 * **The Activity decides the origin once, after this resolves.** That is what keeps
 * [omp.vm.provision.GuestStart]'s rule — *starting is not being up* — load-bearing at the screen as
 * well as in the report: a WebView pointed at a port nothing is listening on is a page that never
 * arrives, and the app's own loopback server is the one thing on this phone known to answer.
 *
 * **Nothing here has ever run.** No device, no emulator, no test — the whole of the guest path is
 * [omp.vm.provision.ProotCommand]'s and [ProotProcessLauncher]'s open questions, and this class is
 * the composition of things none of which has ever been executed.
 */
object GuestRuntime {

    private const val TAG = "omp"

    /**
     * The report from the last start in this process, or null while there has not been one.
     *
     * **A `@Volatile` field and not a listener list**, for the reason
     * [omp.vm.provision.ProvisionStatusHolder] is one: the reader is the Activity's frame loop, which
     * asks once every 16 ms and must not be given a subscription to keep alive. A field read that
     * has already happened by the time a caller asks is the whole of the contract, and [resolved] is
     * what says whether it has.
     */
    @Volatile
    private var report: GuestStartReport? = null

    /**
     * Whether [start] has been asked and is not going to be asked again.
     *
     * **An `AtomicBoolean` and not a null check on [report]**, because the decision being made is
     * "has this process already begun". A device with no Debian reaches a report in a millisecond and
     * one with a guest takes seconds, so a caller that asked again in between would get two guests,
     * two Apache binds on one port and two racing probes.
     */
    private val begun = AtomicBoolean(false)

    /**
     * Whether the guest has finished starting, whichever way it went.
     *
     * **False before the first report and true after it, and the screen waits on this rather than on
     * [serving].** The two are not interchangeable: waiting for [serving] would hang the app on a
     * device whose guest is never coming up, and that is the case the report exists to explain.
     */
    val resolved: Boolean get() = report != null

    /**
     * Whether Apache inside the Debian is serving this build's chat document.
     *
     * **The one value the chat's origin is decided on, and it comes from a probe rather than from a
     * process.** [omp.vm.provision.GuestWeb] writes down what that claim is and what it is not.
     */
    val serving: Boolean get() = report?.serving == true

    /** The whole report, for a caller that wants the state by name rather than the boolean. */
    fun last(): GuestStartReport? = report

    /**
     * Start the guest, once per process, on a daemon thread.
     *
     * **Idempotent, and that is the whole of its contract as far as the Activity is concerned**: a
     * rotation and a second `onCreate` both come through here and the second is a no-op, because the
     * first run's Apache is holding the port and a second would be a collision with itself.
     */
    fun start(services: PlatformServices, out: OutputStream) {
        if (!begun.compareAndSet(false, true)) return
        Thread({ report = run(services, out) }, "omp-guest-start").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * The whole composition, and every refusal that stops it before a process is launched.
     *
     * **Three preconditions are checked here and all three are a named refusal rather than a
     * half-built guest.** They are here and not inside [omp.vm.provision.GuestStart] because each is
     * a fact about *this platform* and none is a fact about the guest:
     *
     * 1. **An ABI list naming nothing this build packages** — there is no proot to launch and no
     *    Debian that could have been downloaded for it.
     * 2. **No conversations directory** — [ProotCommand] binds it at [ProotCommand.VISIBLE_MOUNT] and
     *    there is nothing to bind, and a guest whose API cannot reach a conversation is not a guest
     *    this app should start. Run `grant-storage` and it is answered.
     * 3. **No proot in the exec directory** — [ProotHelper.refusal]'s own sentence, which names the
     *    attribute that makes the package manager put it there.
     *
     * All three reach the record through [notStarted], so `omp doctor` names which of them it was on
     * the next run rather than inferring it from an absent guest.
     */
    private fun run(services: PlatformServices, out: OutputStream): GuestStartReport {
        val vfs = RealVfs()
        val paths = ProvisionPaths.inAppStorage(services)
        val abi = Abi.detect(System.getProperty("os.arch"), abiList(services))
        if (abi == null) {
            return notStarted(
                vfs,
                paths,
                "this device reports an architecture this app does not know, so the APK has no proot " +
                    "for it and there is no guest to start. Nothing was launched and nothing was " +
                    "downloaded.",
            )
        }
        val visible = Containers.hostRootOf(services)
        if (visible == null) {
            return notStarted(
                vfs,
                paths,
                "there is no conversations directory to bind at ${ProotCommand.VISIBLE_MOUNT}: this " +
                    "app does not hold \"All files access\", and a guest whose API cannot reach a " +
                    "conversation is not one this build starts. Run 'grant-storage' and open the app " +
                    "again. Nothing was launched.",
            )
        }
        val helper = ProotHelper(services.nativeLibraryDir(), services.appFilesDir(), abi)
        val prootPath = helper.prootPath
        if (prootPath == null) {
            return notStarted(vfs, paths, "proot: ${helper.refusal}. Nothing was launched.")
        }
        val proot = ProotCommand(
            prootPath = prootPath,
            rootfs = paths.rootfsDir,
            host = abi,
            guest = abi,
            dataDir = services.appFilesDir(),
            visibleDir = visible,
            agentDir = paths.agentDir,
        )
        // One launcher for the steps that finish — `a2enconf`, and the boot's `omp update` — one
        // bound tighter for the one that can take minutes, and one seam for the server that does not
        // finish at all. They differ only in how long the caller waits, which is the whole difference
        // between `omp.vm.provision.ProotLauncher` and `omp.vm.provision.GuestServer`.
        //
        // **The install gets its own launcher because it is the only step with a bound of its own.**
        // `GuestPackages.INSTALL_BOUND_MS` lives in `:core` because it is a fact about that step; the
        // launcher that enforces it can only exist here, because only `ProotProcessLauncher` can
        // destroy a process. It returns `GuestPackages.TIMED_OUT` (124) when it does, and the step
        // reports that as `STOPPED` rather than as a failure — the same trade
        // `omp.vm.guestapi.AgentUpdate` makes at 60 seconds, on the same terms.
        val installer = NativeProot(
            helper,
            out,
            ProotProcessLauncher(out, timeoutMs = GuestPackages.INSTALL_BOUND_MS),
        )
        val finished = GuestStart(
            paths = paths,
            vfs = vfs,
            proot = proot,
            guestInstall = ArtifactManifest.of(abi).guest,
            packages = GuestPackages(paths, proot, installer, vfs),
            server = ProotForegroundServer(helper, out),
            binder = GuestWeb.LOOPBACK_BINDER,
            probe = GuestWeb.RetryingProbe(LoopbackWebProbe),
            update = AgentUpdate(paths, vfs, proot, ProotUpdateTransport()),
        ).start()
        Log.i(TAG, "guest start: ${finished.state.name} on ${GuestWeb.baseUrl(finished.port)}")
        for (line in finished.lines) Log.i(TAG, "  $line")
        return finished
    }

    /**
     * `ro.product.cpu.abilist`, which [omp.shell.AndroidPlatformServices] writes from
     * `Build.SUPPORTED_ABIS`, in order.
     *
     * **The key is [omp.vm.doctor.Doctor.ABILIST] and not a second spelling of it.** A key typed twice
     * is two chances to read the wrong property, and the wrong property here is an empty ABI list,
     * which is a device this build decides it cannot start a guest on.
     */
    private fun abiList(services: PlatformServices): List<String> =
        services.buildProperties()[Doctor.ABILIST]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
}
