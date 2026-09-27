package omp.vm.provision

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

/**
 * The port Apache inside the Debian answers on, the command that starts it, and the two questions
 * this build has to ask of a device before it will hand that port to a WebView.
 *
 * ### The port decision, and why it is a fixed number and not a discovery
 *
 * **proot does not give the guest a network namespace, so a listener inside the guest is a listener
 * on this phone.** [omp.vm.provision.ProotCommand] passes no `-0` and no netns flag because proot has
 * no such option: a service in the Debian that binds `127.0.0.1:PORT` holds a socket in the *phone's*
 * loopback, reachable by every app on the device and by the app's own [omp.vm.provision.Provisioner]
 * siblings. That single fact is why the port cannot be a constant somebody hopes is free.
 *
 * **The decision: one reserved number, bind-checked immediately before use, and refused out loud
 * when it is not free.** The three alternatives were weighed and rejected in the project's own terms:
 *
 * | | why not |
 * |---|---|
 * | **discover it from the guest's own configuration** | Apache does not report the port it chose. Debian's `apache2` listens on `*:80` and `Listen 80` in `ports.conf` is the only place the number exists, and the value this build would have to write there and read back is one this build wrote — so "discovered" would be a round trip through a file it just edited, and it would be a *different* number on every device, which is a number the WebView's boundary and `omp doctor` would each have to be told separately. |
 * | **negotiate it with the guest** | there is nobody to negotiate with. The guest is `apt install`ed Apache this app did not write, running under a path emulator, with no init and no socket this app may talk to before the server exists. A handshake would mean putting a listener *in* the guest to ask which port to listen on. |
 * | **an ephemeral port, as [LocalServer] takes** | the app's own server can be handed its port by the kernel because the kernel *told* it. Apache cannot: it binds a number in a file. So an ephemeral port here would be a number this build guessed was free, which is the constant-plus-hope this section exists to remove. |
 *
 * **A fixed number and a bind check is not the same as a fixed number and hope**, and the difference
 * is the two facts this class is built around:
 *
 * 1. **[PortBinder.isFree] is asked immediately before Apache is started**, and a `false` is a named,
 *    reported state — [omp.vm.provision.GuestState.PORT_TAKEN] — and the guest is not started at all.
 *    **It is never a silent fall back to the app's own loopback server presented as though the guest
 *    were answering.** That specific lie is the one this whole path exists to prevent: a user would be
 *    told the real agent is answering while the Kotlin one is, and every decision they took from that
 *    screen would be taken about the wrong program.
 * 2. **[WebProbe] asks again after Apache is up.** The bind check is a check, not a lock: this app
 *    closes its socket before Apache is told the number, so another process can take it in between.
 *    The probe is what settles it, and only a probe that passes produces a [omp.vm.provision.GuestOriginRecord]
 *    that says `answered yes`, which is the only thing that makes the guest's origin eligible.
 *
 * ### The number, and why 8732
 *
 * **One above [com.omp.terminal.ChatService.PREFERRED_PORT]'s 8731, in the same unregistered block.**
 * It cannot be the same number as the app's own server, so the one collision that would be
 * guaranteed — this app's Kotlin server refusing to start because the guest's Apache took its port,
 * or the reverse — is impossible by construction rather than by a check. Everything else that could
 * hold it (a second install under a second profile, a debugging tool, a browser) is what the bind
 * check is for.
 *
 * ### What the probe really establishes, and what it cannot
 *
 * **[WebProbe.answers] is `true` when something on this phone returned this build's chat document to
 * an HTTP `GET /` on `127.0.0.1:[RESERVED_PORT]`.** Taken with the bind check immediately before it,
 * that establishes two facts and no more:
 *
 * - the port was **free** immediately before Apache was started, and
 * - an HTTP server is **answering on it** now, and it is serving *this app's* page rather than
 *   something else that happens to listen ([PAGE_MARKER]).
 *
 * **What it does not establish: that the thing answering is this install's process.** A second
 * profile of this same app is a real case — two installs, two payloads, two identical guests, one
 * phone — and both serve byte-identical pages, so no content check can tell them apart. What rules
 * that case out is the bind check: a second profile's Apache is *already listening* on the number
 * this build reserves, so the bind check fails, and the state is [omp.vm.provision.GuestState.PORT_TAKEN]
 * rather than a guest origin. **The residual, and it is stated rather than hidden: another process
 * that binds the same number in the window between the check being closed and Apache binding it.**
 * That window is a few milliseconds and nothing in this build can close it — the port cannot be held
 * and handed to another process — and the consequence of losing it is the safe one: the probe fails,
 * the state is [omp.vm.provision.GuestState.APACHE_NOT_ANSWERING], and the app's own server is shown
 * *and named as the app's own server*.
 *
 * **What is not established at all, and cannot be off a device: any of it.** Nothing in this
 * repository has ever run proot, Apache, or a glibc binary from a Debian on a phone, an emulator or a
 * test. The command in [APACHE_COMMAND] has never been executed by any code path here. See
 * [omp.vm.provision.ProotCommand] for the four questions a real device has to answer about the emulator
 * itself, and [com.omp.terminal.vm.ProotProcessLauncher] for the five about this one.
 */
object GuestWeb {

    /**
     * 8732 — the port Apache inside the Debian is told to listen on, and the only one.
     *
     * **A `const`, and not a preference read from a file or a setting.** The number appears in three
     * places that must agree — [omp.vm.provision.GuestStart] writes it into the record, `omp doctor`
     * prints it, and [com.omp.terminal.web.UiOrigins] builds the origin's base URL from it — and a
     * value that could be configured at runtime is a value those three could disagree about. One
     * constant in this one place is the whole of that problem.
     */
    const val RESERVED_PORT: Int = 8732

    /** The address every socket in this app binds and every origin is validated against. */
    const val LOOPBACK: String = "127.0.0.1"

    /**
     * The command that starts Apache inside the Debian, in the guest's own vocabulary.
     *
     * ```
     * /usr/sbin/apache2ctl -D FOREGROUND
     * ```
     *
     * **`apache2ctl` and not `apache2`, and the difference is Debian's own configuration.** Debian's
     * `apache2` is configured by `/etc/apache2/envvars`, which sets `APACHE_RUN_DIR`,
     * `APACHE_PID_FILE`, `APACHE_LOCK_DIR` and the runtime user; `apache2ctl` sources that file before
     * it does anything, and a bare `apache2` does not. The drop-in this app writes is linked in with
     * `a2enconf` before this runs, so a start that ignored `envvars` would be a start of a different
     * configuration than the one [omp.vm.provision.GuestPackages.enableApi] just set up.
     *
     * **`-D FOREGROUND`, because a proot guest has no init and this app must be able to tell a
     * running server from a dead one.** Apache's default is to fork into the background and return,
     * which under proot means the tracer that owns the traced processes may exit with it; running in
     * the foreground is the arrangement a container uses and the one [omp.vm.provision.GuestServer]
     * can hold open.
     *
     * **This vector has never been run by anything in this repository**, and the two things a device
     * has to answer about it are named where they are guessed at nowhere: whether proot's own fork and
     * `ptrace` support survives Apache's worker processes, and whether the `envvars` values point at
     * directories (`/var/run/apache2`, `/var/lock/apache2`) that exist in a netboot image.
     */
    val APACHE_COMMAND: List<String> = listOf(APACHE_CTL, "-D", "FOREGROUND")

    /** Debian's Apache control program, named by its own path because the guest has no shell to find it. */
    const val APACHE_CTL: String = "/usr/sbin/apache2ctl"

    /**
     * A line of `app/src/main/assets/web/index.html` that an HTTP server serving that file must send
     * back, and that nothing else on a loopback port is likely to.
     *
     * **A content check as well as a connect, because "something is listening" is not "the guest is
     * answering".** A port held by a debugging tool, a stray `nc`, or a stale server from a previous
     * run of this app all answer a connect. Requiring this build's own document turns the probe into
     * a statement about *what* is answering. `GuestWebProbeTest` in `:app` pins the marker against
     * the real file, for the reason [omp.vm.doctor.DoctorTest.theExpectedHelperListIsWhatThisApkActuallyPackages]
     * pins the packaged helper list: a marker that drifted from the file it names would make the
     * probe refuse a guest that is serving perfectly well.
     */
    const val PAGE_MARKER: String = "<title>omp</title>"

    /** The origin's base URL for [port], with no trailing slash and no path. */
    fun baseUrl(port: Int): String = "http://$LOOPBACK:$port"

    // ---- the two questions -------------------------------------------------------------------------

    /**
     * Whether this phone will let this app hold [port] on its own loopback right now.
     *
     * **A seam and not a call, so the collision case is reachable in a test at all.** A
     * [ServerSocket] bind either succeeds or throws, and the interesting answer is the one on a
     * device that already has something on the number; a fake that returns `false` is the only way to
     * say that on a build machine, and the behaviour it produces — a named refusal and no launch — is
     * the whole of what this seam exists for.
     */
    fun interface PortBinder {

        /**
         * True when [port] was free and this app has now given it back.
         *
         * **The socket is closed before this returns**, deliberately: the number has to be free for
         * Apache, and a reservation this app kept would be the one thing guaranteed to make it not
         * free. The window that opens is named on the class.
         */
        fun isFree(port: Int): Boolean
    }

    /**
     * The real binder: bind `127.0.0.1:port` exclusively, and give it straight back.
     *
     * **`setReuseAddress(false)`, and it is the strict version on purpose.** A JVM `ServerSocket`
     * asks the kernel for `SO_REUSEADDR` by default, which on Linux lets a bind succeed against a
     * port left in `TIME_WAIT` by a server that *was* there — and "was there and is gone" is not the
     * question. With it off, the bind succeeds only when nothing holds the number, which is the fact
     * this build needs before it hands the number to Apache.
     *
     * **Every failure is `false` and none of them is distinguished.** No permission, no address, a
     * port the kernel will not give an app: all of them mean the same thing to a caller, which is
     * that this number is not available to us right now, and the report says so in one named state
     * rather than in four.
     */
    val LOOPBACK_BINDER: PortBinder = PortBinder { port ->
        var socket: ServerSocket? = null
        try {
            socket = ServerSocket().apply {
                reuseAddress = false
                bind(java.net.InetSocketAddress(InetAddress.getByName(LOOPBACK), port))
            }
            true
        } catch (e: IOException) {
            false
        } finally {
            try {
                socket?.close()
            } catch (e: IOException) {
                // A socket this app opened and is giving back. A failure to close is not a fact about
                // the port, and reporting one would be inventing a diagnosis.
            }
        }
    }

    /**
     * Whether an HTTP server on `127.0.0.1:port` is serving this build's chat document.
     *
     * **The seam the guest's origin is gated on, and the only one.** A [omp.vm.provision.GuestStart]
     * that launched Apache and got a status back has established nothing about whether the page will
     * arrive, which is the entire difference between *starting* and *being up* and the reason
     * [omp.vm.provision.GuestState.APACHE_NOT_ANSWERING] is a state rather than a note.
     *
     * **The shipped implementation is in `:app`** — it opens a socket, which is the same class of
     * thing a JVM can do but not the same class of thing this module does — and it is
     * [omp.vm.provision.RetryingProbe] around it, because Apache binds its port *after* `apache2ctl`
     * has read its configuration and forked its workers, and a start that probed once would report a
     * working guest as dead.
     */
    fun interface WebProbe {

        /**
         * True when `GET /` on `127.0.0.1:port` came back `200` carrying [PAGE_MARKER].
         *
         * **`/` and not the login path.** The token is the app's to issue and the guest's PHP knows
         * nothing about it; `/` is the one path both arrangements serve, and probing a path that
         * needed the token would make the probe a test of this app's credential rather than of
         * whether Apache is up. See [omp.vm.provision.GuestOriginRecord] for how the answer is used.
         */
        fun answers(port: Int): Boolean
    }

    /**
     * Asks [inner] up to [attempts] times, and stops at the first yes.
     *
     * **A retry and not a sleep, and both halves are injected.** The count is the decision that
     * matters — a start that waited forever for a port that is never coming is a hung app, and a
     * start that waited once is a start that reports a working guest as dead on a slow phone — and
     * the pause between attempts is the thing a test must not pay for. `wait` is a parameter so a
     * test runs the real loop with no clock in it at all.
     *
     * **The last answer is what a false means.** One refused connect and one refused connect after
     * twenty are the same fact about the device, and the state that reports them says so in one
     * sentence rather than in a count.
     */
    class RetryingProbe(
        private val inner: WebProbe,
        private val attempts: Int = ATTEMPTS,
        private val pauseMs: Long = PAUSE_MS,
        private val wait: (Long) -> Unit = { Thread.sleep(it) },
    ) : WebProbe {

        override fun answers(port: Int): Boolean {
            for (attempt in 1..attempts) {
                if (inner.answers(port)) return true
                // No pause after the last one: a probe that fails is already the answer, and a final
                // wait would add `pauseMs` to every start on a device where nothing came up.
                if (attempt < attempts) wait(pauseMs)
            }
            return false
        }
    }

    /**
     * How many times the guest's port is asked before it is called not answering.
     *
     * **Six, at [PAUSE_MS] apart — about three seconds.** Apache under a path emulator reads its
     * configuration, creates `/var/run/apache2`, forks workers and binds; on a phone that is not
     * instant, and a single attempt would turn a slow boot into a report that says the guest is down.
     * Three seconds is also short enough that a start which is never coming is a status line and not
     * a wait, which is the same bound [omp.vm.guestapi.AgentUpdate] chose for the same reason: a boot
     * must not hang on something that is not going to happen.
     */
    const val ATTEMPTS: Int = 6

    /** The pause between two probes: 500 ms, six times, about three seconds in all. See [ATTEMPTS]. */
    const val PAUSE_MS: Long = 500L
}
