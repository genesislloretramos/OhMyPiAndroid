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
