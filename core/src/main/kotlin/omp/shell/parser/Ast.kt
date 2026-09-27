package omp.shell.parser

/** A redirect operator as typed. */
class Redirect(val op: String, val target: Word) {
    /** 0 for `<`, otherwise 1 or 2. `&>` reports 1 and sets [stderrToo]. */
    val fd: Int
        get() = when {
            op == "<" -> 0
            op.startsWith("2") -> 2
            else -> 1
        }

    val stderrToo: Boolean get() = op == "&>"

    val append: Boolean get() = op.endsWith(">>")

    /**
     * True when applying this redirect sends the command's **stdout** somewhere else.
     *
     * A pipeline position is not a destination. `cmd > file` is the last stage of a one-stage
     * pipeline, so "am I the last stage" says nothing about where the output goes, and a command
     * that trusts `isTty` there — asking a question into a file, colouring a redirected listing —
     * is believing a lie. `<` and `2>` leave stdout where it was; `>`, `>>`, `&>`, `1>&2` and
     * `1>&-` all move it.
     *
     * It is decided here rather than in the shell because the operator is the whole of the
     * knowledge: everything that is not fd 0 and not fd 2 writes stdout.
     */
    val changesStdout: Boolean get() = fd == 1
}

class CommandNode(
    /** `NAME=VALUE` prefixes; they apply to this command only, and are also exported by `export`. */
    val assignments: List<Pair<String, Word>>,
    val words: List<Word>,
    val redirects: List<Redirect>,
) {
    val name: String? get() = words.firstOrNull()?.raw
}

class PipelineNode(val commands: List<CommandNode>, val background: Boolean)

/** A pipeline plus the `&&` / `||` operators that follow it. */
class AndOrNode(val first: PipelineNode, val rest: List<Pair<String, PipelineNode>>)

class Program(val statements: List<AndOrNode>) {
    val isEmpty: Boolean get() = statements.isEmpty()
}
