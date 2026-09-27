package omp.agent.tools

import omp.agent.json.Json
import omp.shell.fs.VNodeType

/**
 * `edit_file`: one exact piece of text replaced by another, with the user shown both.
 *
 * **It refuses rather than guesses, twice.** `old` that is not in the file is a refusal, because
 * the alternative — inserting it anyway, or editing the nearest thing to it — is how a file ends
 * up with a change nobody asked for. And `old` that is in the file **more than once** is also a
 * refusal: an edit that lands on the first of three identical lines is a change to a specific
 * line the caller did not name, and the fix is one more line of context in `old`, which a model
 * can always supply. The description says both, because a model that does not know will happily
 * send a one-word `old` and get a refusal it could have avoided.
 *
 * **The file is read again after the approval.** [check] reads it to validate, [preview] reads it
 * to show the two texts, and [act] reads it once more to change it — so the edit lands on the
 * bytes that were there when the user typed `y`, not on the ones that were there when the
 * question was printed.
 */
object EditFile : FileTool() {

    override val name = "edit_file"

    override val description =
        "Replace one exact piece of text in a file in this conversation's folder. 'old' must " +
            "occur exactly once: if it is not in the file, or is in it more than once, the edit " +
            "is refused rather than guessed at, and you should send 'old' with more of the " +
            "surrounding lines in it. 'new' is what replaces it, and may be empty to delete it. " +
            "Nothing else in the file changes. A path outside this conversation's folder is " +
            "refused. The user is shown the path and both texts and is asked to approve; if they " +
            "decline nothing is changed and you are told."

    override val parameters = Tools.schema(
        linkedMapOf(
            "path" to Tools.property(
                STRING,
                "The file to edit, relative to this conversation's folder or absolute inside it.",
            ),
            "old" to Tools.property(
                STRING,
                "The exact text to replace. It must occur exactly once in the file.",
            ),
            "new" to Tools.property(
                STRING,
                "The text that replaces it. An empty string deletes it.",
            ),
        ),
        listOf("path", "old", "new"),
    )

    override val changesDisk: Boolean get() = true

    override fun check(env: ToolEnv, path: Sandbox.Path, arguments: Json.Obj): Refusal? {
        if (arguments.str("old") == null) {
            return Refusal("refused: 'old' is required and has to be a string. Nothing was changed.")
        }
        if (arguments.str("new") == null) {
            return Refusal("refused: 'new' is required and has to be a string. Nothing was changed.")
        }
        val stat = env.vfs.stat(path.value)
        if (stat.type == VNodeType.DIRECTORY) {
            return Refusal("refused: ${path.projectRelative} is a directory, not a file.")
        }
        if (stat.size > Tools.MAX_RESULT_BYTES) {
            return Refusal(
                tooBig(
                    path.projectRelative,
                    stat.size,
                    "An edit reads the whole file to find the text, and this build does not edit " +
                        "a file it will not read. Rewrite it with write_file instead, which the " +
                        "user is shown and asked to approve in full.",
                ),
            )
        }
        return exactlyOnce(env, path, arguments.str("old").orEmpty())
    }

    override fun preview(env: ToolEnv, path: Sandbox.Path, arguments: Json.Obj): List<String> =
        header(env, path, "edit a file") +
            listOf("  size:               ${env.vfs.stat(path.value).size} bytes now") +
            quoted("  old:", arguments.str("old").orEmpty()) +
            quoted("  new:", arguments.str("new").orEmpty())

    override fun act(env: ToolEnv, path: Sandbox.Path, arguments: Json.Obj): String {
        val text = String(env.vfs.readBytes(path.value), Charsets.UTF_8)
        val old = arguments.str("old").orEmpty()
        val fresh = arguments.str("new").orEmpty()
        // The same refusal as before the question, run again on the bytes that are there now: a
        // file that changed while the user was reading the prompt is a file this edit must not
        // touch, and saying so is the only answer that is true.
        val refusal = exactlyOnce(env, path, old, text)
        if (refusal != null) return refusal.text
        val at = text.indexOf(old)
        val edited = text.substring(0, at) + fresh + text.substring(at + old.length)
        env.vfs.writeBytes(path.value, edited.toByteArray(Charsets.UTF_8))
        return "edited ${path.projectRelative}: one occurrence, ${old.length} characters replaced " +
            "by ${fresh.length}; the file is now ${edited.length} characters."
    }

    /**
     * [old] occurs in the file, and @return a refusal when it does not occur exactly once.
     *
     * [text] is passed in when the caller has already read the file, so the refusal that matters
     * most — the one given *after* the user approved — costs a second read and not a third.
     */
    private fun exactlyOnce(
        env: ToolEnv,
        path: Sandbox.Path,
        old: String,
        text: String = String(env.vfs.readBytes(path.value), Charsets.UTF_8),
    ): Refusal? {
        if (old.isEmpty()) {
            return Refusal("refused: 'old' is empty, so there is nothing to find. Nothing was changed.")
        }
        val at = text.indexOf(old)
        if (at < 0) {
            return Refusal(
                "refused: the text to replace is not in ${path.projectRelative}, which is " +
                    "${text.length} characters long. Nothing was changed. Read the file and send " +
                    "'old' exactly as it is there, spacing and line breaks included.",
            )
        }
        if (text.indexOf(old, at + old.length) >= 0) {
            return Refusal(
                "refused: the text to replace is in ${path.projectRelative} ${count(text, old)} " +
                    "times, so the one to change was not named. Nothing was changed. Send 'old' " +
                    "again with the lines around the one you mean, until it occurs once.",
            )
        }
        return null
    }

    /** How many times [old] is in [text]. Only reached when the answer is two or more. */
    private fun count(text: String, old: String): Int {
        var found = 0
        var at = text.indexOf(old)
        while (at >= 0) {
            found++
            at = text.indexOf(old, at + old.length)
        }
        return found
    }

    /**
     * A piece of text as the user is shown it, cut **with the size of what was cut**.
     *
     * A preview that silently showed the first eight lines of a three-hundred-line replacement is
     * a preview nobody can honestly approve, which defeats the point of asking; so anything left
     * out is counted on the last line. A very long single line is cut on characters for the same
     * reason — a model may send one enormous line, and a terminal that wraps it forty times is
     * not an approval anybody gave.
     */
    private fun quoted(label: String, text: String): List<String> {
        if (text.isEmpty()) return listOf("$label (empty, so this deletes the text)")
        val lines = text.split('\n')
        val body = lines.take(SHOWN).joinToString("\n") { "$label$it" }
        if (lines.size <= SHOWN && body.length <= CHARS) return listOf(body)
        val kept = body.take(CHARS)
        return listOf(
            kept,
            "$label… this text is ${text.length} characters in ${lines.size} line(s); only the " +
                "first $kept characters are shown here",
        )
    }

    /** How many lines of a replacement go in front of the user before the count takes over. */
    private const val SHOWN = 8

    /** How many characters survive onto the screen before the count takes over. */
    private const val CHARS = 400

    private const val STRING = "string"
}
