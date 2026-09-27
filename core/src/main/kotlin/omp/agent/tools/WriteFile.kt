package omp.agent.tools

import omp.agent.json.Json
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.VNodeType
import omp.shell.fs.VStat

/**
 * `write_file`: one file, written whole, with the user asked first.
 *
 * **It replaces rather than merges, and says so in the description.** `content` is the whole of
 * the file, so a model that read a file, changed one line and wrote it back has to send the whole
 * thing; that is a cost, and it is the price of a tool that cannot be used to lose the rest of a
 * file by accident. An append mode would be a second capability this build does not have, and
 * the description does not pretend to it.
 *
 * **The approval names the size and both paths, because that is what a user is being asked
 * about.** "The model wants to write something" is not a question with a `y` answer; a file
 * name, the folder it is really in, and whether it is new or is about to overwrite something,
 * are.
 */
object WriteFile : FileTool() {

    override val name = "write_file"

    override val description =
        "Write one UTF-8 text file in this conversation's folder, replacing it if it is already " +
            "there. 'content' is the WHOLE file: this is not an append and not a patch, so read " +
            "the file first and send all of it back with your change in it. A path outside this " +
            "conversation's folder is refused, and so is the folder this app keeps its own " +
            "transcript and configuration in, and so is a directory. The user is shown the " +
            "path, the size and whether the file is new, and is asked to approve it; if they " +
            "decline nothing is written and you are told. Folders are not created. 'content' may " +
            "contain any character, including control characters, which is why an edit that " +
            "carries one is refused and should come here."

    override val parameters = Tools.schema(
        linkedMapOf(
            "path" to Tools.property(
                STRING,
                "The file to write, relative to this conversation's folder or absolute inside it.",
            ),
            "content" to Tools.property(
                STRING,
                "The entire text of the file, exactly as it should be afterwards.",
            ),
        ),
        listOf("path", "content"),
    )

    override val changesDisk: Boolean get() = true

    override fun check(env: ToolEnv, path: Sandbox.Path, arguments: Json.Obj): Refusal? {
        if (arguments.str("content") == null) {
            return Refusal(
                "refused: 'content' is required and has to be a string. Nothing was written.",
            )
        }
        val stat = absent(env, path)
        if (stat != null) {
            if (stat.type == VNodeType.DIRECTORY) {
                return Refusal(
                    "refused: ${path.projectRelative} is a directory, so there is no file to write.",
                )
            }
            if (stat.type == VNodeType.SYMLINK) {
                return Refusal(
                    "refused: ${path.projectRelative} is a symbolic link and is not written through.",
                )
            }
        }
        return parent(env, path)
    }

    override fun preview(env: ToolEnv, path: Sandbox.Path, arguments: Json.Obj): List<String> {
        val content = arguments.str("content").orEmpty()
        val bytes = content.toByteArray(Charsets.UTF_8).size.toLong()
        // A file that ends in a line break has as many lines as it has breaks, not one more: the
        // question the user is answering here is "how big is this", and "2 lines" for a string
        // that ends in a newline is a small lie told in the one place a lie is expensive.
        val lines = when {
            content.isEmpty() -> 0
            content.endsWith("\n") -> content.count { it == '\n' }
            else -> content.count { it == '\n' } + 1
        }
        val existing = absent(env, path)?.size
        val there = if (existing == null) {
            "new file"
        } else {
            "replaces the $existing bytes already in it"
        }
        return header(env, path, "write a file") + listOf(
            "  size:               $bytes bytes in $lines line(s)",
            "  this file is:       $there",
        )
    }

    override fun act(env: ToolEnv, path: Sandbox.Path, arguments: Json.Obj): String {
        val content = arguments.str("content").orEmpty()
        val bytes = content.toByteArray(Charsets.UTF_8)
        env.vfs.writeBytes(path.value, bytes)
        return "wrote ${path.projectRelative}: ${bytes.size} bytes."
    }

    /**
     * The directory the file is in, which has to be there already.
     *
     * `mkdir` is not a tool and is not run on the model's behalf: a tool that quietly made the
     * directories a path implies is a tool that can scatter empty folders through a conversation
     * a user is looking at in a file manager. A missing parent is a refusal naming it.
     */
    private fun parent(env: ToolEnv, path: Sandbox.Path): Refusal? {
        val cut = path.value.lastIndexOf('/')
        val above = if (cut <= 0) "/" else path.value.substring(0, cut)
        val stat = try {
            env.vfs.stat(above)
        } catch (e: FsException) {
            return Refusal(
                "refused: ${path.projectRelative}: the folder it would be in is not there ($above). " +
                    "This build makes no folders; list_dir what is here and write inside one of those.",
            )
        }
        if (stat.type != VNodeType.DIRECTORY) {
            return Refusal("refused: ${path.projectRelative}: $above is not a directory.")
        }
        return null
    }

    /** The [omp.shell.fs.VStat] of the file, or null when it is not there yet. */
    private fun absent(env: ToolEnv, path: Sandbox.Path): VStat? = try {
        env.vfs.stat(path.value)
    } catch (e: FsException) {
        if (e.errno == FsErrno.NO_SUCH_FILE) null else throw e
    }

    private const val STRING = "string"
}
