package omp.agent

import omp.agent.store.KeyStore
import omp.agent.store.META_DIR
import omp.agent.store.TRANSCRIPT
import omp.agent.tools.Tools
import omp.shell.exec.ExecContext
import omp.vm.launcher.OmpCommand
import java.io.File

/**
 * What the agent is, in the four things anything else may ask it for: its version, the words
 * `omp` means when it is the agent rather than the launcher, the key store the app owns, and the
 * help.
 *
 * **A *bare* `omp` has two meanings, and which one you get is decided by where you are.** Outside
 * a conversation it is the launcher, unchanged — it lists folders, makes them, opens them. Inside
 * one, [OmpCommand.ENV_WORKSPACE] is set and the session is standing in that folder, and bare `omp`
 * is the agent: the thing that talks to a model about the folder you are in. The second meaning is
 * the right one *there* and nowhere else, because a conversation is a folder and a model needs a
 * folder; that is the whole reason the two are one command rather than two programs.
 *
 * **[VERBS] is not gated on any of that.** The four verbs belong to the agent wherever they are
 * typed, and each one handles having no conversation behind it in its own words — `omp update`
 * explains that there is nothing to report yet, `omp key` still stores a key (a key is per
 * provider, not per folder), and `omp run` refuses in two lines. That is what makes the list worth
 * having in one place: it is checked **before** a bare name is taken for a conversation, so `omp
 * update` is a question about the agent rather than an attempt to open a folder called `update` —
 * and the price, stated plainly, is that those four names are unopenable as conversation folders,
 * **everywhere**, not only inside one.
 */
object Agent {

    /**
     * This build's agent.
     *
     * A string and not a build number, because nothing reads it back: the agent is the Kotlin
     * inside the app the user is already running, and `omp update` says in as many words that
     * this number cannot be anything but a label for the build in their hands.
     */
    const val VERSION = "0.1.0"
    /**
     * The prefix every line this agent prints carries, so a recording of the screen, or a
     * `grep omp` over one, reads as the agent rather than as whatever else was on the terminal.
     */
    const val TAG = "omp"

    /**
     * The words `omp` means when it is the agent, and the whole of its grammar.
     *
     * The launcher's own words — `new`, `ls`, `rm`, and a bare name — are deliberately **not**
     * here: they keep working inside a conversation, because a folder is still a folder and the
     * launcher is still how you get out of one.
     */
    val VERBS: List<String> = listOf("run", "update", "key", "help")

    /** Whether [word] is one of [VERBS]. Null-safe, so the launcher can ask about a missing word. */
    fun isVerb(word: String?): Boolean = word != null && word in VERBS

    /**
     * The key store, made over the app's own private storage.
     *
     * **This is the one route to a key, and it is not a path.** The store is reached through
     * [KeyStore]'s own directory, which is deliberately outside the namespace the agent runs in:
     * no `Vfs` here, no path a command could be tricked into aiming elsewhere, and nothing in
     * `Documents/omp` — a container any app holding the all-files grant can read — that could
     * reach a key. The app reads the secret and hands it to the transport, and the transport is
     * the only thing in the app that ever holds it.
     */
    fun store(ctx: ExecContext): KeyStore =
        KeyStore(File(ctx.services.appFilesDir())) { ctx.services.wallClockMillis() }

    /**
     * Whether this command may take the terminal, which takes three things being true.
     *
     * **It has to be the terminal.** [omp.shell.InputChannel.owns] is the authority: a pipe, a
     * script and `vm exec` — which runs a line in a throwaway session over a channel nothing will
     * ever write to — all hand a command a stdin that is not this session's, and `isTty` alone
     * would say yes to all three.
     *
     * **It has to be the foreground.** A `&` job gets the same channel and the same `isTty`, and
     * the REPL is back at its prompt before the job has printed a line: a question asked there
     * would take keystrokes the line editor expects, on a thread nobody is waiting for, and the
     * two of them would fight over one keyboard. [omp.shell.Session.foreground] is the same fact
     * Ctrl-C uses, and its [omp.shell.exec.JobGroup.background] says which kind of job this is.
     *
     * **And stdout has to be a terminal**, or the question would be asked into a pipe and never
     * seen.
     *
     * It lives here rather than in [OmpCommand] because two commands now ask a question of this
     * terminal — the launcher's own and the agent's — and a second copy of these three lines is a
     * second copy that can drift from the first at exactly the moment it matters.
     */
    fun mayPrompt(ctx: ExecContext): Boolean {
        val job = ctx.session.foreground ?: return false
        return ctx.isTty && !job.background && ctx.session.input?.owns(ctx.stdin) == true
    }

    /**
     * The five tools, in the words `omp help` and `omp update` use.
     *
     * **One list, read from [Tools.ALL] rather than written out here.** The system prompt, the
     * help page, the `omp update` block and the `tools` array in every request all name the same
     * tools, and a copy of the list in this file is a list that a build adding a sixth tool would
     * update in three of those four places. The failure this guards against is an agent that
     * describes itself out of a different version of itself — and a help page that lists four
     * tools while a model is offered five is that failure exactly.
     */
    val NAMES: String = Tools.ALL.joinToString(", ") { it.name }

    /** What a user is told about what a write costs them: a question, one keypress, every time. */
    const val APPROVAL_FOR_USER =
        "write_file and edit_file are put to you before they happen — the path, the real path " +
            "on your phone, the size, and for an edit the old and the new text. One 'y' goes " +
            "ahead and anything else cancels that one call. 'omp --yes' approves every call in " +
            "that turn without asking, and the transcript records that it did"

    /** What a user is told about the boundary, in the imperative. */
    const val BOUNDARY_FOR_USER =
        "the tools reach this conversation's folder and nothing else: not the folder above it " +
            "with your other conversations in it, and not anything else on the phone"


    /**
     * `omp help`, inside a conversation.
     *
     * Both shapes of `omp key` are written out rather than summarised as `omp key [OPTION]`,
     * because a help that leaves the user guessing which options exist has not answered the
     * question it was asked. What this build cannot do is on the same page as what it can: a user
     * who finds the limits here is a user who is not surprised later.
     */
    fun help(ctx: ExecContext): Int {
        val dir = ctx.env[OmpCommand.ENV_WORKSPACE]?.takeIf { it.isNotBlank() }
        val real = ctx.env[OmpCommand.ENV_WORKSPACE_REAL]?.takeIf { it.isNotBlank() } ?: dir
        ctx.outLine("omp, inside a conversation: the agent, not the launcher")
        if (real != null) {
            ctx.outLine()
            ctx.outLine("  this conversation is $real")
            ctx.outLine("  the model is asked about that folder, and the conversation is kept in")
            ctx.outLine("  $META_DIR/$TRANSCRIPT inside it, so the next run continues it")
        }
        ctx.outLine()
        ctx.outLine("  omp                  ask the model a question and watch the answer arrive;")
        ctx.outLine("                      Ctrl-C stops an answer, Ctrl-C on an empty prompt leaves")
        ctx.outLine("  omp --yes            the same, with every write_file and edit_file approved")
        ctx.outLine("                      without asking, and the transcript saying so")
        ctx.outLine("  omp run              exactly the same thing, spelled out as a verb")
        ctx.outLine("  omp key              set the API key, prompted, with the terminal's echo off")
        ctx.outLine("  omp key --show       the provider, the file it is in, and how many bytes it is")
        ctx.outLine("  omp key --list       every provider that has a key")
        ctx.outLine("  omp key --forget P   take one provider's key away, and only that one")
        ctx.outLine("  omp update           what the agent is: version, provider, endpoint, model,")
        ctx.outLine("                      where the key is kept, and how big the transcript is")
        ctx.outLine("  omp help             this list")
        ctx.outLine()
        ctx.outLine("  the model has ${Tools.ALL.size} tools, and they reach the files in this")
        ctx.outLine("  conversation: $NAMES")
        ctx.outLine("  $BOUNDARY_FOR_USER")
        ctx.outLine("  $APPROVAL_FOR_USER")
        ctx.outLine()
        ctx.outLine("  one thing it was deliberately not given, and the reason:")
        ctx.outLine("  ${Tools.NO_RUN}")
        ctx.outLine()
        ctx.outLine("  outside a conversation omp is the launcher: 'omp new NAME', 'omp ls',")
        ctx.outLine("  'omp rm NAME --force', and a bare name to open one")
        ctx.flush()
        return ExecContext.EXIT_OK
    }
}
