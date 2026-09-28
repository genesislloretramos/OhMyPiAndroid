package com.omp.terminal.android

import com.omp.terminal.ChatService
import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.vm.web.ChatServerStatusHolder
import java.io.File

/**
 * `web`: where the agent's browser front end is, and how to stop it.
 *
 * **This is the terminal's half of the same fact the notification carries.** A phone user who
 * wants the URL, the token and whether the server is up should not have to read a notification to
 * find out, and a developer wondering why nothing is listening should not have to guess. Both are
 * one command away in a shell this app already has.
 *
 * **The answer is read out of the file the service writes when it binds**, not out of a constant.
 * The service prefers one port and falls back to an ephemeral one if that is taken, so a port
 * printed from a constant would be a port that might not be the one listening — and this project's
 * rule is that a path or a port is printed only when the thing that owns it confirmed it. When the
 * file is absent, the honest answer is that the service is not running, and that is what is said.
 *
 * **The token is printed, and that is a decision rather than an oversight.** It is the same token
 * the notification shows and the same one the URL carries: this command is typed on this app's own
 * terminal, by the person who installed the app, on a device that already holds the token. What it
 * must never print is a *model* key, and no line below can — the state this reads is
 * `omp.agent.store.AgentState`, which holds the provider, the endpoint and the model and
 * deliberately does not hold the secret, which lives in a file outside anything a `Vfs` reaches.
 */
@CommandSpec(
    name = "web",
    synopsis = "[stop]",
    group = "system",
    notes = "the agent's browser front end: prints the loopback URL, this install's token and " +
        "where the conversations are; 'web stop' ends the service and gives the port back",
)
object WebCommand : Command {

    /** Long enough for a loopback connect on a busy phone, short enough not to look like a hang. */
    private const val PROBE_MS = 400

    override fun run(ctx: ExecContext): Int = when (val first = ctx.args.firstOrNull()) {
        null -> status(ctx)
        "stop" -> stop(ctx)
        else -> {
            ctx.errLine("web: '${first}' is not a sub-command; 'web' or 'web stop'")
            ExecContext.EXIT_USAGE
        }
    }

    private fun status(ctx: ExecContext): Int {
        val home = File(ctx.services.appFilesDir(), "web")
        val token = File(home, "token").takeIf { it.isFile }?.readText()?.trim().orEmpty()
        val said = File(home, "url").takeIf { it.isFile }?.readText()?.trim().orEmpty()
        // **The port is asked, not the file.** A file records that the service started; it does
        // not record that it is still here. A service the user stopped from its notification takes
        // the socket with it, and a `web` that went on printing that URL would be handing out an
        // address that answers nothing — the small kind of lie this project does not tell, and the
        // kind a user only finds out about when a browser says "connection refused".
        val up = said.isNotEmpty() && listening(said)
        val url = if (up) said else ""
        ctx.outLine("omp: the agent's web front end")
        ctx.outLine("  state:       ${if (up) "running" else "not running"}")
        // The reason, when there is one, printed on the line under the state it belongs to: a
        // "not running" on its own is what a user already knew, and the sentence under it is the
        // platform's own — the one that names the permission. Only a refused service has one, so
        // a server that is merely stopped says nothing here beyond having been stopped.
        ChatServerStatusHolder.current.line()?.let { ctx.outLine("  refused:     $it") }
        if (up) {
            ctx.outLine("  url:         $url")
            ctx.outLine("  token:       ${token.ifEmpty { "(not on disk)" }}")
        } else {
            // Said as a fact about a file rather than as a failure: the token is made on the first
            // start, and a user who has not started the service has none to be given.
            ctx.outLine("  token file:  ${File(home, "token").path}, made on the first start")
            ctx.outLine("  the app makes the server every time it opens; 'web stop' ends it")
        }
        ctx.outLine("  bind:        ${ChatService.LOOPBACK} only, so no other device can reach it")
        ctx.outLine("  conversations: the folder 'omp ls' prints — every one is a plain directory")
        ctx.outLine()
        ctx.outLine("  the token is what stops every other app on this phone reading your")
        ctx.outLine("  conversations. it is not a defence against a rooted phone, or against")
        ctx.outLine("  someone who can see this screen; it is a defence against the other apps.")
        return ExecContext.EXIT_OK
    }

    /**
     * Whether something is accepting connections on the port this URL names.
     *
     * **A connect, not a request.** A `GET /` would prove the server is alive and also prove it is
     * the server, at the price of a route call for a question a shell should answer with one
     * syscall. A refused connect is the whole answer, and a connect that succeeds means a listener
     * is there whatever it is.
     */
    private fun listening(url: String): Boolean {
        val port = url.substringAfter("://", "")
            .substringAfter(':', "")
            .substringBefore('/')
            .toIntOrNull() ?: return false
        return try {
            java.net.Socket().use {
                it.connect(java.net.InetSocketAddress("127.0.0.1", port), PROBE_MS)
                true
            }
        } catch (e: java.io.IOException) {
            false
        }
    }

    private fun stop(ctx: ExecContext): Int {
        val context = AndroidRuntime.appContext
        if (context == null) {
            ctx.errLine("web: there is no Android context bound, so the service cannot be reached")
            return ExecContext.EXIT_GENERAL_ERROR
        }
        ChatService.stop(context)
        ctx.outLine("omp: stopping the web front end; the terminal is not affected")
        return ExecContext.EXIT_OK
    }
}
