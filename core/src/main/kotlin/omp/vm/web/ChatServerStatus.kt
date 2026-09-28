package omp.vm.web

/**
 * What the app's own loopback server is doing, in the two facts anybody ever asks about it: whether
 * it is up, and, when it is not, the sentence the platform said about why.
 *
 * **A state and a reason, and not a boolean.** A `false` on its own is the shape of the bug this
 * exists for: `startForeground` threw a [SecurityException] out of `Service.onCreate`, the process
 * died during launch, and everything that reports the service afterwards could only have said "not
 * running" — which is a true sentence about a symptom and no use at all to whoever has to fix it.
 * [ChatServerState.REFUSED] plus [refusal] is the difference between a report and a shrug.
 */
enum class ChatServerState {
    /** No Activity has asked for the service yet on this launch or any other. */
    NOT_STARTED,

    /** Asked for, and the foreground notification is up; the socket is being bound. */
    STARTING,

    /** Bound a port and answering on it. */
    RUNNING,

    /**
     * Not running and nobody said why: the user stopped it from the notification, or `web stop`.
     *
     * **Separate from [REFUSED] rather than the same state with an empty reason**, because the two
     * call for different answers — a stop is what the user asked for, and a refusal is a bug to
     * paste into a report.
     */
    STOPPED,

    /** The platform would not let it become a foreground service, and [ChatServerStatus.refusal] is what it said. */
    REFUSED,
}

/**
 * One reading of the app's own web front end, as the reporters see it.
 *
 * **[url] is the address that was actually bound**, with the port the OS gave it, for the same
 * reason `omp doctor` reads it off the file the service wrote and not off a constant: this server
 * falls back to an ephemeral port, so a port printed from a preference is a port that might not be
 * listening.
 *
 * **[line] is null for every state that is not [ChatServerState.REFUSED]**, and that is the whole
 * contract: a healthy server has a URL to print and a stopped one has a stop the user asked for,
 * so a sentence here would be noise. What is missing is a sentence — a refusal is the one case
 * where "not running" and "here is why" are the same answer.
 */
data class ChatServerStatus(
    val state: ChatServerState,
    val url: String? = null,
    val refusal: String? = null,
) {

    /** Whether something is answering on [url] right now, as far as this service instance knows. */
    val running: Boolean get() = state == ChatServerState.RUNNING

    /** Whether this is the degraded case: refused by the platform, with the reason kept. */
    val degraded: Boolean get() = state == ChatServerState.REFUSED

    /**
     * The one sentence a reporter prints for a degraded server, or null when there is nothing to
     * report beyond the state itself.
     *
     * **It names the state and quotes the platform's own words**, because "the chat server did not
     * start" is what the user already knows and `SecurityException: Starting FGS with type
     * specialUse ... requires FOREGROUND_SERVICE_SPECIAL_USE` is the sentence that names the file
     * to open.
     */
    fun line(): String? = when (state) {
        ChatServerState.REFUSED -> "not running: the platform refused the foreground service: " +
            (refusal ?: "no reason was given")
        else -> null
    }

    companion object {
        /** Nothing has been asked for: the state of a device that has not opened the app yet. */
        val IDLE = ChatServerStatus(ChatServerState.NOT_STARTED)
    }
}

/**
 * The one place this app's own chat server publishes what it is doing, so the Activity that starts
 * it, the service that runs it and `omp doctor` in the shell all read the same fact.
 *
 * **The same shape as [omp.vm.provision.ProvisionStatusHolder] and for the same reason.** The run
 * that fills that slot happens in a terminal and is reported by a notification; this one happens
 * inside a `Service.onCreate` on the main thread and is reported by `web`, by `omp doctor` and by
 * whatever the Activity decides to say. In both cases the writer is somewhere the reader cannot
 * call, and in both cases the answer is a `@Volatile` field and a few functions rather than a
 * callback: a thread parked in somebody else's read cannot be pushed to, and a listener list would
 * have to be kept alive by a service whose whole failure mode here is not being alive.
 *
 * **One slot, and a later service instance overwrites an earlier one.** There is one chat server
 * per process, and a second instance only exists because the first was destroyed; the honest
 * reading of the slot is "the service as it last stood in this process", not a history.
 *
 * **A refusal outlives the [ChatServerStatus.STOPPED] that follows it.** The service that records
 * a refusal stops itself in the same breath, so any rule that cleared the reason on the way out
 * would erase the only record of the failure the process is ever going to have — and the question
 * that record answers ("why is it not running?") is asked after it stopped, not during.
 */
object ChatServerStatusHolder {

    /** The current fact. Written by the service; read by the Activity, `web` and `omp doctor`. */
    @Volatile
    var current: ChatServerStatus = ChatServerStatus.IDLE
        private set

    /** An Activity has asked for the service, which has not bound anything yet. */
    fun starting() {
        current = ChatServerStatus(ChatServerState.STARTING)
    }

    /** The server is bound and answering on [url]. */
    fun bound(url: String) {
        current = ChatServerStatus(ChatServerState.RUNNING, url)
    }

    /**
     * The platform would not let this service run in the foreground, and [reason] is what it said.
     *
     * **The reason is the platform's own message and not a paraphrase of it**, because the message
     * names the permission and the paraphrases in this file are the ones that were wrong once.
     */
    fun refused(reason: String) {
        current = ChatServerStatus(ChatServerState.REFUSED, refusal = reason)
    }

    /**
     * The service is gone: a stop the user asked for, or the end of one that never ran.
     *
     * **A [ChatServerState.REFUSED] is left alone**, so a device that refused this service keeps
     * saying why until the next launch asks again.
     */
    fun stopped() {
        if (current.state != ChatServerState.REFUSED) {
            current = ChatServerStatus(ChatServerState.STOPPED)
        }
    }

    /** Back to the state of a device that has never opened the app, which is what a test wants. */
    fun clear() {
        current = ChatServerStatus.IDLE
    }
}
