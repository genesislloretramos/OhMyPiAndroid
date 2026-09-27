package omp.agent.tools

import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.PathResolver
import omp.shell.fs.VNodeType
import omp.shell.fs.Vfs

/**
 * The boundary an AI agent is given: one conversation folder, and nothing it can name outside it.
 *
 * The user permitted exactly one directory — *Internal storage ▸ Documents ▸ omp*, which is
 * `/mnt/omp` in the namespace and the same bytes under another name on the phone — and the agent
 * was then given ONE conversation folder inside it. This class is what that second sentence means
 * in code. A coding agent that can be talked into writing outside its project is worse than one that
 * cannot write at all, so the boundary lives here, once, instead of in a check each tool remembers
 * to make: a tool asks [resolve] and acts on what it is handed, and there is no way to reach the
 * filesystem from the agent without an answer from this class first.
 *
 * **The container is refused, and that is the stricter of the two possible answers.** [project] and
 * everything under it is what the agent may act on; the container above it is not, so the agent
 * cannot `mkdir /mnt/omp/backup` and leave behind a sibling conversation the user never asked for —
 * a folder in the place people keep their documents, listed by the next `omp` as if they had made
 * it. One folder was given, so one folder is the answer. A *read* of the container is refused for
 * the same reason: a verdict here is one per path, and a path this class refuses is one a tool can
 * neither read nor write. Listing the other conversations is a capability the agent was not given.
 *
 * **A name is compared exactly, and the volume behind it is not.** `Documents/omp` is exFAT: there,
 * `Photos` and `photos` are one directory, and `café` typed as `e` + U+0301 is another spelling of
 * the same one. Nothing here folds case and nothing normalises Unicode, so a spelling the user did
 * not give is refused rather than quietly treated as the folder it names. That is the stricter of
 * the two rules on a case-insensitive volume, and it is the one a model cannot talk its way past by
 * discovering that the project answers to two spellings.
 *
 * **A link is followed and the target is the thing that is checked.** A path inside the project can
 * be a symlink whose target is not: the kernel resolves a link against its own root, so a link to
 * `/etc/passwd` in the namespace resolves to *the namespace's* `/etc/passwd` — a directory this app
 * can see and the agent may not touch. So [resolve] checks the collapsed name first — a `..` that
 * left the project is refused before the filesystem is consulted at all — then asks the [Vfs] for
 * the real path of the name *as it was written*, because the kernel applies a `..` to where a
 * link led and not to where the link was written, and then holds that against the boundary. When
 * the two answers about the boundary disagree, the answer is a refusal: a link that leads out is
 * caught even when the `..` behind it would have walked back in. A path that is not there yet has
 * no real path, so the walk stops at the deepest ancestor the [Vfs] will answer for and the
 * remaining names are re-attached, which is why a link is followed even when
 * what is written through it is new.
 *
 * **This is a path check, not a security boundary against a malicious model.** It stops the
 * accident: a model that is confidently wrong about where it may write, a tool that composes a path
 * out of a filename and a guess, a `..` that walked out of the project. It does not stop an adversary,
 * because a determined caller that holds a [Vfs] of its own can walk straight past this class — and
 * in this app that caller is Kotlin inside the app the user already installed, which is not a thing
 * this class can be responsible for. Read the refusal as "this is not yours to write", not as "this
 * cannot be done".
 *
 * **Defence in depth, not a sandbox in the security sense.** The app is not a sandbox and does not
 * pretend to be; the outer wall is the [Vfs] itself, whose longest-mountpoint rule is what makes
 * `/mnt/omp/photos` and `/storage/emulated/0/Documents/omp/photos` one directory with two names and
 * keeps every path in the namespace inside the mount table. This class is the next ring in, and it
 * is the ring that knows the one rule the mount table cannot express: which of those directories
 * belongs to *this* conversation.
 */
class Sandbox(private val vfs: Vfs, val container: String, val project: String) {

    companion object {
        /**
         * The cap on a path, in UTF-16 units, enforced on the argument as well as on the result.
         *
         * 1024 is under the 4096-byte `PATH_MAX` a Linux kernel enforces, and every path this app
         * builds is `container` + a project of at most 60 code points + whatever the model asked
         * for, so the cap never fires on real work — and it fires long before a hostile string has
         * cost a filesystem walk. At the cap a path is **refused, never truncated**: a cut path is
         * a different path, and quietly shortening one would retarget a write at a file the model
         * did not name.
         */
        const val MAX_PATH = 1024
    }

    /**
     * One path the agent may act on, with the two answers a caller would otherwise have to
     * recompute — and recompute wrongly, which is how a tool ends up printing a path relative to
     * the wrong directory.
     *
     * It is nested rather than top-level because `Path` is a name the JDK already uses
     * (`java.nio.file.Path`), and a file that imports both is a file with an import alias in it.
     */
    data class Path(
        /** The absolute namespace path, symlink-resolved: the one the boundary was checked on. */
        val value: String,
        /**
         * The same path as the agent should see it — below the project, `/` for separator, `.` for
         * the project itself. It is what a tool puts in front of a user, and it is null nowhere:
         * a [Path] is only ever handed out for a path that is inside the project.
         */
        val projectRelative: String,
        /** True when this is the project directory itself, which is the one path with no relative form. */
        val isProject: Boolean,
    )

    /** [container] and [project] as they will be compared: absolute, `..` collapsed, no trailing `/`. */
    private val containerPath: String = PathResolver.normalize(container)
    private val projectPath: String = PathResolver.normalize(project)
    private val projectParts: List<String> = projectPath.split('/')

    init {
        // A project outside the container, or the container itself, would make every answer this
        // class gives a lie or a contradiction: it is refused where it is built rather than on the
        // first tool call that trips over it.
        val parts = containerPath.split('/')
        if (projectParts.size <= parts.size) throw FsException(FsErrno.PERM_DENIED, project)
        for (i in parts.indices) {
            if (projectParts[i] != parts[i]) throw FsException(FsErrno.PERM_DENIED, project)
        }
    }

    /**
     * The one question this class answers: may this path be touched, and what is its name.
     *
     * [raw] is relative to [from] — the project unless a caller says otherwise, because a tool
     * working in a subdirectory is a tool whose `from` moved — or absolute in the namespace, and it
     * is collapsed the way the kernel collapses it: `..` is applied as it is walked, not at the end.
     * [from] itself is relative to the project when it is not absolute, and is not trusted any
     * further than that: a `from` outside the project makes every answer a refusal, by the same
     * check as everything else.
     *
     * A project folder that is itself a link out of the container makes every path in it a
     * refusal, which is the honest answer: the conversation was not in the folder the user opened.
     *
     * The answer is [FsErrno.PERM_DENIED] — not a bespoke exception, because every diagnostic in
     * this codebase is built from one errno, and a tool that had to learn a second failure type
     * would eventually forget it. A name this class will not put in the filesystem at all (a NUL, a
     * newline, `//`, a bare `.`, over the cap) is refused the same way, because those are not paths
     * and the [Vfs] cannot be the one to say so. [FsErrno.SYMLINK_LOOP] is the one failure that
     * comes from the [Vfs] and is passed through unchanged: a link loop inside the project is a
     * fact about the folder, not a decision about the boundary.
     */
    fun resolve(raw: String, from: String = project): Path {
        // A NUL ends the string in every C API underneath, and a newline is one line of a
        // transcript and one line of a diagnostic: neither is a name, and no name this app creates
        // contains either — WorkspaceName drops every control character for that reason.
        if (raw.indexOf('\u0000') >= 0 || raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0) {
            throw FsException(FsErrno.PERM_DENIED, raw)
        }
        // `//` is a distinct path in POSIX and every layer below this one collapses it its own way.
        // A model that emits it is emitting something whose meaning is not portable, and the
        // container is exactly one string.
        if (raw.startsWith("//")) throw FsException(FsErrno.PERM_DENIED, raw)
        // `.`, `..`, `./..` and the empty string: a place rather than a thing. They name the
        // project (which is inside it) and would pass the boundary check, so they are refused here
        // instead — a caller that has not decided what it is acting on has nothing to act on.
        if (navigationOnly(raw)) throw FsException(FsErrno.PERM_DENIED, raw)
        if (raw.length > MAX_PATH) throw FsException(FsErrno.PERM_DENIED, raw)

        // Two names, on purpose. `asked` is the one the model wrote, collapsed: it is what the
        // boundary is checked against first, so a `..` that climbed out is refused before the
        // filesystem is consulted at all. `absolute` keeps its `..` for the link walk, because the
        // kernel applies a `..` to wherever a link led and not to where the link was written —
        // collapsing first would make `link/../x` a path the walk never sees the link in.
        val absolute = if (raw.startsWith("/")) raw else {
            val base = if (from.startsWith("/")) from else "$projectPath/$from"
            "$base/$raw"
        }
        val asked = PathResolver.normalize(absolute)
        if (below(asked) == null) throw FsException(FsErrno.PERM_DENIED, asked)

        val real = throughLinks(absolute)
        if (real.length > MAX_PATH) throw FsException(FsErrno.PERM_DENIED, raw)
        val relative = below(real) ?: throw FsException(FsErrno.PERM_DENIED, real)
        return Path(real, relative, relative == ".")
    }

    /**
     * Whether [path] is one the agent may touch, and never an exception: a caller asking a yes/no
     * question about a name from a model should not have to write a `catch` to get it, and a
     * filesystem that cannot answer is a "no" — the one verdict that is safe to act on.
     */
    fun contains(path: String): Boolean = verdict(path, project) != null

    /**
     * [path] as the agent should see it — below the project — or null when it is not one of ours.
     * The same verdict as [contains], for the caller that wanted to print the name rather than
     * branch on it.
     */
    fun projectRelative(path: String): String? = verdict(path, project)?.projectRelative

    /**
     * [resolve] as a value and not an exception, for the two callers that only want the verdict.
     * A [FsException] from the [Vfs] itself — a link loop, a directory this app may not read — is
     * a "no" here as well: a caller asking whether a name is safe to act on must never be told
     * "probably".
     */
    private fun verdict(raw: String, from: String): Path? = try {
        resolve(raw, from)
    } catch (e: FsException) {
        null
    }

    /**
     * @return the part of [candidate] below the project, `.` for the project itself, and null when
     * it is not at or under the project. Components are compared whole and exactly, so a path whose
     * spelling the volume would fold is not silently the same directory as one that was approved.
     */
    private fun below(candidate: String): String? {
        val parts = candidate.split('/')
        if (parts.size < projectParts.size) return null
        for (i in projectParts.indices) {
            if (parts[i] != projectParts[i]) return null
        }
        return parts.drop(projectParts.size).joinToString("/").ifEmpty { "." }
    }

    /**
     * True when every component of [raw] is `.` or `..` — or there is no component at all, which is
     * the empty string. Both are a position rather than a name.
     */
    private fun navigationOnly(raw: String): Boolean {
        var at = 0
        while (at < raw.length) {
            val stop = raw.indexOf('/', at).let { if (it < 0) raw.length else it }
            val part = raw.substring(at, stop)
            if (part.isNotEmpty() && part != "." && part != "..") return false
            at = stop + 1
        }
        return true
    }

    /**
     * The path the kernel would act on, as [omp.shell.fs.resolveSymlinks] computes it.
     *
     * [value] arrives un-collapsed, so the walk sees a link before the `..` that would have
     * cancelled it: the kernel applies a `..` to where the link led, and a boundary that collapsed
     * first would be checking a different path from the one the filesystem is about to act on.
     *
     * A [Vfs] that refuses to answer about a path that is not there is a real [Vfs] — a dangling
     * link has to fail at the point of use — so the walk stops at the deepest ancestor it *will*
     * answer for and re-attaches the names below it. Those names are then checked to be ordinary
     * ones: a name that exists and is itself a link was not resolved by that walk, and treating it
     * as an ordinary name would be exactly the hole this class exists to close.
     */
    private fun throughLinks(value: String): String {
        try {
            // Collapsed on the way out because the walk hands its argument back untouched when
            // there is no link in it, and that argument still has the `..` the caller wrote.
            return PathResolver.normalize(vfs.realpath(value))
        } catch (e: FsException) {
            val cut = value.lastIndexOf('/')
            if (cut < 0) throw e
            val head = throughLinks(if (cut == 0) "/" else value.substring(0, cut))
            val leaf = value.substring(cut + 1)
            val rejoined = if (head == "/") "/$leaf" else "$head/$leaf"
            if (isLink(rejoined)) throw e
            return PathResolver.normalize(rejoined)
        }
    }

    private fun isLink(path: String): Boolean = try {
        vfs.stat(path).type == VNodeType.SYMLINK
    } catch (e: FsException) {
        false
    }
}
