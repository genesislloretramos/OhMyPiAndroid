package omp.agent

import omp.agent.store.AgentState
import omp.agent.store.KeyStore
import omp.shell.exec.ExecContext
import omp.shell.exec.printUsage
import omp.vm.launcher.OmpCommand
import java.io.File
import java.io.IOException

/**
 * `omp key`: the one command in the app that handles a credential.
 *
 * **The secret is read as bytes and never written anywhere but the key store.** The prompt turns
 * the terminal's echo off by never echoing at all: [readSecret] reads a byte, keeps it or acts on
 * it, and writes nothing until the Enter that ends the line. That is not a smaller version of
 * echo-off — a phone keyboard, a screen recording and a person over a shoulder all read the same
 * buffer, and a `*` per character is a length a key does not need to give away.
 *
 * Nothing here can print the secret, because nothing here holds it in a string that goes
 * anywhere: it goes from [readSecret] to [omp.agent.store.KeyStore.put] and is dropped, and every
 * message afterwards names the **provider** and the **file**. That includes the refusals — a key
 * that is empty, holds a newline or is over the cap is refused by the store in its own words, and
 * those words do not quote it either. The two shapes the user did not ask for, `--show` and
 * `--list`, are therefore no different as far as the secret is concerned: they report a provider,
 * a path and a byte count, and there is no code path from them to the file's contents.
 *
 * ### Why the provider can be asked for
 *
 * A key is stored per provider and the store has no idea which one this conversation means, so
 * [set] takes the name from the conversation's own state file when there is one and asks for it
 * when there is not. Asking for a name is asking the user to type something that is not a secret,
 * which is why it is echoed while the key is not.
 */
class KeyCommand(
    private val ctx: ExecContext,
    private val store: KeyStore,
) {

    /**
     * The four shapes, in the order the flags are read.
     *
     * `omp key` sets, `omp key --show` describes the one this conversation uses, `omp key --list`
     * describes every provider there is, and `omp key --forget P` takes exactly one away. Two
     * modes at once is a usage error rather than a best guess, and an option this build does not
     * have is named: a flag that is silently ignored is a user who believes a key was stored
     * somewhere it is not.
     */
    fun run(operands: List<String>): Int {
        val flags = operands.filter { it.startsWith("--") }
        val rest = operands.filterNot { it.startsWith("--") }
        val unknown = flags.firstOrNull { it != SHOW && it != LIST && it != FORGET }
        if (unknown != null) {
            ctx.errLine("$TAG key: unknown option '$unknown'")
            return usage()
        }
        if (flags.filter { it != FORGET }.size > 1) {
            ctx.errLine("$TAG key: one of $SHOW, $LIST at a time")
            return usage()
        }
        if (rest.isNotEmpty() && flags.isEmpty()) {
            ctx.errLine("$TAG key: it takes no arguments; the provider comes from this conversation")
            return usage()
        }
        return when {
            flags.contains(LIST) -> list()
            flags.contains(SHOW) -> show()
            flags.contains(FORGET) -> forget(rest)
            else -> set()
        }
    }

    /**
     * `omp key`: prompt for the key and store it.
     *
     * Nothing is written until [omp.agent.store.KeyStore.put] has accepted the value, so a key
     * that is refused leaves the previous one exactly as it was. The two lines afterwards name the
     * provider and the file, which are the two facts a user needs to be able to find it again.
     */
    private fun set(): Int {
        if (!Agent.mayPrompt(ctx)) {
            ctx.outLine("$TAG key: stdin is not the terminal this command owns, so nothing is asked")
            ctx.outLine("$TAG key: a key is typed on a terminal, with the echo off, and nowhere else")
            ctx.flush()
            return ExecContext.EXIT_USAGE
        }
        val provider = configured() ?: askProvider() ?: return cancelled()
        val target = store.pathOf(provider)
        ctx.outLine("$TAG key: paste the $provider key now; nothing is shown as you type it")
        ctx.flush()
        val secret = readSecret() ?: return cancelled()
        return try {
            store.put(provider, secret)
            ctx.outLine("$TAG key: stored the $provider key in $target")
            ctx.outLine("$TAG key: it is not in the conversation folder, and $TAG never prints it")
            ctx.flush()
            ExecContext.EXIT_OK
        } catch (e: IllegalArgumentException) {
            // The store's own wording, which names the file and never the value.
            ctx.errLine("$TAG key: ${e.message}")
            ctx.flush()
            ExecContext.EXIT_USAGE
        } catch (e: IOException) {
            ctx.errLine("$TAG key: ${e.message}")
            ctx.flush()
            ExecContext.EXIT_GENERAL_ERROR
        }
    }

    /**
     * `omp key --show`: the provider, the file, and how many bytes are in it.
     *
     * A byte count rather than a masked prefix, on purpose. A prefix of a key is a credential
     * that has leaked — the whole argument in [omp.agent.store.KeyStore]'s KDoc — and a length is
     * not one: it is the difference between "there is a key here" and "there is not", which is
     * the question a user running `--show` is actually asking.
     */
    private fun show(): Int {
        val provider = configured()
        if (provider == null) {
            ctx.errLine("$TAG key: this conversation has no provider chosen, so there is no key to show")
            val names = store.providers()
            if (names.isEmpty()) {
                ctx.errLine("$TAG key: no provider has a key yet; 'omp key' stores one")
            } else {
                for (name in names) ctx.errLine("$TAG key: $name is stored, in ${store.pathOf(name)}")
            }
            ctx.flush()
            return ExecContext.EXIT_USAGE
        }
        val target = store.pathOf(provider)
        if (!store.has(provider)) {
            ctx.outLine("$TAG key: no key for $provider yet; it would go in $target")
            ctx.flush()
            return ExecContext.EXIT_USAGE
        }
        ctx.outLine("$TAG key: $provider")
        ctx.outLine("  file:    $target")
        ctx.outLine("  bytes:   ${bytesAt(target)}")
        ctx.outLine("  the key itself is never printed, by this command or by any other")
        ctx.flush()
        return ExecContext.EXIT_OK
    }

    /**
     * `omp key --list`: every provider with a key, its file, and its size.
     *
     * Works outside a conversation and on a pipe, because it asks nothing and changes nothing. An
     * empty store says so in one line rather than printing a header over nothing.
     */
    private fun list(): Int {
        val names = store.providers()
        if (names.isEmpty()) {
            ctx.outLine("$TAG key: no provider has a key yet; 'omp key' stores one")
            ctx.flush()
            return ExecContext.EXIT_OK
        }
        for (name in names) {
            val target = store.pathOf(name)
            ctx.outLine("  %-16s %s (%s bytes)".format(name, target, bytesAt(target)))
        }
        ctx.outLine("$TAG key: ${plural(names.size, "provider")}, and no part of any of them above")
        ctx.flush()
        return ExecContext.EXIT_OK
    }

    /**
     * `omp key --forget P`: take one provider's key away, and say which one.
     *
     * The name is required and is not defaulted to the conversation's provider, because a delete
     * whose target is implied is a delete nobody can be sure of. A provider that had no key says
     * so and is not an error: the state the user asked for is the state they are in.
     */
    private fun forget(rest: List<String>): Int {
        if (rest.size > 1) {
            ctx.errLine("$TAG key: one provider at a time")
            return usage()
        }
        val provider = rest.firstOrNull()
        if (provider == null) {
            ctx.errLine("$TAG key: name the provider to forget, as: $TAG key $FORGET NAME")
            return usage()
        }
        val target = try {
            store.pathOf(provider)
        } catch (e: IllegalArgumentException) {
            ctx.errLine("$TAG key: ${e.message}")
            ctx.flush()
            return ExecContext.EXIT_USAGE
        }
        return try {
            if (store.forget(provider)) {
                ctx.outLine("$TAG key: forgot the $provider key; $target is gone")
            } else {
                ctx.outLine("$TAG key: there was no $provider key; $target was not there")
            }
            ctx.flush()
            ExecContext.EXIT_OK
        } catch (e: IOException) {
            ctx.errLine("$TAG key: ${e.message}")
            ctx.flush()
            ExecContext.EXIT_GENERAL_ERROR
        }
    }

    // ---- the two reads -----------------------------------------------------------------------

    /**
     * The provider this conversation is configured for, or null when it names none.
     *
     * A state file from a newer build reads as naming none rather than being read: its keys may
     * have been renamed, and a `--forget` aimed at a field this build does not understand is a
     * delete in the wrong direction.
     */
    private fun configured(): String? {
        val dir = ctx.env[OmpCommand.ENV_WORKSPACE]?.takeIf { it.isNotBlank() } ?: return null
        return AgentState(ctx.session.vfs, dir).read().config.provider?.takeIf { it.isNotBlank() }
    }

    /**
     * The provider, asked for and **echoed**: it is a name, and a name the user cannot see is a
     * name they cannot check.
     */
    private fun askProvider(): String? {
        ctx.out("provider name: ")
        ctx.flush()
        val typed = readLine() ?: return null
        if (typed.isBlank()) {
            ctx.errLine("$TAG key: a key is stored per provider, so a name is needed")
            ctx.flush()
            return null
        }
        return typed
    }

    /**
     * The key, with the terminal's echo off — which here means the terminal is never written to
     * until the Enter that ends the line.
     *
     * Backspace and delete are honoured on the way in, because a pasted key with a character
     * picked up from a previous line is a key the provider will reject as wrong and the user
     * cannot see the character to remove it. Ctrl-C gives up and stores nothing. The returned
     * string goes straight to [omp.agent.store.KeyStore.put]; no local outlives the call, and no
     * message in this class can quote it.
     */
    private fun readSecret(): String? {
        val typed = StringBuilder()
        while (true) {
            val c = ctx.stdin.read()
            if (c < 0 || c == CTRL_C) {
                // The newline [readLine] writes on the same branch, and for the same reason: the
                // prompt is on the line already, and without this the shell's next prompt is
                // appended to it. Nothing has been echoed, so this is the only thing the terminal
                // ever gets from the key the user was typing.
                ctx.outLine()
                ctx.flush()
                return null
            }
            if (c == '\n'.code || c == '\r'.code) {
                ctx.outLine()
                ctx.flush()
                return typed.toString()
            }
            if (c == 0x08 || c == 0x7F) {
                if (typed.isNotEmpty()) typed.setLength(typed.length - 1)
                continue
            }
            typed.append(c.toChar())
        }
    }

    /** One echoed line: the provider prompt's half of the two reads, which is not a secret. */
    private fun readLine(): String? {
        val typed = StringBuilder()
        while (true) {
            val c = ctx.stdin.read()
            if (c < 0 || c == CTRL_C) {
                ctx.outLine()
                ctx.flush()
                return null
            }
            if (c == '\n'.code || c == '\r'.code) {
                ctx.outLine()
                ctx.flush()
                return typed.toString().trim()
            }
            if (c == 0x08 || c == 0x7F) {
                if (typed.isNotEmpty()) {
                    typed.setLength(typed.length - 1)
                    ctx.out("\b \b")
                }
                continue
            }
            typed.append(c.toChar())
            ctx.out(c.toChar().toString())
            ctx.flush()
        }
    }

    // ---- the small facts ----------------------------------------------------------------------

    /** The file's size in bytes, which is the whole of what `--show` and `--list` report. */
    private fun bytesAt(path: String): Long = runCatching { File(path).length() }.getOrDefault(0L)

    /** `1 provider` or `3 providers`: the one place a count is turned into a word. */
    private fun plural(count: Int, noun: String): String =
        if (count == 1) "1 $noun" else "$count ${noun}s"

    /** A prompt the user walked away from, in this command's own words. */
    private fun cancelled(): Int {
        ctx.errLine("$TAG key: cancelled; nothing was stored")
        ctx.flush()
        return ExecContext.EXIT_INTERRUPTED
    }

    private fun usage(): Int {
        printUsage(ctx.stderr, "$TAG key [--show | --list | $FORGET NAME]")
        return ExecContext.EXIT_USAGE
    }

    private companion object {
        /** [Agent.TAG], aliased so every line of this command reads `omp key: …`. */
        const val TAG = Agent.TAG

        const val SHOW = "--show"
        const val LIST = "--list"
        const val FORGET = "--forget"
        const val CTRL_C = 0x03
    }
}
