package omp.vm.provision

import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.Vfs
import omp.vm.rootfs.Rootfs
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** What a [Provisioner.provision] ended up doing. Every one of them is a line the user read. */
enum class ProvisionOutcome {
    /** Everything this ABI can have was already on the device. Nothing was fetched. */
    ALREADY_PROVISIONED,

    /** The rootfs and the agent are both here, or the one this ABI can have is. */
    PROVISIONED,

    /** Refused before a byte moved: no room, or nowhere legal to put it. */
    REFUSED,

    /** Stopped part way on purpose. What had arrived is kept, and the next run continues it. */
    CANCELLED,

    /** Stopped on a failure. The report says what was deleted and what was kept. */
    FAILED,
}

/** One thing happening, for whatever is drawing the progress. [receivedBytes] of 0 starts a phase. */
enum class Phase { DOWNLOADING, VERIFYING, UNPACKING, INSTALLED }

/**
 * A download's position, in bytes, against the size the manifest names for it.
 *
 * [receivedBytes] is 0 at the start of [Phase.DOWNLOADING], and that zero is the *only* moment at
 * which a user who does not want 293,246,344 bytes of mobile data can be asked: the whole feature
 * is behind one call, and this event is what a screen turns into a question.
 */
data class Progress(
    val artifact: String,
    val phase: Phase,
    val receivedBytes: Long,
    val totalBytes: Long,
) {
    /** 0f to 1f, for a bar. Zero when the server never said how big the artifact is. */
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (receivedBytes.toDouble() / totalBytes).toFloat()
}

/**
 * What happened, and every line that says so, in the order it happened.
 *
 * A report rather than an exception, because every caller of this is a user-facing screen and a
 * progress bar cannot render a stack trace. The [lines] are written the way this project writes to
 * a terminal: a fact, a real number, and — where a download was thrown away — what was thrown away
 * and why.
 */
data class ProvisionReport(
    val outcome: ProvisionOutcome,
    val manifest: ArtifactManifest,
    val lines: List<String>,
)

/**
 * Downloads a Debian and the real `omp` agent, and puts them where this app is allowed to execute
 * them. Every step says what it did.
 *
 * **The order of this class is the order of the promises it makes, and the promises are the reason
 * it is written this way:**
 *
 * 1. **Nothing moves before there is somewhere to put it.** The install directory is created and
 *    the free space is asked for through [omp.shell.fs.Vfs.diskUsage] first, because a check before
 *    293,246,344 bytes of mobile data is worth more than a failure after it. Both refusals name
 *    the number that was short.
 * 2. **Nothing is unpacked before it is verified.** A digest mismatch deletes the partial file and
 *    says so; a length mismatch on an artifact with no pinned digest is treated the same way, since
 *    a truncated archive unpacked into a Debian is a bug that surfaces days later as a missing
 *    library.
 * 3. **A partial download is a partial download.** It is named `.part`, the state file records how
 *    much of it there is, and the next call asks the server for a `Range` from exactly there. A
 *    cancelled or failed download is never recorded as a finished one — the state file only says
 *    `done` after the artifact is installed — because the worst outcome in a download that runs on
 *    a phone's data is a resume that starts again from zero on the fifth attempt.
 * 4. **Every unpacked member is checked against the directory it is going into**, because a tar
 *    member called `../../files/agent/openai.key` is a real thing on the wire and this is the code
 *    that meets it. See [TarEntry.relativeName].
 *
 * **What this class does not do.** It does not fork, it does not launch anything, and it does not
 * know that proot exists: [ProotCommand] builds the argument vector and [ProotLauncher] — which has
 * no implementation in `:core`, on purpose — is the only thing that would run it. It also does not
 * check that a downloaded Debian is *bootable*; the honest limit of everything below is that the
 * bytes have been fetched, verified against what this build pins, and unpacked, and whether a
 * particular proot build runs a particular kernel is a question with no answer on a JVM.
 *
 * @param onProgress called on a background thread, every 64 KiB and once per phase change.
 * @param cancelled polled at the same rate, and once before the first byte. A read already parked
 *   inside a socket is not interrupted by this class, which is the same limit this project already
 *   states about `Ctrl-C` on a quiet answer: the flag is honoured, the syscall is not.
 */
class Provisioner(
    val paths: ProvisionPaths,
    private val vfs: Vfs,
    private val transport: Transport,
    private val onProgress: (Progress) -> Unit = {},
    private val cancelled: () -> Boolean = { false },
) {

    /** What is on this device right now, from the disk and nothing else. */
    fun state(): ProvisionState = paths.state(vfs)

    /**
     * Fetch, verify and unpack everything [manifest] names that is not already here.
     *
     * Idempotent in the way the user means it: a second call on a provisioned device downloads
     * nothing, because what counts as provisioned is read off the disk — a marker inside the rootfs
     * and the agent binary itself — and not out of the state file, which a user, a backup or a
     * `vm reset` can take away without touching 224 MB of anybody's data.
     */
    fun provision(manifest: ArtifactManifest): ProvisionReport {
        val log = Log()
        val outcome = try {
            run(manifest, log)
        } catch (stop: Halt) {
            log.say(stop.line)
            stop.outcome
        } catch (e: IOException) {
            // A truncated gzip stream and a full disk both arrive here, and both are a thing a
            // report can say. The class's promise is a report for every outcome, so an exception
            // out of this method is a bug in the class rather than a fact about the download.
            log.say("${manifest.abi.abiName}: the download or the unpack failed: ${e.message}.")
            ProvisionOutcome.FAILED
        }
        return ProvisionReport(outcome, manifest, log.lines)
    }

    private fun run(manifest: ArtifactManifest, log: Log): ProvisionOutcome {
        val rootfs = manifest.rootfs ?: throw Halt(
            ProvisionOutcome.REFUSED,
            "${manifest.abi.abiName}: ${ArtifactManifest.NO_ROOTFS} Nothing was downloaded.",
        )
        val before = state()
        if (before.rootfsInstalled && (!manifest.needsAgent || before.agentInstalled)) {
            log.say(
                "${manifest.abi.abiName} is already provisioned: the Debian is at ${paths.rootfsDir} " +
                    "and" + if (manifest.needsAgent) " the agent at ${paths.agentBinary}." else
                    " this ABI has no agent build, so there is nothing else to have.",
            )
            return ProvisionOutcome.ALREADY_PROVISIONED
        }
        ensureDirectories()
        // The installed figure, not the compressed one: a run that downloads 293 MB and then
        // unpacks and installs 500 MB is the case that fills a phone, and a check made against
        // the download alone would pass it and then fail it half way through.
        room(manifest.requiredBytes, "the download and what it installs")

        if (!before.rootfsInstalled) install(rootfs, unpack = true, log)
        manifest.agent?.let { if (!state().agentInstalled) install(it, unpack = false, log) }
        return ProvisionOutcome.PROVISIONED
    }

    /**
     * The two directories this layer writes into, and the reason each one refuses by name.
     *
     * The **target** is where the payload is unpacked and it is the app's own storage in the
     * ordinary case: the Debian and the agent are *read* by proot's emulated loader rather than
     * exec'd at a real path, so nothing about them needs the platform's executable-content rules.
     * A device that cannot be written there anyway — a full disk, a `data` partition mounted
     * read-only, an OEM that puts `files/` somewhere unusual — has to be told so in this app's own
     * words, because the `Errno` the filesystem throws back says nothing a user can act on.
     *
     * The **download** directory is the same app storage, for a partial download: also data, also
     * a few hundred megabytes of somebody's data, and a file that wants to survive a reboot.
     */
    private fun ensureDirectories() {
        try {
            mkdirs(vfs, paths.installDir)
        } catch (e: FsException) {
            throw Halt(
                ProvisionOutcome.REFUSED,
                "the target directory (${paths.targetDir}) cannot be written: ${e.errno.text}. " +
                    "That is where the Debian and the agent are unpacked, and a download that " +
                    "cannot be unpacked anywhere is not worth starting. Nothing was downloaded.",
            )
        }
        try {
            mkdirs(vfs, paths.downloadDir)
        } catch (e: FsException) {
            throw Halt(
                ProvisionOutcome.REFUSED,
                "the app's own files directory (${paths.workDir}) cannot be written: " +
                    "${e.errno.text}. That is where a download in progress is kept so it can be " +
                    "resumed, and there is nowhere else for it to go. Nothing was downloaded.",
            )
        }
    }

    /** One artifact, end to end: resume, fetch, verify, install. */
    private fun install(artifact: Artifact, unpack: Boolean, log: Log) {
        val partial = paths.partial(artifact.name)
        var have = size(partial)
        if (have >= artifact.sizeBytes) {
            log.say("${artifact.name}: ${ArtifactManifest.humanBytes(have)} is already downloaded; verifying it")
        } else {
            have = fetch(artifact, partial, have, log)
        }
        verify(artifact, partial, have, log)
        if (unpack) unpack(artifact, partial, log) else place(artifact, partial, log)
    }

    /**
     * The download, resumed or started, and the two things that can go wrong inside it.
     *
     * The connection is asked for a `Range` from [have] and the answer is believed only when the
     * body actually starts there: a server that answers 200 to a ranged request is sending the
     * whole file, and appending that to a half-written one would produce a file of exactly the
     * right size and entirely the wrong bytes — the one mistake in here that a length check cannot
     * see and a digest has to catch 55 MB later.
     */
    private fun fetch(artifact: Artifact, partial: String, have: Long, log: Log): Long {
        log.say(
            "fetching ${artifact.name} — ${ArtifactManifest.humanBytes(artifact.sizeBytes)} from " +
                artifact.url + if (have > 0L) ", resuming at ${ArtifactManifest.humanBytes(have)}." else ".",
        )
        onProgress(Progress(artifact.name, Phase.DOWNLOADING, 0L, artifact.sizeBytes))
        if (cancelled()) {
            throw Halt(ProvisionOutcome.CANCELLED, "cancelled before a byte of ${artifact.name} moved.")
        }
        var written = 0L
        var start = 0L
        val connection = transport.open(artifact.url, have)
        connection.use {
            if (connection.status !in 200..299) {
                throw Halt(
                    ProvisionOutcome.FAILED,
                    "${artifact.name}: the server answered HTTP ${connection.status} for ${artifact.url}. " +
                        "Nothing was unpacked.",
                )
            }
            val resumed = connection.from == have && have > 0L
            if (!resumed && have > 0L) {
                log.say(
                    "${artifact.name}: the server sent the whole file instead of the range, so the " +
                        "${ArtifactManifest.humanBytes(have)} already fetched are not used.",
                )
            }
            start = if (resumed) have else 0L
            val meter = Meter(vfs.openWrite(partial, resumed), artifact, start)
            try {
                connection.body().use { body -> body.copyTo(meter, BUFFER) }
            } catch (stopped: Cancelled) {
                meter.flushQuietly()
                record(artifact.name, start + meter.written, PARTIAL)
                throw Halt(
                    ProvisionOutcome.CANCELLED,
                    "cancelled after ${ArtifactManifest.humanBytes(start + meter.written)} of " +
                        "${artifact.name}; what arrived is kept and the next run continues from there.",
                )
            } catch (broken: IOException) {
                // A radio dropping a 293 MB transfer is not an edge case on a phone, it is the
                // normal one, and it is the answer a resume exists for: say what arrived, keep it,
                // and name the artifact rather than unwinding with an exception nobody can read.
                meter.flushQuietly()
                record(artifact.name, start + meter.written, PARTIAL)
                throw Halt(
                    ProvisionOutcome.FAILED,
                    "${artifact.name}: the connection broke after " +
                        "${ArtifactManifest.humanBytes(start + meter.written)} — " +
                        "${broken.message}. What arrived is kept and the next run continues from there.",
                )
            } finally {
                meter.close()
            }
            written = meter.written
        }
        val total = start + written
        if (total > artifact.sizeBytes) {
            discard(partial, artifact)
            throw Halt(
                ProvisionOutcome.FAILED,
                "${artifact.name}: ${ArtifactManifest.humanBytes(total)} arrived but the manifest " +
                    "names ${ArtifactManifest.humanBytes(artifact.sizeBytes)}; the download was deleted.",
            )
        }
        record(artifact.name, total, PARTIAL)
        if (total < artifact.sizeBytes) {
            throw Halt(
                ProvisionOutcome.FAILED,
                "${artifact.name}: the connection ended after ${ArtifactManifest.humanBytes(total)} of " +
                    "${ArtifactManifest.humanBytes(artifact.sizeBytes)}; what arrived is kept and the " +
                    "next run continues from there.",
            )
        }
        return total
    }

    /**
     * The digest, compared before anything is unpacked, and the deletion that follows a mismatch.
     *
     * A partial file that survives a failed verification is the one artefact a later run would
     * mistake for a finished one, so a mismatch deletes it outright: the bytes are known to be
     * wrong, and keeping them only makes the next attempt fail again for a reason the user cannot
     * see.
     */
    private fun verify(artifact: Artifact, partial: String, have: Long, log: Log) {
        onProgress(Progress(artifact.name, Phase.VERIFYING, 0L, artifact.sizeBytes))
        val expected = artifact.sha256
        if (expected == null) {
            log.say(
                "${artifact.name}: ${ArtifactManifest.humanBytes(have)}, and no sha256 is pinned for it " +
                    "in this build, so it is taken as it arrived over HTTPS and unpacked as it is.",
            )
            return
        }
        val digest = digest(partial)
        if (!digest.equals(expected, ignoreCase = true)) {
            discard(partial, artifact)
            throw Halt(
                ProvisionOutcome.FAILED,
                "${artifact.name}: sha256 is $digest but the manifest says $expected. The download " +
                    "was deleted, nothing was unpacked, and the next run starts it again.",
            )
        }
        log.say("${artifact.name}: sha256 ${digest.take(16)}… matches the manifest.")
    }

    /**
     * The gzip'd tar into the install directory, one member at a time.
     *
     * Every member's path is resolved against the target before anything is created, and a member
     * that would leave it ends the unpack with the member's own name in the report. The archive
     * is kept when that happens, because the bytes may be exactly what the manifest promised and
     * the name in it is the thing that is wrong; the line says so rather than spending another
     * 55 MB to find out again.
     */
    private fun unpack(artifact: Artifact, archive: String, log: Log) {
        val target = paths.rootfsDir
        room(artifact.sizeBytes, "the unpack")
        onProgress(Progress(artifact.name, Phase.UNPACKING, 0L, artifact.sizeBytes))
        var files = 0
        var links = 0
        var skipped = 0
        vfs.openRead(archive).use { raw ->
            GZIPInputStream(BufferedInputStream(raw, BUFFER)).use { gz ->
                TarReader(gz).use { tar ->
                    while (true) {
                        val entry = tar.next() ?: break
                        val name = entry.relativeName() ?: throw Halt(
                            ProvisionOutcome.FAILED,
                            "${artifact.name}: the archive holds a member named '${entry.name}', which " +
                                "is not inside the directory it is being unpacked into. Nothing was " +
                                "unpacked and the download was kept; delete ${paths.partial(artifact.name)} " +
                                "to get rid of it.",
                        )
                        val path = "$target/$name"
                        when {
                            entry.isDirectory -> mkdirs(vfs, path)
                            entry.isFile -> {
                                mkdirs(vfs, parentOf(path))
                                vfs.openWrite(path, false).use { out -> tar.copyContentTo(out) }
                                Rootfs.setMode(File(HOST_ROOT), path, entry.mode)
                                files++
                            }
                            entry.isSymlink -> {
                                mkdirs(vfs, parentOf(path))
                                try {
                                    vfs.symlink(entry.linkName, path)
                                    links++
                                } catch (e: FsException) {
                                    // A rootfs unpacked twice over an existing tree meets its own links.
                                    if (e.errno != FsErrno.FILE_EXISTS) throw e
                                }
                            }
                            else -> {
                                // Hard links, device nodes and fifos: the Vfs has no link(2) and an
                                // app has no mknod(2), so they are counted rather than faked.
                                tar.skip()
                                skipped++
                            }
                        }
                    }
                }
            }
        }
        val sb = StringBuilder("unpacked ${artifact.name} into $target: ")
        sb.append("$files files, $links symlinks")
        if (skipped > 0) sb.append(", $skipped members this app cannot create and does not fake")
        log.say(sb.toString() + ".")
        vfs.writeBytes(
            paths.rootfsMarker,
            "omp-provisioned ${ArtifactManifest.DEBIAN_POINT_RELEASE} ${artifact.name}\n".toByteArray(),
        )
        // 55 MB of archive that has been unpacked is 55 MB the next `df` a user runs will show,
        // and the install is now decided by the marker inside the tree rather than by this file's
        // length.
        discard(archive, artifact)
    }

    /**
     * The agent binary, moved out of the download directory and given its executable bit.
     *
     * It is *not* exec'd at this path by the kernel — proot's loader execs the binary it has
     * mapped, from the exec directory — but the loader still needs the mode to be right, and a
     * 0644 file is a file the loader refuses. The bit is set through [Rootfs.setMode], the one
     * host-side permission call this project has, which the namespace VM already uses for its own
     * program files: the [Vfs] has no `chmod`, and adding one would change an interface the whole
     * shell shares. The check afterwards is `canExecute` rather than the return of a setter, so a
     * mode that did not take is a sentence this layer owes the user rather than a `false`.
     */
    private fun place(artifact: Artifact, partial: String, log: Log) {
        onProgress(Progress(artifact.name, Phase.INSTALLED, 0L, artifact.sizeBytes))
        mkdirs(vfs, paths.agentDir)
        try {
            vfs.rename(partial, paths.agentBinary)
        } catch (e: FsException) {
            // Across filesystems a rename is a copy, and the seam reports it as a failure. Both
            // directories are on /data on a stock device, so this is the rare path, not the common.
            vfs.openWrite(paths.agentBinary, false).use { out ->
                vfs.openRead(partial).use { input -> input.copyTo(out, BUFFER) }
            }
            forget(artifact.name)
        }
        Rootfs.setMode(File(HOST_ROOT), paths.agentBinary, Rootfs.EXECUTABLE_MODE)
        if (!File(paths.agentBinary).canExecute()) {
            throw Halt(
                ProvisionOutcome.FAILED,
                "${artifact.name} is at ${paths.agentBinary} but the filesystem refused to set its " +
                    "executable bit, and proot's loader execs the binary it has mapped, so it will " +
                    "not run. The bytes are still correct; the mode is not.",
            )
        }
        log.say(
            "installed ${artifact.name} at ${paths.agentBinary}, mode 0755, in app-private storage: " +
                "it is read there, never exec'd by the kernel. The executable is proot and the " +
                "loader beside it, which come from " +
                (paths.execDirectory ?: "a directory this device has not told us about") +
                " and are packaged in the APK rather than downloaded.",
        )
    }

    /**
     * **It is made against the room a first run occupies, not against what it downloads.** Those
     * are different numbers by 130% on an arm64 phone, and only the second one can fill a disk: the
     * rootfs is unpacked, the guest installs LAMP out of the Debian archive, and that install is
     * 376.6 MiB of files before a single request has been made. A check against the download alone
     * would pass a device that then runs out of room in the middle of an unpack, which is the exact
     * failure this check exists to prevent.
     *
     * **And it is still only a floor, against a limit nobody has measured.** The rootfs's own
     * unpacked size is not in [ArtifactManifest.requiredBytes] because it has not been measured,
     * and a number invented to fill that hole is the same defect this class exists to avoid — the
     * arithmetic for why no factor is applied is written down once, on that property, rather than
     * here and once more somewhere else.
     *
     * A device may also refuse the write at a level no amount of asking can see: a quota, a `data`
     * partition that fills from another app while this one downloads, an OEM that reports one
     * filesystem and mounts another, a write that succeeds and is then truncated.
     *
     * So the answer is a *refusal before the bytes*, and every failure below it is reported in
     * this app's own words: the `Errno` a filesystem throws back is a word like `No space left on
     * device`, with no path, no number and nothing a user can act on.
     */
    private fun room(bytes: Long, what: String) {
        val free = try {
            vfs.diskUsage(paths.targetDir).freeBytes
        } catch (e: FsException) {
            return // Nothing to ask, and refusing on a filesystem that will not answer is its own lie.
        }
        if (free >= bytes) return
        throw Halt(
            ProvisionOutcome.REFUSED,
            "not enough room: $what needs ${ArtifactManifest.humanBytes(bytes)} and " +
                "${ArtifactManifest.humanBytes(free)} is free. Nothing was downloaded.",
        )
    }

    /**
     * The resume record: one line per artifact, `name received status`, and a comment on the first
     * line so that a file a user opens in a text editor says what it is.
     *
     * Losing it costs a re-stat and nothing else, which is the property the [Provisioner]'s own
     * KDoc promises: the offset a resume uses is the length of the `.part` file, so this file is a
     * record of what happened rather than the thing that makes resuming possible.
     */
    private fun record(name: String, received: Long, status: String) {
        val records = readRecords().toMutableMap()
        records[name] = "$received $status"
        vfs.writeBytes(paths.stateFile, render(records).toByteArray())
    }

    private fun forget(name: String) {
        try {
            val records = readRecords().toMutableMap()
            if (records.remove(name) == null) return
            vfs.writeBytes(paths.stateFile, render(records).toByteArray())
        } catch (e: FsException) {
            // A state file that was never written is not a problem: there was nothing to forget.
        }
    }

    private fun discard(partial: String, artifact: Artifact) {
        forget(artifact.name)
        try {
            vfs.delete(partial)
        } catch (e: FsException) {
            // Nothing useful to add: the report already says the bytes are not to be trusted.
        }
    }

    /** The resume record, through [ProvisionPaths.records] — one parser for one format. */
    private fun readRecords(): Map<String, String> = paths.records(vfs)

    private fun render(records: Map<String, String>): String = buildString {
        appendLine("#omp-provision/v1 — name received status; a .part file's own length is the offset")
        for ((name, value) in records) {
            append(name)
            append(' ')
            appendLine(value)
        }
    }

    private fun size(path: String): Long = try {
        vfs.stat(path).size
    } catch (e: FsException) {
        0L
    }

    private fun digest(path: String): String {
        val sha = MessageDigest.getInstance("SHA-256")
        vfs.openRead(path).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sha.update(buffer, 0, read)
            }
        }
        return buildString(32) {
            for (byte in sha.digest()) append(HEX[(byte.toInt() and 0xff) shr 4]).append(HEX[byte.toInt() and 0x0f])
        }
    }

    private fun progress(artifact: String, phase: Phase, received: Long, total: Long) {
        onProgress(Progress(artifact, phase, received, total))
    }


    private inner class Meter(
        private val out: OutputStream,
        private val artifact: Artifact,
        private val start: Long,
    ) : OutputStream() {
        var written: Long = 0L
            private set

        override fun write(one: Int) {
            check()
            out.write(one)
            moved(1L)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check()
            out.write(bytes, offset, length)
            moved(length.toLong())
        }

        override fun flush() {
            out.flush()
        }

        override fun close() {
            out.close()
        }

        fun flushQuietly() {
            try {
                flush()
            } catch (e: IOException) {
                // The connection is going away anyway; the report is the part that has to survive.
            }
        }

        private fun moved(count: Long) {
            written += count
            progress(artifact.name, Phase.DOWNLOADING, start + written, artifact.sizeBytes)
        }

        /** Polled per buffer, which is why the buffer is 64 KiB and not 4 KiB. */
        private fun check() {
            if (cancelled()) throw Cancelled()
        }
    }

    private class Log {
        val lines = ArrayList<String>()

        fun say(line: String) {
            lines += line
        }
    }

    /** A refusal, a cancellation or a failure: the one way out of the middle of a step. */
    private class Halt(val outcome: ProvisionOutcome, val line: String) : Exception(line)

    private class Cancelled : RuntimeException()

    companion object {
        /** The read size everywhere in this class, and the rate at which a cancel is noticed. */
        const val BUFFER = 64 * 1024

        private const val PARTIAL = "partial"
        /**
         * The host of every [Rootfs.setMode] call in this class. Every path handed to it here is
         * absolute and already inside the app's own storage, so the device root is the only host
         * that is not a second opinion about where the file is.
         */
        const val HOST_ROOT = "/"
        private const val HEX = "0123456789abcdef"
    }
}

/**
 * `mkdir -p` through the seam: every level, and an existing directory is not a failure.
 *
 * Internal rather than private because [WebRoot] creates a document root inside the rootfs the
 * same way, and a second implementation of `mkdir -p` is a second thing to get subtly wrong.
 */
internal fun mkdirs(vfs: Vfs, path: String) {
    var current = ""
    for (part in path.trim('/').split('/')) {
        if (part.isEmpty()) continue
        current += "/$part"
        try {
            vfs.mkdir(current)
        } catch (e: FsException) {
            if (e.errno != FsErrno.FILE_EXISTS) throw e
        }
    }
}

/** The directory holding [path], which is what a member's parent has to be created as. */
private fun parentOf(path: String): String = path.substringBeforeLast('/')
