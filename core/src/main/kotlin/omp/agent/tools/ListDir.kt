package omp.agent.tools

import omp.agent.json.Json
import omp.shell.fs.VNodeType

/**
 * `list_dir`: what is in one folder of this conversation.
 *
 * **It lists one folder, not the tree.** A recursive listing of a conversation holding a photo
 * library would be a result no phone should try to put on a screen, and a model that wants the
 * tree can walk it one call at a time — which is also the only way it can see which folder to ask
 * about next. The description says so, because a model told "lists a directory" reasonably
 * expects `tree`.
 *
 * **This app's own bookkeeping is listed and not entered.** The `.omp` folder holding the
 * transcript and the state file is listed like anything else rather than hidden: it is a real
 * folder in the user's `Documents`, and a tool that pretended otherwise would be making a claim
 * about the filesystem that the filesystem does not support. **Asking to go into it is refused**,
 * by [omp.agent.tools.Sandbox] and not here — the same verdict a read of the container gets, for
 * the same reason: the transcript is this conversation in the model's own words, and reading it
 * back answers every question about what was said before with a copy of what was said before.
 * So the name is visible and the contents are not, which is what a person gets too: the folder is
 * there in the file manager, and the transcript in it is theirs rather than a tool result.
 */
object ListDir : FileTool() {

    override val name = "list_dir"

    override val description =
        "List one folder in this conversation's folder, with the type and size of each entry. " +
            "'path' is that folder, relative to this conversation or absolute inside it; with no " +
            "'path' the conversation's own folder is listed. This is one folder, not a tree: a " +
            "subfolder is listed by name and you list it with a second call. A path outside this " +
            "conversation's folder is refused, and so is the folder above it that holds the other " +
            "conversations, and so is the folder this app keeps its own transcript and " +
            "configuration in — its name is in the listing, and going into it is not. A file is " +
            "refused; read_file reads one."

    override val parameters = Tools.schema(
        linkedMapOf(
            "path" to Tools.property(
                STRING,
                "Optional. The folder to list, relative to this conversation's folder. " +
                    "Omit it to list the conversation's own folder.",
            ),
        ),
        emptyList(),
    )

    override val defaultsToProject: Boolean get() = true

    override fun check(env: ToolEnv, path: Sandbox.Path, arguments: Json.Obj): Refusal? {
        val stat = env.vfs.stat(path.value)
        if (stat.type != VNodeType.DIRECTORY) {
            return Refusal(
                "refused: ${path.projectRelative} is a ${stat.type.name.lowercase()}, not a " +
                    "directory. read_file reads a file.",
            )
        }
        return null
    }

    override fun preview(env: ToolEnv, path: Sandbox.Path, arguments: Json.Obj): List<String> =
        emptyList()

    override fun act(env: ToolEnv, path: Sandbox.Path, arguments: Json.Obj): String {
        val entries = env.vfs.readDir(path.value).sortedBy { it.name }
        if (entries.isEmpty()) return "${path.projectRelative} is empty: 0 entries."
        val listing = StringBuilder()
        for (entry in entries) {
            listing.append(kind(entry.stat.type)).append("  ").append(entry.name)
            if (entry.stat.type == VNodeType.FILE) {
                listing.append("  ").append(entry.stat.size).append(" bytes")
            }
            listing.append('\n')
        }
        val body = listing.toString()
        if (body.toByteArray(Charsets.UTF_8).size > Tools.MAX_RESULT_BYTES) {
            return "refused: ${path.projectRelative} holds ${entries.size} entries, which is more " +
                "than one tool result can carry (${Tools.MAX_RESULT_BYTES} bytes). Nothing was " +
                "truncated. This build cannot list part of a folder; use search to look for a " +
                "name inside it."
        }
        val noun = if (entries.size == 1) "entry" else "entries"
        return "${path.projectRelative}: ${entries.size} $noun\n$body"
    }

    /** The four characters a listing sorts by, which is what `ls -F` would print. */
    private fun kind(type: VNodeType): String = when (type) {
        VNodeType.DIRECTORY -> "dir "
        VNodeType.SYMLINK -> "link"
        VNodeType.FILE -> "file"
        VNodeType.DEVICE -> "dev "
        VNodeType.OTHER -> "othr"
    }

    private const val STRING = "string"
}
