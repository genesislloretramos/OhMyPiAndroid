package omp.agent.tools

import omp.agent.json.Json
import omp.agent.store.META_DIR
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.VNodeType
import omp.vm.workspace.Workspace

/**
 * `search`: the lines of this conversation's files that contain a piece of text.
 *
 * **It is a substring search and says so.** A plain `indexOf` is what this build can do without
 * compiling a pattern on a phone, and a description that said "regular expression" would be a
 * claim about a capability that is not there — a model would send `.*` and get a literal. The
 * comparison is **case-sensitive**, because the volume this folder sits on is case-insensitive
 * and a search that folded case would quietly answer a different question from the one asked.
 *
 * **The walk is bounded, and every bound is in the description.** A conversation is a folder in
 * the user's `Documents`, which may hold a photo library, and a walk with no stop on it is a walk
 * that runs until the phone is warm. So the walk stops at [MAX_FILES] files and the result at
 * [MAX_MATCHES] lines, and says which of the two happened; files over [MAX_FILE_BYTES] are not
 * opened at all, because a file that big is not a text file a model is looking for a word in.
 *
 * **A symbolic link is counted and not followed.** A link inside the conversation can point
 * anywhere the namespace can name, and a walk that followed one would leave the folder through a
 * name the boundary check never saw. So the walk stays where [omp.agent.tools.Sandbox] put it and
 * says how many links it passed over.
 *
 * **This app's own `.omp` folder is not searched.** The transcript in it is a conversation with
 * the model written in the model's own words, and a search that found it would answer every
 * question about what was said before with a copy of what was said before.
 */
object Search : FileTool() {

    override val name = "search"

    override val description =
        "Find lines in the files of this conversation's folder that contain a piece of text, and " +
            "return them as 'file:line: the line'. 'pattern' is matched as plain text, not as a " +
            "regular expression, and the match is case-sensitive. 'path' is the folder to start " +
            "in, relative to this conversation or absolute inside it, and with no 'path' the " +
            "whole conversation is searched. Subfolders are searched; symbolic links are counted " +
            "but not followed, and the $META_DIR folder this app keeps its own notes in is " +
            "skipped. Files over $MAX_FILE_BYTES bytes are not opened. At most $MAX_MATCHES " +
            "matching lines and $MAX_FILES files are reported, and the result says when it " +
            "stopped. A path outside this conversation's folder is refused."

    override val parameters = Tools.schema(
        linkedMapOf(
            "pattern" to Tools.property(
                STRING,
                "The text to look for, matched as plain text and case-sensitively.",
            ),
            "path" to Tools.property(
                STRING,
                "Optional. The folder to start in, relative to this conversation's folder. " +
                    "Omit it to search everything under the conversation's folder.",
            ),
        ),
        listOf("pattern"),
    )

    override val defaultsToProject: Boolean get() = true

    override fun check(env: ToolEnv, path: Sandbox.Path, arguments: Json.Obj): Refusal? {
        val pattern = arguments.str("pattern")
        if (pattern == null) {
            return Refusal("refused: 'pattern' is required and has to be a string.")
        }
        if (pattern.isEmpty()) {
            return Refusal("refused: 'pattern' is empty, and every line contains it.")
        }
        val stat = env.vfs.stat(path.value)
        if (stat.type != VNodeType.DIRECTORY) {
            return Refusal(
                "refused: ${path.projectRelative} is a ${stat.type.name.lowercase()}, not a " +
                    "folder to search. read_file reads a file.",
            )
        }
        return null
    }

    override fun preview(env: ToolEnv, path: Sandbox.Path, arguments: Json.Obj): List<String> =
        emptyList()

    override fun act(env: ToolEnv, path: Sandbox.Path, arguments: Json.Obj): String {
        val pattern = arguments.str("pattern").orEmpty()
        val found = StringBuilder()
        var lines = 0
        var files = 0
        var links = 0
        var outOfMatches = false
        var outOfFiles = false
        // The absolute and the conversation-relative name travel together: the walk composes
        // names out of a listing, and carrying the second one costs a string join where a second
        // [omp.agent.tools.Sandbox.resolve] would cost a filesystem walk per file.
        val pending = ArrayDeque<Pair<String, String>>()
        pending.addLast(path.value to path.projectRelative)
        while (pending.isNotEmpty() && !outOfMatches && !outOfFiles) {
            val (dir, relative) = pending.removeFirst()
            val children = try {
                env.vfs.readDir(dir)
            } catch (e: FsException) {
                // Named rather than dropped: a subtree nobody can read is a subtree whose contents
                // are unknown, and a search that reported nothing found there would be lying.
                if (e.errno == FsErrno.PERM_DENIED) {
                    found.append(relative).append(": this folder could not be read\n")
                    lines++
                }
                continue
            }
            for (child in children.sortedBy { it.name }) {
                if (lines >= MAX_MATCHES) {
                    outOfMatches = true
                    break
                }
                if (files >= MAX_FILES) {
                    outOfFiles = true
                    break
                }
                val below = if (relative == ".") child.name else "$relative/${child.name}"
                when (child.stat.type) {
                    VNodeType.DIRECTORY -> {
                        if (child.name != META_DIR) pending.addLast(Workspace.child(dir, child.name) to below)
                    }

                    VNodeType.SYMLINK -> links++
                    VNodeType.FILE -> {
                        if (child.stat.size > MAX_FILE_BYTES) continue
                        files++
                        val hit = scan(env, Workspace.child(dir, child.name), below, pattern)
                        if (hit != null) {
                            found.append(hit)
                            lines += hit.count { it == '\n' }
                        }
                    }

                    else -> Unit
                }
            }
        }
        // Measured in UTF-8 bytes rather than characters, because the cap is a byte cap and a
        // result of accented text is three bytes a character.
        val body = found.toString()
        val bytes = body.toByteArray(Charsets.UTF_8).size
        if (bytes > Tools.MAX_RESULT_BYTES) {
            return tooBig(
                "\"$pattern\"",
                bytes.toLong(),
                "Narrow it: search one folder with 'path', or use a longer pattern. " +
                    "Nothing was truncated.",
            )
        }
        val tail = when {
            outOfMatches -> "Stopped at the limit of $MAX_MATCHES lines; there may be more."
            outOfFiles -> "Stopped after $MAX_FILES files; there may be more."
            else -> ""
        }
        val skipped = if (links == 0) "" else " $links link(s) were counted and not followed."
        return "$body$tail$skipped"
    }

    /**
     * The matching lines of one file, each with the conversation-relative name in front of it, or
     * null when there are none.
     *
     * **The name shown is the one the model would have to open.** An absolute namespace path would
     * be true and useless; the relative form is what goes back into `read_file` unchanged, so a
     * model that wants the rest of a file can ask for it without translating anything.
     */
    private fun scan(env: ToolEnv, at: String, relative: String, pattern: String): String? {
        val bytes = try {
            env.vfs.readBytes(at)
        } catch (e: FsException) {
            return null
        }
        // A NUL near the start is what a binary file looks like from the inside, and printing the
        // lines of one would put megabytes of mojibake in front of a model.
        for (i in 0 until minOf(bytes.size, HEAD)) {
            if (bytes[i] == 0.toByte()) return null
        }
        val out = StringBuilder()
        var line = 0
        for (row in String(bytes, Charsets.UTF_8).split('\n')) {
            line++
            if (row.contains(pattern)) {
                out.append(relative).append(':').append(line).append(": ").append(row.trimEnd()).append('\n')
            }
        }
        return if (out.isEmpty()) null else out.toString()
    }

    /** How many matching lines one result may carry before the walk is told to stop. */
    const val MAX_MATCHES = 200

    /** How many files one call will open. A conversation is a folder in the user's Documents. */
    const val MAX_FILES = 2_000

    /** What a file has to be under for this build to open it and look for a word in it. */
    const val MAX_FILE_BYTES = 256L * 1024

    /** How much of a file is looked at for a NUL, which is all it takes to know it is binary. */
    private const val HEAD = 512

    private const val STRING = "string"
}
