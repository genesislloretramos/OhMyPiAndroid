package omp.shell.exec

import java.io.OutputStream

fun interface Command {
    fun run(ctx: ExecContext): Int
}

/**
 * Implemented by the built-ins that make an assignment prefix persist, as bash's special built-ins
 * do: `export FOO=bar` leaves `FOO` in the shell, while `FOO=bar echo hi` does not.
 */
interface SessionAssignmentCommand {
    fun applyPrefix(ctx: ExecContext, assignments: List<Pair<String, String>>)
}

/**
 * Name plus the one-line synopsis `help` prints. Read by [CommandTable]; there is no reflection
 * scan, only this annotation lookup at explicit registration time.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class CommandSpec(
    val name: String,
    val synopsis: String,
    val group: String = "misc",
    /** One line explaining a limit the platform imposes, when there is one. */
    val notes: String = "",
)

fun printUsage(err: OutputStream, line: String) {
    err.write("usage: $line\n".toByteArray(Charsets.UTF_8))
    err.flush()
}

/**
 * A command with option parsing. An option that is not listed produces
 * `<cmd>: invalid option -- 'x'` plus the usage line on stderr and exit 2; nothing silently ignores
 * an unknown flag anywhere in this shell.
 */
abstract class FileCommand : Command {

    /** Short options that take no value, as a letter string: `"lahrRtS1d"`. */
    protected open val flagSpec: String = ""

    /** Short options that take a value, as `"n:"` — the colon marks the value. */
    protected open val valueSpec: String = ""

    /** Long options, name to takesValue. */
    protected open val longOptions: Map<String, Boolean> = emptyMap()

    final override fun run(ctx: ExecContext): Int {
        val ctx = ctx.withArgv(normalizeArgv(ctx.argv))
        val cmdName = ctx.name
        val flags = StringBuilder()
        val options = HashMap<String, String>()
        val operands = ArrayList<String>()
        var i = 1
        var literal = false
        while (i < ctx.argv.size) {
            val a = ctx.argv[i]
            if (literal || a == "-" || !a.startsWith("-")) {
                operands += a
                i++
                continue
            }
            if (a == "--") {
                literal = true
                i++
                continue
            }
            if (a.startsWith("--")) {
                val eq = a.indexOf('=')
                val name = if (eq < 0) a.substring(2) else a.substring(2, eq)
                val takesValue = longOptions[name]
                if (takesValue == null) {
                    return invalidOption(ctx, cmdName, a)
                }
                if (takesValue) {
                    val v = if (eq >= 0) a.substring(eq + 1) else ctx.argv.getOrNull(++i)
                    if (v == null) {
                        ctx.errLine("$cmdName: option '--$name' requires an argument")
                        printUsage(ctx.stderr, usageLine(ctx))
                        return ExecContext.EXIT_USAGE
                    }
                    options[name] = v
                } else {
                    flags.append('L') // long options set a private marker letter
                    options[name] = ""
                }
                i++
                continue
            }
            var j = 1
            var consumedNext = false
            while (j < a.length) {
                val c = a[j]
                val pos = valueSpec.indexOf(c)
                if (pos >= 0) {
                    flags.append(c)
                    if (valueSpec.getOrNull(pos + 1) == ':') {
                        val v = if (j + 1 < a.length) a.substring(j + 1) else ctx.argv.getOrNull(i + 1)
                        if (v == null) {
                            ctx.errLine("$cmdName: option requires an argument -- '$c'")
                            printUsage(ctx.stderr, usageLine(ctx))
                            return ExecContext.EXIT_USAGE
                        }
                        options[c.toString()] = v
                        if (j + 1 >= a.length) consumedNext = true
                        break
                    }
                    j++
                } else if (flagSpec.indexOf(c) >= 0) {
                    flags.append(c)
                    j++
                } else {
                    return invalidOption(ctx, cmdName, "'$c'")
                }
            }
            i += if (consumedNext) 2 else 1
        }
        return execute(ctx, flags.toString(), options, operands)
    }

    /** Rewrites argv before option parsing, for spellings the parser cannot see (`head -3`). */
    protected open fun normalizeArgv(argv: List<String>): List<String> = argv

    private fun invalidOption(ctx: ExecContext, cmdName: String, shown: String): Int {
        ctx.errLine("$cmdName: invalid option -- $shown")
        printUsage(ctx.stderr, usageLine(ctx))
        return ExecContext.EXIT_USAGE
    }

    protected open fun usageLine(ctx: ExecContext): String {
        val spec = this::class.java.getAnnotation(CommandSpec::class.java) ?: return ctx.name
        return "${spec.name} ${spec.synopsis}"
    }

    protected abstract fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int
}
