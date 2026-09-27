package omp.agent.tools

import omp.agent.Agent
import omp.agent.json.Json
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.PathResolver
import omp.shell.fs.Vfs
import omp.shell.fs.VNodeType

/**
 * One operation the agent can be given, as the model is told about it and as it is run.
 *
 * **The [description] and the [parameters] are the model's only documentation.** There is no
 * `tools.md` inside the conversation, no second system paragraph and no manual a user reads
 * before trusting an agent: a model decides whether to call a tool from those two fields and
 * nothing else. So each one says what the tool does, what it refuses, and that a path is inside
 * this conversation — and **not one word of it is a capability the tool does not have.** A tool
 * described as being able to run something it cannot do is worse than a tool that is absent,
 * because a model will try.
 */
interface Tool {

    /** The name the model calls it by, and the name the transcript records. */
    val name: String

    /** What the model is told this does, in as many words as that takes. */
    val description: String

    /** The parameter block, in the JSON-schema shape every OpenAI-compatible endpoint documents. */
    val parameters: Json.Obj

    /**
     * Runs one call and @return what the model is sent back.
     *
     * **This never throws.** Every refusal — a path outside the conversation, a name that is not
     * there, a `search` with no `pattern` — comes back as an [Outcome.Result] the model can read
     * and recover from, because the one thing a tool loop must not do is end the session over a
     * call a model got slightly wrong.
     */
    fun run(env: ToolEnv, arguments: Json.Obj): Outcome
}

/**
 * Everything a tool is allowed to reach, handed to it rather than reached for.
 *
 * A tool has no [omp.shell.exec.ExecContext] and no [omp.shell.Session]: the shell a tool runs
 * in can be a pipe, a background job or a `vm exec` line, and a tool that could ask any of those
 * questions would be a tool whose behaviour depends on where the agent was typed. The two things
 * that do vary — the boundary, and whether a human is watching — are fields here, and both are
 * set by the session that owns the terminal.
 */
class ToolEnv(
    /** The one decision about where the agent may write, made once, in one class. */
    val sandbox: Sandbox,
    /** The session's own filesystem: every byte a tool touches comes through it. */
    val vfs: Vfs,
    /** The conversation's folder as a file manager shows it, for the approval prompt to name. */
    val real: String,
    /** Puts a line on the user's screen. The approval prompt is written out of these. */
    val say: (String) -> Unit,
    /** Asks the user about one operation, and does not return without an answer. */
    val approve: (List<String>) -> Verdict,
) {

    /**
     * The same path as the user would name it outside the namespace: the conversation's real
     * folder and the name below it.
     *
     * [Sandbox.Path.projectRelative] is what makes this a join and not a guess — it is `.` for the
     * folder itself, it never starts with `/`, and it cannot contain `..` because a path that
     * could is one [Sandbox.resolve] refused. On the phone the namespace path and the real path
     * are the same string, so this is the identity; inside the VM it is the other name for the
     * same directory, which is the only reason this exists.
     */
    fun onPhone(path: Sandbox.Path): String =
        if (path.isProject) real else "$real/${path.projectRelative}"
}

/** How a user answered one question this agent had to ask. */
enum class Verdict {
    /** `y`. The operation goes ahead. */
    YES,

    /** Anything else. The operation does not happen, and the model is told so. */
    NO,

    /** A Ctrl-C at the prompt: the turn is over and the terminal goes back to the user. */
    CANCELLED,
}

/** What one tool call ended as. */
sealed class Outcome {

    /**
     * The text the model is sent back, and — when the tool had to ask — [approval], which is what
     * the transcript records so a later reader can tell an approved write from a silent one.
     */
    data class Result(val text: String, val approval: String? = null) : Outcome()

    /** A Ctrl-C at an approval prompt. Nothing is sent back and nothing is written. */
    object Cancelled : Outcome()
}

/**
 * A tool's own answer before any work: the sentence the model is sent instead of a result.
 *
 * Its own type and not a nullable string, because [FileTool] has to tell "checked, carry on" from
 * "here is why not" and a tool that answered with `""` would be indistinguishable from one that
 * had nothing to say.
 */
class Refusal(val text: String)

/**
 * Every tool in this build, and the two numbers that bound what one of them may return.
 *
 * **The refusal for a tool this build does not have names `run` in as many words.** A model that
 * has been told it is a coding agent will ask for a shell; the answer is not silence and not a
 * tool result invented to keep it happy, it is a sentence saying what the tools are and why that
 * one is not among them.
 */
object Tools {

    /**
     * 64 KiB, the cap on the UTF-8 bytes in one tool result.
     *
     * A phone has to hold a result in memory, write it to a transcript and send it in the next
     * request, so the cap is a memory question first: 64 KiB is about two thousand lines of text,
     * which is several files' worth of a real conversation, and it is small enough that the
     * transcript's own 8 MiB cap is roughly a hundred rounds away rather than two. **A result over
     * it is refused with its size, never clipped**: half a file that looks like the whole of one
     * is how a model concludes a file ends where it does not, and a refusal it can read is worth
     * more to it than a truncated lie.
     */
    const val MAX_RESULT_BYTES = 64 * 1024

    /**
     * How many times one question may be answered with tool calls before the loop stops.
     *
     * Every round is a whole request and a whole streamed answer — on a phone, on a metered
     * connection, with the whole transcript going out each time — so the number is about the
     * user's patience as much as the model's. Ten is far more than an honest question needs
     * (read a file, write it, read it back, answer) and far less than a model looping on a tool
     * that keeps failing, which is the case the cap exists for. **Reaching it is said out loud
     * and written to the transcript**: a loop that stopped for a reason nobody could see is the
     * same failure as one that stopped for no reason at all.
     */
    const val MAX_ROUNDS = 10

    /**
     * Why there is no `run` tool, in the one place a reader will look for it.
     *
     * The shell this app already has can be handed the line `vm reset`, which throws the whole
     * namespace away — the one holding the conversation the agent is working in. A tool that ran
     * a model's command lines would therefore hand a model the means of destroying its own
     * project, and a shell it composes itself is exactly the thing an approval prompt on a phone
     * cannot honestly summarise: a line of shell has a hundred ways to write, and a user who
     * approves one of them has approved nothing in particular. So the file tools are here and the
     * shell is not, and the user who wants a command run can run it themselves in the terminal
     * they are already sitting at.
     */
    const val NO_RUN =
        "There is no run tool: the shell in this VM can 'vm reset' and throw away the namespace " +
            "this conversation is in, and the model picks its own command lines. Use the file " +
            "tools above; the user can run a command themselves."

    /** Every tool, in the order they are declared to the model: read before write. */
    val ALL: List<Tool> = listOf(ReadFile, ListDir, Search, WriteFile, EditFile)

    /** `y` was typed at an approval. Recorded, so a later reader can tell it from [APPROVED_AUTO]. */
    const val APPROVED_YES = "y"

    /** Anything but `y` was typed: the operation did not happen and the model was told. */
    const val APPROVED_NO = "declined"

    /**
     * `omp --yes` answered for the user.
     *
     * **It is a separate value rather than a blank one, and that is the whole point of it.** A
     * transcript line that recorded an automatic write as `y` would be a claim that somebody
     * looked at it; one that recorded nothing would be indistinguishable from a write this build
     * made on its own, which is the one thing a user reading their own folder needs to be able to
     * rule out.
     */
    const val APPROVED_AUTO = "auto"

    /** @return the tool the model named, or null when this build has no such tool. */
    fun byName(name: String): Tool? = ALL.firstOrNull { it.name == name }

    /** The `tools` array for a request: what the model is offered, and nothing that is not here. */
    fun declaration(): Json.Arr = Json.Arr(ALL.map { declaration(it) })

    /**
     * One tool as an endpoint is told about it: the name, the description, and the parameters.
     *
     * The id is absent on purpose. Nothing in this app matches a tool call by id across turns —
     * a transcript line carries strings, not objects — so minting one would be a field in the
     * request that no code here reads.
     */
    fun declaration(tool: Tool): Json.Obj = Json.Obj(
        linkedMapOf(
            TYPE to Json.Str(FUNCTION),
            FUNCTION to Json.Obj(
                linkedMapOf(
                    NAME to Json.Str(tool.name),
                    DESCRIPTION to Json.Str(tool.description),
                    PARAMETERS to tool.parameters,
                ),
            ),
        ),
    )

    /**
     * The sentence a model gets back for a tool this build does not have.
     *
     * It lists what there is, because a model that guessed a name once will guess it again
     * otherwise, and it repeats [NO_RUN] because `run` is the name a model reaches for first and
     * the one answer worth being sure it hears. `omp run` is named too, so a model that has seen
     * the word in a help screen does not conclude the two are the same thing: one is a command
     * the user types, the other is a tool the model calls.
     */
    fun unknown(name: String): String =
        "refused: there is no tool called '$name' in this build. The tools are " +
            ALL.joinToString(", ") { it.name } + ". $NO_RUN Note that 'omp run' is the command " +
            "that starts this session, not a tool you can call."

    /** `{"type":"object","properties":{…},"required":[…]}` with the required list only if there is one. */
    fun schema(properties: Map<String, Json>, required: List<String>): Json.Obj {
        val fields = linkedMapOf<String, Json>(TYPE to Json.Str(OBJECT))
        fields[PROPERTIES] = Json.Obj(properties)
        if (required.isNotEmpty()) {
            fields[REQUIRED] = Json.Arr(required.map { Json.Str(it) })
        }
        return Json.Obj(fields)
    }

    /** One property of a parameter block, with the sentence the model reads instead of the type. */
    fun property(type: String, description: String): Json.Obj = Json.Obj(
        linkedMapOf(
            TYPE to Json.Str(type),
            DESCRIPTION to Json.Str(description),
        ),
    )

    /** How the boundary refused, in a sentence a model can act on rather than only re-read. */
    fun refusal(asked: String, e: FsException): String {
        val where = e.path?.takeIf { it.isNotBlank() } ?: asked
        val errno = e.errno
        val because = when (errno) {
            FsErrno.PERM_DENIED ->
                "it is not inside this conversation's folder, and this build writes nothing outside " +
                    "it — not even a read of the conversations folder above this one"
            FsErrno.SYMLINK_LOOP -> "a symbolic link in the way is a loop"
            else -> errno.text
        }
        return "refused: $where: $errno (${errno.text}): $because. Nothing was changed."
    }

    private const val TYPE = "type"
    private const val FUNCTION = "function"
    private const val NAME = "name"
    private const val DESCRIPTION = "description"
    private const val PARAMETERS = "parameters"
    private const val PROPERTIES = "properties"
    private const val REQUIRED = "required"
    private const val OBJECT = "object"
}

/**
 * What every tool in this build has in common, and the one place a path is checked.
 *
 * **The order is the same for all five: resolve, check, ask, act.** A tool that checked its own
 * path, or that acted before asking, would be a second copy of the decision [Sandbox] exists to
 * make once — and a second copy is a second one that eventually forgets. So the boundary check
 * lives here, [Sandbox.resolve] is the only thing that decides what a tool may touch, and a
 * subclass is handed a [Sandbox.Path] it cannot have arrived at any other way.
 *
 * **The user is asked before anything changes, and only about what is about to change.**
 * [check] runs first precisely so the question is never asked about a call that was going to be
 * refused anyway: being asked to approve an edit whose `old` is not in the file is a question
 * with a rigged answer. [preview] is what the prompt is made of, and it names the path, the same
 * path as a file manager shows it, and the size — because "the model would like to write
 * something" is not something a user can say yes or no to.
 *
 * **The file is read again after the approval.** [check] and [preview] read it to decide and to
 * show; [act] reads it again to change, because the bytes that are there when the user says `y`
 * are the ones the edit has to land on, and a value computed before a keypress went stale is a
 * value nobody approved.
 */
abstract class FileTool : Tool {

    /** Whether a call changes something on the phone, and therefore has to be put to the user. */
    protected open val changesDisk: Boolean get() = false

    final override fun run(env: ToolEnv, arguments: Json.Obj): Outcome = try {
        runChecked(env, arguments)
    } catch (e: FsException) {
        // The seam's own errno, in a sentence the model can read. Nothing a filesystem refuses
        // ends a session; it ends a tool call.
        Outcome.Result(Tools.refusal(arguments.str(PATH) ?: e.path.orEmpty(), e))
    }

    private fun runChecked(env: ToolEnv, arguments: Json.Obj): Outcome {
        val named = arguments.str(PATH)?.takeIf { it.isNotBlank() }
        val path = when {
            named != null -> env.sandbox.resolve(named)
            defaultsToProject -> project(env)
            else -> return Outcome.Result(missingPath())
        }
        val refusal = check(env, path, arguments)
        if (refusal != null) return Outcome.Result(refusal.text)
        if (changesDisk) {
            val lines = preview(env, path, arguments)
            for (line in lines) env.say(line)
            when (env.approve(lines)) {
                Verdict.CANCELLED -> return Outcome.Cancelled
                Verdict.NO -> return Outcome.Result(declined(env, path), Tools.APPROVED_NO)
                Verdict.YES -> Unit
            }
        }
        return Outcome.Result(act(env, path, arguments), if (changesDisk) Tools.APPROVED_YES else null)
    }

    /**
     * The conversation's own folder, as a [Sandbox.Path].
     *
     * [Sandbox.resolve] refuses `.`, `..` and the empty string — a position is not a thing to act
     * on — and that refusal is right for a name a model wrote. It would be wrong for the one path
     * this build hands out itself, because a folder is inside itself. So the project is built
     * here rather than smuggled past [Sandbox.resolve] as a name it would have to start
     * accepting, and it is built the way [Sandbox] builds one: normalized, with `.` for the
     * relative form and the project flag set.
     */
    private fun project(env: ToolEnv): Sandbox.Path {
        val at = PathResolver.normalize(env.sandbox.project)
        return Sandbox.Path(at, PROJECT_DOT, true)
    }

    /** Whether a call that names no `path` acts on the conversation's own folder. */
    protected open val defaultsToProject: Boolean get() = false

    /**
     * Everything that has to be known before the user is asked, and @return a sentence for the
     * model when it is not.
     */
    protected abstract fun check(env: ToolEnv, path: Sandbox.Path, arguments: Json.Obj): Refusal?

    /** The lines the approval prompt is made of. Never printed for a tool that changes nothing. */
    protected abstract fun preview(env: ToolEnv, path: Sandbox.Path, arguments: Json.Obj): List<String>

    /** The work, on a path that is already inside the conversation. */
    protected abstract fun act(env: ToolEnv, path: Sandbox.Path, arguments: Json.Obj): String

    /** The sentence for a call that named no path and has no default one. */
    protected open fun missingPath(): String =
        "refused: this tool needs a 'path' argument, and none was given."

    /** What a refusal of a read looks like: a path that is not a file. */
    protected fun notAFile(env: ToolEnv, path: Sandbox.Path, what: String): Refusal? {
        val stat = env.vfs.stat(path.value)
        if (stat.type == VNodeType.DIRECTORY) {
            return Refusal(
                "refused: ${path.projectRelative} is a directory, not a file. Use list_dir on it.",
            )
        }
        if (stat.type == VNodeType.SYMLINK) {
            return Refusal("refused: ${path.projectRelative} is a symbolic link, and $what.")
        }
        return null
    }

    /** The two header lines every approval prompt starts with: what, and where on the phone. */
    protected fun header(env: ToolEnv, path: Sandbox.Path, what: String): List<String> = listOf(
        "$TAG: the model wants to $what",
        "  in this conversation: ${path.projectRelative}",
        "  on your phone:       ${env.onPhone(path)}",
    )

    /** The sentence the model gets when the user said no to this operation. */
    protected fun declined(env: ToolEnv, path: Sandbox.Path): String =
        "refused: the user was asked to approve this and declined, so nothing was written. " +
            "The file ${path.projectRelative} is unchanged. Say what you wanted to do differently, " +
            "or ask the user to make the change themselves."

    /** [Agent.TAG], aliased so an approval prompt reads like every other line the agent prints. */
    protected val TAG = Agent.TAG

    /**
     * Why a result is over the cap, said with the number in it.
     *
     * @param what the file's name as the model wrote it, for the sentence to be about.
     * @param size the size in bytes, which is what the model cannot see and needs.
     * @param how what to do instead, in the tool's own terms — which for [ReadFile] is an offset
     *   and a limit, and is the whole reason a refusal is better than a truncation.
     */
    protected fun tooBig(what: String, size: Long, how: String): String =
        "refused: $what is $size bytes, over the ${Tools.MAX_RESULT_BYTES}-byte limit for one " +
            "tool result. Nothing was truncated and nothing was sent back. $how"

    protected companion object {
        /** The name of the path argument, and the only name [runChecked] reads itself. */
        protected const val PATH = "path"

        /** The relative form [Sandbox] gives the project itself, repeated here only to build one. */
        private const val PROJECT_DOT = "."
    }
}
