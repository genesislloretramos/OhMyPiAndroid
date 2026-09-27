package omp.agent.tools

import omp.agent.json.Json

/**
 * `read_file`: the text of one file in this conversation's folder.
 *
 * **A file too big for one result is refused, never cut.** The two numbers a model needs are in
 * the refusal — the file's real size and the cap — and the way out is named, because a truncated
 * first 64 KiB of a file reads exactly like a file that ends there, and a model that believes it
 * will write an answer about a document it has seen the top of. So `offset` and `limit` are in the
 * description and in the parameter block, and they count **lines**, which is the unit a model and
 * a person both think in when they say "the rest of it".
 *
 * **Only the window is ever held in memory.** A `limit` stops the read rather than skipping to
 * the end of a line, so asking for twenty lines of a 40 MB file costs twenty lines — which on a
 * phone is the difference between an answer and an out-of-memory.
 */
object ReadFile : FileTool() {

    override val name = "read_file"

    override val description =
        "Read one UTF-8 text file in this conversation's folder. 'path' is relative to that " +
            "folder or absolute inside it; a path outside it is refused, and so is the folder " +
            "above it that holds the other conversations, and so is the folder this app keeps " +
            "its own transcript and configuration in. 'offset' is the 1-based line to start " +
            "at and 'limit' is how many lines to return; with neither, the whole file is " +
            "returned, and a file too large to return in one piece is refused with its size " +
            "rather than truncated — read it again with an offset and a limit. A directory is " +
            "refused; list_dir reads one."

    override val parameters = Tools.schema(
        linkedMapOf(
            "path" to Tools.property(
                STRING,
                "The file to read, relative to this conversation's folder or absolute inside it.",
            ),
            "offset" to Tools.property(
                INTEGER,
                "Optional. The 1-based line to start at; the first line is 1.",
            ),
            "limit" to Tools.property(
                INTEGER,
                "Optional. How many lines to return from that offset.",
            ),
        ),
        listOf("path"),
    )

    override fun check(env: ToolEnv, path: Sandbox.Path, arguments: Json.Obj): Refusal? {
        notAFile(env, path, "a link is not followed for a read")?.let { return it }
        val offset = count(arguments, "offset")
        val limit = count(arguments, "limit")
        // `?:` folds three failures into one test: an argument that is absent is allowed through,
        // and an argument that is present but is not a plain integer literal — or is zero, or is
        // negative — is refused rather than quietly treated as the default.
        if (given(arguments, "offset") && (offset ?: 0L) < 1L) return Refusal(badCount("offset"))
        if (given(arguments, "limit") && (limit ?: 0L) < 1L) return Refusal(badCount("limit"))
        // The whole file in one answer is the common case, and it is the only one that can be
        // refused before a byte is read: an instant sentence about a huge file rather than a long
        // pause before the same sentence.
        if (!given(arguments, "offset") && !given(arguments, "limit")) {
            val size = env.vfs.stat(path.value).size
            if (size > Tools.MAX_RESULT_BYTES) {
                return Refusal(wholeFileTooBig(path.projectRelative, size))
            }
        }
        return null
    }

    override fun preview(env: ToolEnv, path: Sandbox.Path, arguments: Json.Obj): List<String> =
        emptyList()

    override fun act(env: ToolEnv, path: Sandbox.Path, arguments: Json.Obj): String {
        val offset = count(arguments, "offset") ?: 1L
        val limit = count(arguments, "limit")
        val window = StringBuilder()
        var partial = StringBuilder()
        var finished = 0L
        var taken = 0L
        env.vfs.openRead(path.value).use { stream ->
            while (true) {
                val c = stream.read()
                if (c < 0) {
                    // A last line the file does not end on is still a line, and cutting it is the
                    // same lie as truncating the file.
                    if (partial.isNotEmpty() && finished + 1 >= offset && (limit == null || taken < limit)) {
                        window.append(partial)
                        taken++
                    }
                    break
                }
                if (c != '\n'.code) {
                    partial.append(c.toChar())
                    continue
                }
                finished++
                if (finished >= offset && (limit == null || taken < limit)) {
                    window.append(partial).append('\n')
                    taken++
                }
                partial = StringBuilder()
                // The window is full: the rest of the file is not read at all, which is the whole
                // point of a limit on a phone.
                if (limit != null && taken >= limit) break
            }
        }
        val text = window.toString()
        if (text.isEmpty()) {
            val where = if (limit == null) "it is empty" else "there are no lines at or after $offset"
            return "read nothing: $where (${env.vfs.stat(path.value).size} bytes in the file)."
        }
        if (text.toByteArray(Charsets.UTF_8).size > Tools.MAX_RESULT_BYTES) {
            return tooBig(
                path.projectRelative,
                env.vfs.stat(path.value).size,
                "Ask again with a smaller 'limit': the limit counts lines.",
            )
        }
        return text
    }

    /** The refusal for a whole file with no window, with the two numbers and the way out. */
    private fun wholeFileTooBig(name: String, size: Long): String = tooBig(
        name,
        size,
        "Ask again with 'offset' and 'limit', which count lines: 'offset: 1, limit: 500' reads " +
            "the first 500 lines, and a larger offset picks up where that left off.",
    )

    /** A count the model wrote that is not a positive whole number, said in its own terms. */
    private fun badCount(what: String): String =
        "refused: '$what' has to be a whole number of lines, 1 or more. Nothing was read."

    /** Whether the model gave this argument at all, as opposed to leaving it out. */
    private fun given(arguments: Json.Obj, key: String): Boolean = arguments.field(key) != null

    /**
     * The count at [key], or null when it is absent or is not a plain integer literal.
     *
     * `1e3` and `2.0` are whole numbers and are refused anyway, because [omp.agent.json.Json.long]
     * answers null for both and a count this code cannot read exactly is a count it will not
     * guess at. [given] is what tells "absent" from "unreadable" so the model is told which.
     */
    private fun count(arguments: Json.Obj, key: String): Long? {
        if (!given(arguments, key)) return null
        val raw = arguments.long(key) ?: return null
        return if (raw in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) raw else null
    }

    private const val STRING = "string"
    private const val INTEGER = "integer"
}
