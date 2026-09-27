package omp.vm.provision

/**
 * What a provisioning run is doing, at one moment, in the two facts a notification and a browser
 * can both show: **which phase, and how many bytes of how many.**
 *
 * **It is a fact and not a progress bar.** [receivedBytes] is a count the download actually made and
 * [totalBytes] is the size [ArtifactManifest] pins for that artifact, so nothing here has to be
 * interpolated to be shown — and a percentage is deliberately absent, because a percentage of what
 * a caller is guessing at is how a progress bar becomes a lie. [line] prints the two byte counts
 * and lets the reader do the arithmetic, which is the same choice this project makes in `du -h` and
 * for the same reason.
 *
 * **A run that is not running has no line.** [line] is null whenever [running] is false, including
 * for a refusal and a cancellation: the notification this feeds is about bytes that are moving, and
 * a persistent notification that says "provisioning: refused" after the terminal has already said
 * it in full is a second, staler copy of a sentence the user has read. [outcome] is still there for
 * the state route, which is a page a person asked for and not a bar they did not.
 */
data class ProvisionStatus(
    /** Whether a run is in progress on this device right now. */
    val running: Boolean,
    /** `ro.product.cpu.abi`'s value for this device, or null when this run has not named one yet. */
    val abi: String?,
    /** The step, or null before the first step is reported. */
    val phase: Phase?,
    /** What is being fetched or unpacked, or null before the first artifact is named. */
    val artifact: String?,
    /** Bytes that have actually arrived, in total, including a resumed download's earlier bytes. */
    val receivedBytes: Long,
    /** The size the manifest pins for [artifact]. */
    val totalBytes: Long,
    /** How the last run ended, or null while one is running and before any has. */
    val outcome: ProvisionOutcome?,
) {

    /**
     * The one line a notification shows while a run is going, or null when none is.
     *
     * **The byte counts are [ArtifactManifest]'s own spelling**, the same `12.0 MiB (12,582,912
     * bytes)` a terminal line and a `help` line use, so a number read off a notification and a
     * number read off the screen are the same number and not two roundings of it.
     */
    fun line(): String? {
        if (!running) return null
        val doing = when (phase) {
            Phase.DOWNLOADING -> "downloading $artifact"
            Phase.VERIFYING -> "verifying $artifact"
            Phase.UNPACKING -> "unpacking $artifact"
            Phase.INSTALLED -> "installing $artifact"
            null -> "starting"
        }
        if (artifact == null || totalBytes <= 0L) return "provisioning: $doing"
        return "provisioning: $doing — ${ArtifactManifest.humanBytes(receivedBytes)} of " +
            ArtifactManifest.humanBytes(totalBytes)
    }

    companion object {
        /** Nothing has been started: the state of every device before its first `omp provision`. */
        val IDLE = ProvisionStatus(false, null, null, null, 0L, 0L, null)
    }
}

/**
 * The one place a provisioning run's progress is published, so the shell that starts it and the app
 * that outlives it are reading the same fact.
 *
 * **Why a holder and not a callback.** A [Provisioner] is given an `onProgress` by whoever calls it,
 * and the caller that exists is `omp provision` in a terminal — which the user can lock the phone
 * in the middle of, and which has no way to reach a notification. The run is the interesting part
 * and the shell is not there for all of it, so the run's own state is written somewhere both can
 * see, and the app reads it. Nothing in `:core` imports `android.*` to do it: this is a volatile
 * field and three functions, and the surfaces on top of it are the app's problem.
 *
 * **One slot, and a second run overwrites it.** This is not a queue and does not pretend to be: a
 * provisioning run is a foreground job on a terminal that takes one job, and two runs of 334 MB at
 * once is not a thing this app supports or a thing a user asked for. A run that starts while another
 * is going therefore replaces what the notification says, and the honest reading of that is "the run
 * you are watching is the one that is current".
 *
 * [finish] keeps the last run's [ProvisionStatus.abi], artifact and byte counts and only clears
 * [ProvisionStatus.running], because the state route shows how the run ended and an outcome with the
 * name of the artifact in it is worth more than a bare word.
 */
object ProvisionStatusHolder {

    /** The current fact. Read by the app; written by the run. `@Volatile` because they differ. */
    @Volatile
    var current: ProvisionStatus = ProvisionStatus.IDLE
        private set

    /** A run has started and has not fetched a byte yet. */
    fun begin(abi: String) {
        current = ProvisionStatus(true, abi, null, null, 0L, 0L, null)
    }

    /** One step, from the [Provisioner]'s own progress event and its own byte counts. */
    fun progress(abi: String, progress: Progress) {
        current = ProvisionStatus(
            running = true,
            abi = abi,
            phase = progress.phase,
            artifact = progress.artifact,
            receivedBytes = progress.receivedBytes,
            totalBytes = progress.totalBytes,
            outcome = null,
        )
    }

    /** The run ended, one way or another. Nothing is running afterwards, whatever the outcome. */
    fun finish(outcome: ProvisionOutcome) {
        current = current.copy(running = false, outcome = outcome)
    }

    /** Back to the state of a device that has never run this, which a test and a stop button want. */
    fun clear() {
        current = ProvisionStatus.IDLE
    }
}
