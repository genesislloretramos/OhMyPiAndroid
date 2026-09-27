package omp.vm.launcher

import omp.shell.exec.ExecContext

/**
 * What a question this terminal asked was answered with.
 *
 * **Three answers and not two**, because the third is the one this project refuses to fold into
 * the others: Enter on an empty prompt is a mis-key — the second Enter of a double-Enter — and
 * reporting it as a `^C` would put a Ctrl-C in `$?` that never happened. Each of the three says
 * something different to a caller, so each is its own type and a caller cannot forget which one it
 * has.
 */
internal sealed class TerminalAnswer {

    /** What was typed before the Enter. Trimmed, and never empty — that is [Empty]. */
    data class Typed(val text: String) : TerminalAnswer()

    /** Enter on an empty prompt. */
    object Empty : TerminalAnswer()

    /** A Ctrl-C, a closed channel, or a job cancelled while the question was on screen. */
    object Cancelled : TerminalAnswer()
}

/**
 * Reads one answer off the terminal, a byte at a time, until the user presses Enter or cancels.
 *
 * **It reads [ExecContext.stdin] and nothing else**, which in a session is the very
 * [omp.shell.InputChannel] the line editor reads — so a Ctrl-C arrives here as well as on the job it
 * would cancel, and a `y` typed at an approval prompt is the same keystroke the same way round. It
 * is how `less` reads its `/` pattern too, which is the precedent: a question on this terminal is a
 * byte, not a line, because a line editor is already holding the line.
 *
 * **One implementation, because there are now two questions.** `omp`'s own "type a number to
 * continue" and `omp provision`'s "type y to download that" are both a blocking read of one key,
 * and a second copy of the loop is a second copy that will eventually disagree with the first in
 * the case that matters: a terminal in a state where one of them waits and the other returns.
 * Whether a question may be asked at all is a different rule and it lives in
 * [omp.agent.Agent.mayPrompt], which this does not restate.
 */
internal fun askOnTerminal(ctx: ExecContext): TerminalAnswer {
    val line = StringBuilder()
    while (true) {
        if (ctx.cancelled.get()) return TerminalAnswer.Cancelled
        val c = ctx.stdin.read()
        if (c < 0 || c == CTRL_C) return TerminalAnswer.Cancelled
        if (c == '\n'.code || c == '\r'.code) {
            val typed = line.toString().trim()
            return if (typed.isEmpty()) TerminalAnswer.Empty else TerminalAnswer.Typed(typed)
        }
        if (c == 0x08 || c == 0x7F) {
            if (line.isNotEmpty()) line.setLength(line.length - 1)
        } else {
            line.append(c.toChar())
        }
    }
}

/** The Ctrl-C byte, named once because two files now read for it. */
private const val CTRL_C = 0x03
