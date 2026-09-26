package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.exec.printUsage
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

@CommandSpec(
    name = "sed",
    synopsis = "[-ni] [-e SCRIPT]... [file ...]",
    group = "text",
    notes = "s/RE/REPL/[gpi], /RE/d, /RE/p, p, d, q, with the addresses N, $ and N,M; patterns are " +
        "extended regular expressions and never span a line",
)
object Sed : FileCommand() {

    override val flagSpec = "ni"
    override val valueSpec = "e:"

    private const val LAST = Int.MAX_VALUE

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        val quiet = 'n' in flags
        val inPlace = 'i' in flags
        val scripts = ArrayList(scriptsFromArgv(ctx))
        val files = ArrayList<String>()
        var scriptTaken = scripts.isNotEmpty()
        var errored = false
        for (op in operands) {
            val resolved = Cmds.resolve(ctx, op)
            if (resolved == null) {
                errored = true
                continue
            }
            if (File(resolved).exists() || scriptTaken) {
                files += op
            } else {
                // The first operand that is not an existing file is the script, as GNU sed reads it.
                scripts += op
                scriptTaken = true
            }
        }
        if (scripts.isEmpty()) {
            ctx.errLine("sed: no edit script specified")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }
        if (inPlace && files.isEmpty()) {
            ctx.errLine("sed: -i requires a file operand")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }

        val commands = ArrayList<Cmd>()
        for (i in scripts.indices) {
            val parsed = parse(ctx, scripts[i], i + 1) ?: return ExecContext.EXIT_USAGE
            commands += parsed
        }

        if (files.isEmpty()) {
            val out = BufferedOutputStream(ctx.stdout, 16 * 1024)
            val status = run(ctx, commands, quiet, Cmds.reader(ctx.stdin), out)
            out.flush()
            return status
        }
        for (op in files) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            val input = Cmds.openInput(ctx, op) ?: run { errored = true; continue }
            if (inPlace) {
                val status = editInPlace(ctx, commands, quiet, input, op)
                if (status != ExecContext.EXIT_OK) return status
                continue
            }
            val out = BufferedOutputStream(ctx.stdout, 16 * 1024)
            val status = try {
                run(ctx, commands, quiet, Cmds.reader(input), out)
            } catch (e: IOException) {
                Errno.report(ctx, "sed", op, e)
                errored = true
                ExecContext.EXIT_OK
            } finally {
                out.flush()
                if (input !== ctx.stdin) input.close()
            }
            if (status != ExecContext.EXIT_OK) return status
        }
        return if (errored) ExecContext.EXIT_GENERAL_ERROR else ExecContext.EXIT_OK
    }

    /**
     * The edit goes to a sibling temp file that is renamed over the original, so an interrupted
     * run cannot leave half a file where a real one was.
     */
    private fun editInPlace(
        ctx: ExecContext,
        commands: List<Cmd>,
        quiet: Boolean,
        input: InputStream,
        op: String,
    ): Int {
        val resolved = Cmds.resolve(ctx, op) ?: return ExecContext.EXIT_GENERAL_ERROR
        val target = File(resolved)
        val temp = File.createTempFile("sed", ".tmp", target.parentFile)
        val mode = booleanArrayOf(target.canRead(), target.canWrite(), target.canExecute())
        var status = ExecContext.EXIT_OK
        try {
            FileOutputStream(temp).use { fos ->
                val out = BufferedOutputStream(fos, 16 * 1024)
                status = run(ctx, commands, quiet, Cmds.reader(input), out)
                out.flush()
                fos.fd.sync()
            }
        } catch (e: IOException) {
            temp.delete()
            Errno.report(ctx, "sed", op, e)
            return ExecContext.EXIT_GENERAL_ERROR
        } finally {
            if (input !== ctx.stdin) input.close()
        }
        if (status != ExecContext.EXIT_OK) {
            temp.delete()
            return status
        }
        temp.setReadable(mode[0], false)
        temp.setWritable(mode[1], false)
        temp.setExecutable(mode[2], false)
        if (!temp.renameTo(target)) {
            ctx.errLine("sed: could not replace $op")
            temp.delete()
            return ExecContext.EXIT_GENERAL_ERROR
        }
        return ExecContext.EXIT_OK
    }

    /**
     * A one-line lookahead is what makes the `$` address mean "last line" while still streaming,
     * and `d` ends the line before `p` or auto-print can see it.
     */
    private fun run(
        ctx: ExecContext,
        commands: List<Cmd>,
        quiet: Boolean,
        reader: BufferedReader,
        out: OutputStream,
    ): Int {
        var pending: String? = reader.readLine()
        var lineNo = 0
        while (pending != null) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            val original = pending ?: break
            pending = reader.readLine()
            lineNo++
            val isLast = pending == null
            var current = original
            var deleted = false
            var quit = false
            for (cmd in commands) {
                if (!cmd.matches(current, lineNo, isLast)) continue
                when (cmd) {
                    is Simple -> when (cmd.op) {
                        'd' -> { deleted = true; break }
                        'p' -> write(out, current)
                        else -> { quit = true; break }
                    }
                    is Addressed -> {
                        if (!cmd.pattern.matcher(current).find()) continue
                        when (cmd.op) {
                            'd' -> { deleted = true; break }
                            'p' -> write(out, current)
                            else -> { quit = true; break }
                        }
                    }
                    is Substitute -> {
                        val replaced = apply(cmd, current) ?: continue
                        current = replaced
                        if (cmd.print) write(out, current)
                    }
                }
            }
            if (!deleted && !quiet) write(out, current)
            if (quit) break
        }
        return ExecContext.EXIT_OK
    }

    private fun write(out: OutputStream, text: String) {
        out.write(text.toByteArray(Charsets.UTF_8))
        out.write('\n'.code)
    }

    /** Applies one substitution; null when the pattern does not match, leaving the line alone. */
    private fun apply(cmd: Substitute, line: String): String? {
        val matcher = cmd.pattern.matcher(line)
        val sb = StringBuilder()
        var last = 0
        var from = 0
        var found = false
        while (matcher.find(from)) {
            found = true
            sb.append(line, last, matcher.start())
            expand(cmd.replacement, matcher, sb)
            if (!cmd.global) {
                last = matcher.end()
                break
            }
            if (matcher.end() == matcher.start()) {
                // A zero-width match still has to advance, or a `s/x`/ pattern with a star never ends.
                if (matcher.end() >= line.length) {
                    last = line.length
                    break
                }
                sb.append(line[matcher.end()])
                last = matcher.end() + 1
                from = matcher.end() + 1
            } else {
                last = matcher.end()
                from = matcher.end()
            }
        }
        if (!found) return null
        sb.append(line, last, line.length)
        return sb.toString()
    }

    private fun expand(replacement: String, matcher: Matcher, sb: StringBuilder) {
        var i = 0
        while (i < replacement.length) {
            val c = replacement[i]
            if (c == '\\' && i + 1 < replacement.length) {
                when (val n = replacement[i + 1]) {
                    in '1'..'9' -> {
                        val group = n - '0'
                        if (group <= matcher.groupCount()) sb.append(matcher.group(group) ?: "")
                        i += 2
                    }
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '&' -> { sb.append(matcher.group()); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> { sb.append(n); i += 2 }
                }
                continue
            }
            if (c == '&') {
                sb.append(matcher.group())
                i++
                continue
            }
            sb.append(c)
            i++
        }
    }

    private interface Cmd {
        fun matches(line: String, lineNo: Int, isLast: Boolean): Boolean
    }

    private class Addr(val from: Int, val to: Int) {
        fun matches(lineNo: Int, isLast: Boolean): Boolean {
            // A lone `$` is the last line only; `N,$` is every line from N on.
            if (from == LAST) return isLast
            if (to == LAST) return lineNo >= from
            return lineNo in from..to
        }
    }

    private class Simple(val op: Char, val addr: Addr?) : Cmd {
        override fun matches(line: String, lineNo: Int, isLast: Boolean) =
            addr?.matches(lineNo, isLast) ?: true
    }

    private class Addressed(val op: Char, val pattern: Pattern, val addr: Addr?) : Cmd {
        override fun matches(line: String, lineNo: Int, isLast: Boolean) =
            addr?.matches(lineNo, isLast) ?: true
    }

    private class Substitute(
        val pattern: Pattern,
        val replacement: String,
        val global: Boolean,
        val print: Boolean,
        val addr: Addr?,
    ) : Cmd {
        override fun matches(line: String, lineNo: Int, isLast: Boolean) =
            addr?.matches(lineNo, isLast) ?: true
    }

    private fun parse(ctx: ExecContext, script: String, index: Int): List<Cmd>? {
        val out = ArrayList<Cmd>()
        var i = 0
        while (i < script.length) {
            val c = script[i]
            if (c == ';' || c == '\n' || c == ' ' || c == '\t') {
                i++
                continue
            }
            if (c == '#') {
                while (i < script.length && script[i] != '\n') i++
                continue
            }
            val commandStart = i
            var addr: Addr? = null
            val parsed = parseAddress(script, i)
            if (parsed != null) {
                addr = parsed.first
                i = parsed.second
                if (i >= script.length) {
                    fail(ctx, index, i, "no command")
                    return null
                }
            }
            when (val command = script[i]) {
                's' -> {
                    i++
                    if (i >= script.length) {
                        fail(ctx, index, i, "no separator")
                        return null
                    }
                    val delim = script[i]
                    if (delim.isLetterOrDigit() || delim == '\\' || delim == '\n') {
                        fail(ctx, index, i, "unterminated `s' command")
                        return null
                    }
                    i++
                    val regexEnd = findUnescaped(script, i, delim)
                    if (regexEnd < 0) {
                        fail(ctx, index, i, "unterminated `s' command")
                        return null
                    }
                    val patternText = unescapeDelim(script.substring(i, regexEnd), delim)
                    i = regexEnd + 1
                    val replacementEnd = findUnescaped(script, i, delim)
                    if (replacementEnd < 0) {
                        fail(ctx, index, i, "unterminated `s' command")
                        return null
                    }
                    val replacement = unescapeDelim(script.substring(i, replacementEnd), delim)
                    i = replacementEnd + 1
                    var global = false
                    var print = false
                    while (i < script.length && script[i] !in "; \n") {
                        when (script[i]) {
                            'g' -> global = true
                            'p' -> print = true
                            'i' -> Unit
                            else -> {
                                fail(ctx, index, i, "unknown option to `s'")
                                return null
                            }
                        }
                        i++
                    }
                    val pattern = try {
                        Pattern.compile(patternText)
                    } catch (e: PatternSyntaxException) {
                        fail(ctx, index, commandStart, e.description ?: "invalid pattern")
                        return null
                    }
                    out += Substitute(pattern, replacement, global, print, addr)
                }
                '/' -> {
                    i++
                    val regexEnd = findUnescaped(script, i, '/')
                    if (regexEnd < 0) {
                        fail(ctx, index, i, "unterminated `/' command")
                        return null
                    }
                    val patternText = unescapeDelim(script.substring(i, regexEnd), '/')
                    i = regexEnd + 1
                    if (i >= script.length) {
                        fail(ctx, index, i, "no command")
                        return null
                    }
                    val pattern = try {
                        Pattern.compile(patternText)
                    } catch (e: PatternSyntaxException) {
                        fail(ctx, index, i, e.description ?: "invalid pattern")
                        return null
                    }
                    val verb = script[i]
                    i++
                    if (verb != 'd' && verb != 'p' && verb != 'q') {
                        fail(ctx, index, i - 1, "unknown command: $verb")
                        return null
                    }
                    out += Addressed(verb, pattern, addr)
                }
                'd', 'p', 'q' -> {
                    out += Simple(command, addr)
                    i++
                }
                else -> {
                    fail(ctx, index, commandStart, "unknown command: $command")
                    return null
                }
            }
        }
        return out
    }

    private fun parseAddress(script: String, at: Int): Pair<Addr, Int>? {
        var i = at
        val from: Int
        if (script[i] == '$') {
            from = LAST
            i++
        } else {
            val start = i
            while (i < script.length && script[i] in '0'..'9') i++
            if (i == start) return null
            from = script.substring(start, i).toInt()
        }
        if (i < script.length && script[i] == ',') {
            i++
            var to = LAST
            if (i < script.length && script[i] == '$') {
                to = LAST
                i++
            } else {
                val start = i
                while (i < script.length && script[i] in '0'..'9') i++
                if (i > start) to = script.substring(start, i).toInt()
            }
            return Addr(from, to) to i
        }
        return Addr(from, from) to i
    }

    /** The next delimiter that is not preceded by a backslash, or -1 when there is none. */
    private fun findUnescaped(script: String, from: Int, delim: Char): Int {
        var i = from
        while (i < script.length) {
            val c = script[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == delim) return i
            i++
        }
        return -1
    }

    /** Only the delimiter, \n and \t are unescaped here; the rest reaches the regex engine. */
    private fun unescapeDelim(text: String, delim: Char): String {
        if (text.indexOf('\\') < 0) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                val n = text[i + 1]
                if (n == delim || n == 'n' || n == 't' || n == '\\') {
                    sb.append(if (n == 'n') '\n' else if (n == 't') '\t' else n)
                    i += 2
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun fail(ctx: ExecContext, index: Int, at: Int, reason: String) {
        ctx.errLine("sed: -e expression #$index, char ${at + 1}: $reason")
    }

    /**
     * `-e` may be given more than once and a Map<String, String> can only hold the last value, so
     * the order of the scripts is recovered from argv.
     */
    private fun scriptsFromArgv(ctx: ExecContext): List<String> {
        val out = ArrayList<String>()
        val argv = ctx.argv
        var i = 1
        while (i < argv.size) {
            val a = argv[i]
            if (a == "--") break
            if (a == "-" || !a.startsWith("-")) break
            if (a.length > 1 && a[1] == 'e') {
                if (a.length > 2) {
                    out += a.substring(2)
                    i++
                } else {
                    i++
                    if (i < argv.size) {
                        out += argv[i]
                        i++
                    }
                }
                continue
            }
            i++
        }
        return out
    }
}
