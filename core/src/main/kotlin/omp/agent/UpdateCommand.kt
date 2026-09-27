package omp.agent

import omp.agent.store.AgentState
import omp.agent.store.Conversation
import omp.agent.store.KeyStore
import omp.agent.store.StateStatus
import omp.agent.store.sizeOf
import omp.agent.tools.Tools
import omp.shell.exec.ExecContext
import omp.shell.fs.Vfs
import omp.vm.launcher.OmpCommand
import java.io.File

/**
 * `omp update`: what the agent is, and the one thing it cannot do.
 *
 * **There is no update here, and this command is where that is said.** The agent is Kotlin inside
 * the app the user is already running; there is no binary to fetch, no manifest to check and no
 * process to replace one. A command called `update` that printed a progress bar, or a "checking
 * for a newer version" line, or an ellipsis, would be describing a download this app has no way
 * to perform — and the user who sees it has been told a lie by a command whose whole purpose is
 * to be trusted about the agent. So the last line says it, in the only tense that is true: a new
 * agent is a new build of this app, from wherever they got this one.
 *
 * ### What it does report, and why
 *
 * Everything a user needs to answer "what am I about to talk to, and where are my things": the
 * version, the provider, the endpoint the key would be sent to, the model, the **file** the key
 * is in, and the transcript's size against its cap. The last of those is the one a user cannot get
 * anywhere else, because a cap that is silently approaching looks exactly like an app that has
 * stopped saving.
 *
 * A state file from a **newer** build is reported and nothing is changed: the version it carries
 * is printed, its own message says what this build understands, and no field of it is read into a
 * line that would claim to be this conversation's configuration.
 */
class UpdateCommand(
    private val ctx: ExecContext,
    private val store: KeyStore,
) {

    /**
     * The identity block, or — outside a conversation — the two lines that say there is nothing to
     * report and how to get something to report.
     *
     * Both answers are [ExecContext.EXIT_OK]: the user asked what this is and was told, and a
     * status of 1 on a question that was answered would say the *agent* had failed. What the
     * command deliberately does not do is make a folder: an `omp update` outside a conversation
     * opens nothing, and a test can watch the container to prove it.
     */
    fun run(): Int {
        val dir = ctx.env[OmpCommand.ENV_WORKSPACE]?.takeIf { it.isNotBlank() }
        if (dir == null) {
            ctx.outLine("$TAG update: this is not a conversation, so there is nothing to report")
            ctx.outLine("$TAG update: the agent works in a conversation folder and nowhere else;")
            ctx.outLine("$TAG update: 'omp new NAME' makes one and puts you in it, 'omp ls' lists them")
            ctx.outLine("  $NOTHING_HAPPENED")
            ctx.flush()
            return ExecContext.EXIT_OK
        }
        val vfs = ctx.session.vfs
        val state = AgentState(vfs, dir)
        val found = state.read()
        val config = found.config
        val conversation = Conversation(vfs, dir)

        ctx.outLine("omp agent ${Agent.VERSION}")
        ctx.outLine("  folder:     ${real(dir)}")
        ctx.outLine("  provider:   ${orNotSet(config.provider)}")
        ctx.outLine("  endpoint:   ${endpointOf(config.baseUrl)}")
        ctx.outLine("  model:      ${orNotSet(config.model)}")
        ctx.outLine("  key:        ${keyLine(config.provider)}")
        ctx.outLine("  transcript: ${transcriptLine(vfs, conversation)}")
        ctx.outLine("  tools:      ${Agent.NAMES} — inside this conversation only")
        ctx.outLine("  writes:     every write_file and edit_file is put to the user first;")
        ctx.outLine("              'omp --yes' approves them all, and the transcript records it")
        ctx.outLine("  ${Tools.NO_RUN}")
        ctx.outLine("  state:      ${found.message}")
        if (found.status == StateStatus.FUTURE_VERSION) {
            ctx.outLine("  nothing in that state file was read and nothing will be written over it")
        }
        ctx.outLine("  $NOTHING_HAPPENED")
        ctx.flush()
        return ExecContext.EXIT_OK
    }

    /** The provider's file and whether anything is in it, and never a byte of the key. */
    private fun keyLine(provider: String?): String {
        if (provider.isNullOrBlank()) return "not set — 'omp key' asks for one"
        val target = runCatching { store.pathOf(provider) }.getOrNull() ?: return "not set"
        val bytes = runCatching { File(target).length() }.getOrDefault(0L)
        return if (bytes > 0) "$target ($bytes bytes, never printed)" else "not set — $target"
    }

    /**
     * The chat endpoint the key would be sent to, and the refusal if there is not one to send it
     * to. [Endpoint.of] is the same check the loop makes, so `omp update` cannot describe an
     * endpoint the agent would refuse a minute later.
     */
    private fun endpointOf(baseUrl: String?): String {
        if (baseUrl.isNullOrBlank()) return "not set"
        return when (val endpoint = Endpoint.of(baseUrl)) {
            // `endpoint.baseUrl` rather than the configured string: it is the same URL with any
            // `user:password@` prefix already gone, so nothing about a credential can reach here.
            is Endpoint.Refused -> "${endpoint.baseUrl} — refused: ${endpoint.reason}"
            is Endpoint.Ready ->
                endpoint.url + Endpoint.CHAT + if (endpoint.secure) "" else "   (http, not https)"
        }
    }

    /** Entries and bytes against the cap: the one number here the user cannot get elsewhere. */
    private fun transcriptLine(vfs: Vfs, conversation: Conversation): String {
        val entries = conversation.transcript().entries.size
        val bytes = vfs.sizeOf(conversation.path)
        val noun = if (entries == 1) "entry" else "entries"
        return "${conversation.path} — $entries $noun, $bytes of ${Conversation.MAX_TRANSCRIPT_BYTES} bytes"
    }

    /** The folder as a file manager shows it, which is the name a user would recognise. */
    private fun real(dir: String): String =
        ctx.env[OmpCommand.ENV_WORKSPACE_REAL]?.takeIf { it.isNotBlank() } ?: dir

    private fun orNotSet(value: String?): String = if (value.isNullOrBlank()) "not set" else value

    private companion object {
        /** [Agent.TAG], aliased so every line of this command reads `omp update: …`. */
        const val TAG = Agent.TAG

        /** The one line that makes the name of this command true, in the present tense. */
        const val NOTHING_HAPPENED =
            "this command does not download anything and cannot: the agent is Kotlin inside the " +
                "app you are already running, so a newer agent is a newer build of this app"
    }
}
