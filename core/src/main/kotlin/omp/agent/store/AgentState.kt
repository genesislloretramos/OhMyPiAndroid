package omp.agent.store

import omp.agent.json.Json
import omp.agent.json.JsonException
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.Vfs
import omp.vm.workspace.Workspace

/**
 * The three things a conversation needs to reach a model again after a restart: which provider,
 * whose key, and which model and URL inside it.
 *
 * A field is null for "not chosen yet" rather than an empty string, so a state file written by an
 * older build and a file written by a newer one are the same shape to read and a missing value can
 * never be mistaken for a value the user typed. **The key is not here** — it is in [KeyStore], in
 * the app's private storage, and a state file lives in the user's `Documents` where any app with
 * storage permission could read it.
 */
data class AgentConfig(
    val provider: String? = null,
    /** The provider's base URL, for a self-hosted or proxied endpoint. */
    val baseUrl: String? = null,
    val model: String? = null,
) {
    /**
     * Whether there is enough here to make a request.
     *
     * The provider alone is the minimum, and the model is not: several providers have exactly one
     * model, and asking the user for a string that has only one legal answer teaches them that the
     * question is optional.
     */
    val isConfigured: Boolean get() = !provider.isNullOrBlank()
}

/**
 * What is known of a conversation's state file, which is not the same as whether it is usable.
 *
 * A boolean cannot say the difference between "no state file" and "a state file from a version that
 * does not exist here", and the second is the one where guessing does damage: an older build that
 * reads a newer file, does not understand half the keys and writes its own idea back has destroyed
 * the settings the user has.
 */
enum class StateStatus {
    /** Read back, carrying a version this build wrote or an older one it still understands. */
    LOADED,

    /** No state file: a conversation that has never been configured, which is not a failure. */
    ABSENT,

    /** A file is there, and it is from a version newer than this build. Reported, never guessed at. */
    FUTURE_VERSION,

    /** A file is there and is not this format: hand-written, truncated, or something else's. */
    MALFORMED,

    /**
     * A file is there and cannot be read — a directory where a file should be, or a permission
     * this app does not hold. Distinct from [MALFORMED] because the conversation is still ours and
     * must not be reported as broken settings.
     */
    UNREADABLE,
}

/** What [AgentState.read] found, and the one sentence a command can print about it. */
data class StateFile(val config: AgentConfig, val status: StateStatus, val message: String)

/**
 * The per-conversation configuration, in `.omp/state.json` next to the transcript.
 *
 * Same directory as [Conversation] and for the same reason: it belongs to this conversation's
 * project, it has to survive the app being killed, and a user who wants to see what the agent is
 * configured to talk to should be able to open the file. It is a *file* and not part of the
 * transcript, because the transcript is append-only and this is rewritten whole every time a
 * setting changes — two disciplines that cannot share one file.
 *
 * ### The write discipline
 *
 * Written to [STATE_SCRATCH] in the same directory and renamed over [STATE], which is what
 * `omp.vm.workspace.Workspace.writeMetadata` does and for the same reason: `rename` is atomic
 * within a filesystem, so a reader — this app in another window, a text editor watching the folder,
 * a backup — sees the whole old state or the whole new one, never a prefix. A prefix is the failure
 * that matters here, because a state file that lost its first line has no `version` and would be
 * reported as somebody else's file. A crash between the two writes leaves the scratch behind and
 * the real state exactly as it was; the next [write] overwrites the scratch, and [read] never
 * looks at it.
 */
class AgentState(
    private val vfs: Vfs,
    /** The conversation's namespace path, e.g. `/mnt/omp/notes`. */
    val dir: String,
) {

    /** The state file, inside the conversation's `.omp` directory. */
    val path: String = Workspace.child(Workspace.child(dir, META_DIR), STATE)

    /**
     * The stored configuration, and what is known of the file it came from.
     *
     * An absent file is [StateStatus.ABSENT] with [AgentConfig]'s defaults and no exception: a
     * conversation that has never been pointed at a provider is the normal state of a new one, and
     * a caller that had to catch something to find that out would be writing the same catch in
     * every command.
     *
     * A file from a **newer** version is [StateStatus.FUTURE_VERSION], with the defaults rather
     * than the fields it carries. The keys in it may have been renamed, may mean something else, or
     * may not have existed when this build was written; reading three of them and calling the result
     * this conversation's configuration is a guess with a real cost, so the version number and the
     * one that is understood go into [StateFile.message] and the caller decides.
     */
    fun read(): StateFile {
        val text = try {
            String(vfs.readBytes(path), Charsets.UTF_8)
        } catch (e: FsException) {
            return if (e.errno == FsErrno.NO_SUCH_FILE) {
                StateFile(AgentConfig(), StateStatus.ABSENT, "no $STATE in $path: the defaults are in force")
            } else {
                StateFile(AgentConfig(), StateStatus.UNREADABLE, "cannot read $path: ${e.errno.text}")
            }
        }
        val obj = try {
            Json.parse(text) as? Json.Obj
        } catch (e: JsonException) {
            return malformed(e.message ?: "not JSON")
        } ?: return malformed("not a JSON object")
        val version = obj.long(VERSION)
            ?: return malformed("no readable '$VERSION' number, so this is not a state file this app wrote")
        return if (version > FORMAT_VERSION) {
            StateFile(
                AgentConfig(),
                StateStatus.FUTURE_VERSION,
                "$path is version $version; this build understands $FORMAT_VERSION — nothing was " +
                    "read from it and nothing will be written over it",
            )
        } else {
            StateFile(
                AgentConfig(
                    provider = obj.str(PROVIDER),
                    baseUrl = obj.str(BASE_URL),
                    model = obj.str(MODEL),
                ),
                StateStatus.LOADED,
                if (version == FORMAT_VERSION) {
                    "$path is version $FORMAT_VERSION"
                } else {
                    "$path is version $version, from an older build; the fields it has are read"
                },
            )
        }
    }

    /**
     * Writes [config] as the whole state file, and @return nothing to interpret.
     *
     * @throws IllegalStateException when the file on disk is from a **newer** version, carrying
     *   [read]'s message. This build is not allowed to replace settings it could not read: the
     *   user's provider and model are worth more than this build's opinion about their defaults.
     *   A file that is merely malformed or unreadable is written over — there is nothing in it
     *   this build can use, and refusing would leave the user with no way to configure anything.
     */
    fun write(config: AgentConfig) {
        val found = read()
        if (found.status == StateStatus.FUTURE_VERSION) {
            throw IllegalStateException(found.message)
        }
        vfs.ensureDir(Workspace.child(dir, META_DIR))
        val fields = LinkedHashMap<String, Json>()
        // Version first, always: a file that was cut short loses its last lines, never its first, so
        // the one key that says "this is ours, and this is what we understand" is the one key a
        // reader can count on finding.
        fields[VERSION] = Json.Num(FORMAT_VERSION.toDouble(), FORMAT_VERSION.toString())
        fields[PROVIDER] = config.provider?.let { Json.Str(it) } ?: Json.Null
        fields[BASE_URL] = config.baseUrl?.let { Json.Str(it) } ?: Json.Null
        fields[MODEL] = config.model?.let { Json.Str(it) } ?: Json.Null
        val bytes = (Json.Obj(fields).toCompactString() + "\n").toByteArray(Charsets.UTF_8)
        val scratch = Workspace.child(Workspace.child(dir, META_DIR), STATE_SCRATCH)
        vfs.writeBytes(scratch, bytes)
        vfs.rename(scratch, path)
    }

    private fun malformed(why: String): StateFile = StateFile(
        AgentConfig(),
        StateStatus.MALFORMED,
        "$path is not a state file this build wrote: $why",
    )

    companion object {
        /**
         * Bumped when the keys below change meaning.
         *
         * A file at or below this is read; a file above it is reported and left alone. The number
         * is a `Long` in the file so a version written by a future build stays a number rather
         * than becoming a string this build would have to guess the order of.
         */
        const val FORMAT_VERSION = 1L

        const val VERSION = "version"
        const val PROVIDER = "provider"

        /** The base URL, for a self-hosted or proxied endpoint; absent means the provider's own. */
        const val BASE_URL = "base_url"

        const val MODEL = "model"
    }
}
