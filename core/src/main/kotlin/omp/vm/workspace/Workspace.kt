package omp.vm.workspace

import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.RealVfs
import omp.shell.fs.VEntry
import omp.shell.fs.VNodeType
import omp.shell.fs.Vfs
import java.util.LinkedHashMap

/**
 * What is known about a conversation's `.omp-workspace` file, which is the difference between
 * "this folder is not one of ours" and "this folder is ours and something is wrong with it".
 *
 * A boolean cannot say that, and getting it wrong is worse than not having it: a folder omp
 * created, whose metadata a sync tool or a permission change made unreadable, came out of a
 * boolean as `(not made by omp)` — a positive claim that the user's own folder was not made by
 * this app. So the state is carried, and the listing says which of the four it is.
 */
enum class MetadataState {
    /** Read back, and carrying a version this class wrote. */
    RECORDED,

    /** No metadata file at all: a folder the user made by hand, or an old one. */
    NONE,

    /** A file is there but is not this format — hand-written, or rewritten by something else. */
    CORRUPT,

    /**
     * A file is there and cannot be read — denied, or a directory where a file should be. Distinct
     * from [NONE] because the folder is still ours and the user must not be told otherwise.
     */
    UNREADABLE,
}

/**
 * One conversation: one folder, which is also the project it works on.
 *
 * The directory *is* the name. A user renames a folder in the Android file manager and the next
 * listing has to show the new name, because that is what the folder is now — which is why [name]
 * comes from the filesystem's own listing and never from the metadata file, in [list] and in [open]
 * alike. The name the app originally gave it is kept in `.omp-workspace` as history and is never
 * allowed to override what is on disk.
 *
 * `path` and `hostPath` are the same directory seen twice, and both exist because a user asks both
 * questions. Inside the VM the only true name of a conversation is [path] — `/mnt/omp/notes` — and
 * it is what a command takes and what a program writes to. But the whole point is that the bytes
 * are **not** in the app's private sandbox, and a user who wants them from a file manager, a USB
 * cable or another app needs [hostPath] — `/storage/emulated/0/Documents/omp/notes`. A tool that
 * printed only the namespace path would be describing the sandbox in the user's own language; one
 * that printed only the host path would be reaching around the [Vfs], which is the boundary this
 * design is built on.
 */
data class Entry(
    /** The directory name, as the filesystem reports it right now. The directory owns this. */
    val name: String,
    /** The namespace path, e.g. `/mnt/omp/notes` — what a command inside the VM uses. */
    val path: String,
    /** The real path on the phone, e.g. `/storage/emulated/0/Documents/omp/notes`. */
    val hostPath: String,
    /** When the conversation was created, in epoch millis. Falls back to the directory's own mtime. */
    val createdMillis: Long,
    /** The directory's last modification, which is what the listing orders by. */
    val modifiedMillis: Long,
    /** What is known of the metadata file, and the reason a listing needs rather than a yes. */
    val meta: MetadataState,
) {
    /** Whether this is a conversation omp itself created, which is the question most callers ask. */
    val hasMetadata: Boolean
        get() = meta == MetadataState.RECORDED
}

/**
 * The lifecycle of the folders that hold conversations — the data model, the naming, the metadata
 * file and the listing — over a [Vfs] and nothing else.
 *
 * **A conversation is a plain directory, and that directory is the project.** There is no index, no
 * registry, no database and no per-conversation subfolder inside a project: the user picks a folder
 * to continue working in, or asks for a new one, and either way they are working in that folder.
 * The directories *are* the list, which is why a conversation survives a reboot of the app with no
 * restore step, and why a file manager on the phone shows exactly what this class shows. The one
 * extra file inside a conversation is `.omp-workspace`, which records what the app knows about a
 * folder it created so a later run can tell its own work from the user's.
 *
 * Every project this app makes lives in one container directory, `omp`, so it never scatters folders
 * across the user's `Documents` and so "forget everything" is a single folder to delete. [root] is
 * that folder in the VM's namespace (`/mnt/omp`); the same directory on the phone is
 * `/storage/emulated/0/Documents/omp`, which the user reaches as *Internal storage ▸ Documents ▸
 * omp*. The namespace is a bind, not a copy: `/mnt/omp/notes` **is**
 * `/storage/emulated/0/Documents/omp/notes`, so what the user sees in the file manager and what they
 * see in the VM are the same folder. [hostRoot] is where the bytes really are, and it is not a
 * string anyone may disagree with: it is asked of the [Vfs] itself whenever the [Vfs] knows, and
 * the constructor's value is only what a filesystem that cannot answer had to be told. There is no
 * `java.io.File` in this package — a folder the user can see in a file manager has to be one this
 * code reached through the same seam a program in the VM reached.
 *
 * [now] is the clock, injected like everything else here, so the generated name and the `created`
 * stamp are the same millisecond and so a test can name a session in 2021.
 *
 * **This package is the model, and it has no caller in the app yet.** The command that lists and
 * creates is the next thing to be written against it, which is why the keys it will fill in — the
 * [DISTRO] it ran and the [TITLE] the user gave it — are defined here rather than being left to be
 * invented twice. Everything in it is exercised by tests over a real filesystem.
 */
class Workspace(
    val vfs: Vfs,
    val root: String,
    /**
     * What [hostRoot] is when the [vfs] cannot say for itself. A caller with a [RealVfs] is
     * ignored here — the bind map is the authority — and a caller with any other [Vfs] has nothing
     * else to go on. Passing the right one is therefore the caller's job, and the only thing that
     * can be wrong about it is a path the user would be sent to look in.
     */
    declaredHostRoot: String,
    val now: () -> Long,
) {

    /**
     * The real directory [root] is, as the filesystem itself says.
     *
     * A [RealVfs] holds the bind map and answers exactly, and a bind really is a promise: `/mnt/omp`
     * is that directory and no other. A device-rooted [RealVfs] asked about a path with no bind
     * answers with the path itself, which is also true — on such a filesystem the namespace path
     * *is* the real path. Only a [Vfs] that is neither answers nothing, and there the caller's
     * string stands, because inventing a location for a folder nobody can see would be worse than
     * believing the one name we were given.
     */
    val hostRoot: String = hostPathOf(root) ?: declaredHostRoot

    /**
     * Every conversation under [root], newest first, with the name as the tiebreak so the order is
     * total and two runs of the same command print the same table — a listing whose order depends on
     * the order the filesystem hands names back is a listing a user cannot talk about.
     *
     * Anything that is not a directory is skipped. A directory with no usable metadata is listed
     * anyway, with its creation time taken from the directory itself and the reason in [Entry.meta].
     * The listing is deliberately the operation here that is hardest to make fail, because the only
     * thing worse than a slightly wrong listing is no listing at all.
     *
     * @throws FsException [FsErrno.NO_SUCH_FILE] when [root] itself is not there. That one case is
     * worth refusing over: a caller that asked what exists needs to be told the directory it asked
     * about is missing, not that there is nothing yet. Every other errno is reported as itself — a
     * root that cannot be read is a phone without the all-files grant, and telling such a user their
     * container is missing sends them to create a conversation that will fail the same way.
     */
    fun list(): List<Entry> {
        // readDir reports a missing directory as NO_SUCH_FILE already; saying it here makes this
        // method's contract its own rather than inherited from a call three frames down, and
        // refusing to translate anything else is what keeps "does not exist" meaning that.
        val rootStat = try {
            vfs.stat(root)
        } catch (e: FsException) {
            if (e.errno != FsErrno.NO_SUCH_FILE) throw e
            throw FsException(FsErrno.NO_SUCH_FILE, root)
        }
        if (rootStat.type != VNodeType.DIRECTORY) throw FsException(FsErrno.NOT_A_DIRECTORY, root)
        val out = ArrayList<Entry>()
        for (child in vfs.readDir(root)) {
            if (child.stat.type != VNodeType.DIRECTORY) continue
            val path = child(root, child.name)
            // The stat came back with the listing: one read per entry, never a second.
            val (meta, fields) = metadataOf(path)
            out += Entry(
                name = child.name,
                path = path,
                hostPath = child(hostRoot, child.name),
                // Only a file this class wrote is believed about when a conversation was created.
                // A hand-written `created=` is somebody's guess, and the directory's own mtime is
                // at least a fact about the directory.
                createdMillis = if (meta == MetadataState.RECORDED) {
                    fields[CREATED]?.toLongOrNull() ?: child.stat.mtimeMillis
                } else {
                    child.stat.mtimeMillis
                },
                modifiedMillis = child.stat.mtimeMillis,
                meta = meta,
            )
        }
        out.sortWith(compareByDescending<Entry> { it.modifiedMillis }.thenBy { it.name })
        return out
    }

    /**
     * A new conversation directory, and its metadata file.
     *
     * A null or blank [name] means the user asked for a new one without typing a name, which is the
     * common case, so the name is generated from [now]. An explicit name goes through
     * [WorkspaceName]: what a person types is taken at face value, and the *characters that cannot
     * be part of a name at all* are removed. A name that sanitises away to nothing — `..`, a single
     * emoji, a name made only of separators — falls back to the generated one rather than failing:
     * the user asked for a new conversation, and refusing over a stray slash answers a question
     * they did not ask.
     *
     * A name already in use gets the first free `-2`, `-3`, … suffix instead of
     * [FsErrno.FILE_EXISTS]. "Make me a new one" is the whole flow, and being told the name exists —
     * when the name was a suggestion rather than a request — makes the user invent a second name for
     * a project they have not started yet. The suffix is inside [WorkspaceName.MAX] like the rest of
     * the name, so a long name is shortened rather than allowed to grow past what a filesystem
     * will hold.
     */
    fun create(name: String?): Entry {
        val millis = now()
        val base = if (name.isNullOrBlank()) {
            WorkspaceName.generated(millis)
        } else {
            WorkspaceName.sanitize(name) ?: WorkspaceName.generated(millis)
        }
        var candidate = base
        var suffix = 1
        var found = false
        // Bounded so a root holding nothing but `notes-2`…`notes-999` reports a real errno
        // instead of walking it forever.
        while (suffix <= MAX_SUFFIX) {
            if (free(child(root, candidate)) == null) {
                found = true
                break
            }
            suffix++
            candidate = WorkspaceName.withSuffix(base, suffix)
        }
        if (!found) throw FsException(FsErrno.FILE_EXISTS, child(root, base))
        val path = child(root, candidate)
        mkdirs(path)
        val entry = Entry(
            name = candidate,
            path = path,
            hostPath = child(hostRoot, candidate),
            createdMillis = millis,
            modifiedMillis = millis,
            meta = MetadataState.RECORDED,
        )
        // The name is recorded as history, once, from here the directory owns it: a rename in the
        // file manager is the user's business and not something this file gets to undo.
        writeMetadata(entry)
        // After the metadata file, not before: writing a file into a directory updates that
        // directory's mtime, so setting it first would leave the entry disagreeing with the disk it
        // claims to describe.
        vfs.setModified(path, millis)
        return entry
    }

    /**
     * The conversation called [name], for a command that has been told which project to work in.
     *
     * The name is sanitised, so a name reaching here from a command line cannot address anything
     * outside the root: `../..` is not a conversation and is reported as absent rather than
     * resolved. A name carrying a separator or a run of dots is refused outright rather than
     * sanitised into a different one, because `notes/../..` and `notes` naming the same folder is
     * the sort of thing a caller composes by accident and then works in the wrong project. A name
     * that merely needs tidying — `My Project` — is still accepted and finds `My-Project`.
     *
     * The name on the returned entry is the one the filesystem reports, found through this folder's
     * own listing, never the one that was typed: `/storage/emulated/0` is case-insensitive, and on
     * such a volume `NOTES` and `notes` are one directory that must have one name.
     *
     * @throws FsException [FsErrno.NO_SUCH_FILE] when there is no such conversation, and
     * [FsErrno.NOT_A_DIRECTORY] when the name is a file — a different error because the user can act
     * on it: one says "pick another", the other says "something is in the way".
     */
    fun open(name: String): Entry {
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")) {
            throw FsException(FsErrno.NO_SUCH_FILE, child(root, name))
        }
        val safe = WorkspaceName.sanitize(name) ?: throw FsException(FsErrno.NO_SUCH_FILE, child(root, name))
        val path = child(root, safe)
        val stat = try {
            vfs.stat(path)
        } catch (e: FsException) {
            throw FsException(FsErrno.NO_SUCH_FILE, path)
        }
        if (stat.type != VNodeType.DIRECTORY) throw FsException(FsErrno.NOT_A_DIRECTORY, path)
        val onDisk = reportedName(safe)
        val (meta, fields) = metadataOf(path)
        return Entry(
            name = onDisk,
            path = child(root, onDisk),
            hostPath = child(hostRoot, onDisk),
            createdMillis = if (meta == MetadataState.RECORDED) {
                fields[CREATED]?.toLongOrNull() ?: stat.mtimeMillis
            } else {
                stat.mtimeMillis
            },
            modifiedMillis = stat.mtimeMillis,
            meta = meta,
        )
    }

    /**
     * The `.omp-workspace` file of [entry] as key/value pairs, or an empty map when it has none.
     *
     * A line that is not `key=value` is skipped rather than fatal. The file is one the user can see
     * and edit from a file manager — it is in their `Documents`, not in a sandbox — and a conversation
     * must not disappear because someone fixed a typo in it or a sync tool rewrote the line endings.
     * A missing file is an empty map too, because "made by hand" and "emptied" answer the only
     * question this is asked for the same way.
     */
    fun metadata(entry: Entry): Map<String, String> = parse(
        String(vfs.readBytes(child(entry.path, METADATA)), Charsets.UTF_8),
    )

    /**
     * Rewrites [entry]'s metadata file, keeping every key already in it that the caller does not
     * mention — including the name the folder was created under, which is history once the user
     * renames it. The version, the creation time and both paths are stamped from the entry itself,
     * so a caller cannot write a file that disagrees with the directory it lives in.
     *
     * A read that fails is a failure, not an excuse: rewriting over a file this class could not read
     * would drop the very keys that are only in that file, and the name — the one record the
     * "renamed from" note is made of — is written once and never again. Only [FsErrno.NO_SUCH_FILE]
     * means "there is nothing to keep".
     *
     * The keys a caller adds are its own: [DISTRO] for the userland the conversation ran, [TITLE]
     * for the first line the user gave it.
     *
     * The write is a rename over the target, not a truncate in place: a reader — this app in another
     * window, a file manager, a backup — sees the whole old file or the whole new one, never a
     * prefix of it. A file that lost its first line has no version key, and the folder is then
     * reported as not being ours, which is a lie about a folder this class created.
     */
    fun writeMetadata(entry: Entry, values: Map<String, String> = emptyMap()) {
        val merged = LinkedHashMap<String, String>()
        try {
            merged.putAll(parse(String(vfs.readBytes(child(entry.path, METADATA)), Charsets.UTF_8)))
        } catch (e: FsException) {
            if (e.errno != FsErrno.NO_SUCH_FILE) throw e
        }
        merged.putAll(values)
        // Only ever added, never replaced: the name the folder was created under is history the
        // moment the user renames it, and the current name is already the directory's own name.
        if (!merged.containsKey(NAME)) merged[NAME] = entry.name
        // A fixed key order, version first and name second: a file this class did not finish
        // writing, or something else truncated, is then still recognisable as ours and still
        // carries the history, instead of losing its first lines to whatever order a map had.
        val ordered = LinkedHashMap<String, String>(merged.size)
        ordered[VERSION] = FORMAT_VERSION
        ordered[NAME] = merged[NAME].orEmpty()
        ordered[CREATED] = entry.createdMillis.toString()
        ordered[PATH] = entry.path
        ordered[HOST] = entry.hostPath
        for ((key, value) in merged) {
            if (!ordered.containsKey(key)) ordered[key] = value
        }
        val text = buildString {
            for ((key, value) in ordered) appendLine(line(key, value))
        }
        val target = child(entry.path, METADATA)
        val scratch = child(entry.path, TEMP)
        vfs.writeBytes(scratch, text.toByteArray(Charsets.UTF_8))
        vfs.rename(scratch, target)
    }

    // ---- the file helpers ---------------------------------------------------------------

    /** One read, and both answers: what the file says, and how much of it can be believed. */
    private fun metadataOf(dir: String): Pair<MetadataState, Map<String, String>> {
        val text = try {
            String(vfs.readBytes(child(dir, METADATA)), Charsets.UTF_8)
        } catch (e: FsException) {
            return if (e.errno == FsErrno.NO_SUCH_FILE) {
                MetadataState.NONE to emptyMap()
            } else {
                MetadataState.UNREADABLE to emptyMap()
            }
        }
        val fields = parse(text)
        return if (fields.containsKey(VERSION)) {
            MetadataState.RECORDED to fields
        } else {
            // The keys are still worth showing a caller; what they are not is a record this class
            // wrote, and a file without a version is somebody else's.
            MetadataState.CORRUPT to fields
        }
    }

    private fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in text.split('\n')) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            if (key.isEmpty()) continue
            out[key] = line.substring(eq + 1).trim()
        }
        return out
    }

    /** A newline in a value would silently end the line and lose the rest of it. */
    private fun line(key: String, value: String): String =
        "$key=${value.replace('\n', ' ').replace('\r', ' ')}"

    /**
     * The name the filesystem reports for [typed], which is not always the name that was typed.
     * `/storage/emulated/0` is a case-insensitive volume, so `NOTES` and `notes` are one directory
     * with one spelling, and the only way to learn which is to ask for the listing.
     */
    private fun reportedName(typed: String): String {
        val siblings = try {
            vfs.readDir(root)
        } catch (e: FsException) {
            return typed
        }
        return match(siblings, typed)
    }

    private fun match(siblings: List<VEntry>, typed: String): String {
        for (sibling in siblings) {
            if (sibling.name == typed) return sibling.name
        }
        for (sibling in siblings) {
            if (sibling.name.equals(typed, ignoreCase = true)) return sibling.name
        }
        return typed
    }

    /** @return null when nothing of that name is there, the path when something is. */
    private fun free(path: String): String? = try {
        vfs.stat(path)
        path
    } catch (e: FsException) {
        null
    }

    /**
     * [omp.shell.fs.Vfs] has no `mkdir -p`, and the root may be a directory this app has not made
     * yet — the first conversation of a cold start is the first thing to touch it. The same helper
     * `PackageOps` keeps, written out rather than imported, because a private one is not a seam.
     */
    private fun mkdirs(path: String) {
        val at = StringBuilder()
        for (part in path.split('/')) {
            if (part.isEmpty()) continue
            at.append('/').append(part)
            try {
                vfs.mkdir(at.toString())
            } catch (e: FsException) {
                if (e.errno != FsErrno.FILE_EXISTS) throw e
            }
        }
    }

    /** The real path behind a namespace path, for a [Vfs] that knows where its paths land. */
    private fun hostPathOf(path: String): String? = (vfs as? RealVfs)?.hostPathOf(path)

    companion object {
        /** The one file this app writes inside a conversation, and the reason one is not empty. */
        const val METADATA = ".omp-workspace"

        /**
         * Where the next version of the metadata file is written before it is renamed over
         * [METADATA]. A scratch file, not a temporary: it has to be the same directory, because
         * `rename` is only atomic within a filesystem, and a conversation is small enough that
         * doubling it for the length of one write costs nothing.
         */
        const val TEMP = ".omp-workspace.tmp"

        /** Bumped when the keys below change meaning; an older file is still read, never rewritten. */
        const val FORMAT_VERSION = "1"

        const val VERSION = "version"

        /** The name the folder was *created* under. History: the directory owns the current name. */
        const val NAME = "name"

        /** Epoch millis, as [Entry.createdMillis] has it. */
        const val CREATED = "created"

        /** The namespace path, `/mnt/omp/notes`. */
        const val PATH = "path"

        /** The real path, `/storage/emulated/0/Documents/omp/notes`. */
        const val HOST = "host"

        /** The userland the conversation ran, as the command that runs one records it. */
        const val DISTRO = "distro"

        /** The first line the user gave the conversation, if they gave one. */
        const val TITLE = "title"

        /** How far `notes`, `notes-2`… is walked before a root full of them gives up and says so. */
        private const val MAX_SUFFIX = 999

        /** Joins a namespace path and a name the way the rest of the shell does: one slash, always. */
        fun child(dir: String, name: String): String =
            if (dir.endsWith("/")) dir + name else "$dir/$name"
    }
}
