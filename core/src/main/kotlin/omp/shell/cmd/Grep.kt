package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.exec.printUsage
import omp.shell.fs.FsException
import omp.shell.fs.VNodeType
import omp.shell.fs.Vfs
import omp.shell.fs.resolveSymlinks
import java.io.IOException
import java.io.InputStream
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

@CommandSpec(
    name = "grep",
    synopsis = "[-inrvlqwEcF] [-e PATTERN] [--include=GLOB] pattern [file ...]",
    group = "text",
    notes = "patterns are extended regular expressions, so + ? \\| () and {m,n} need no backslash; " +
        "there is no -P, and -F is a fixed string",
)
object Grep : FileCommand() {

    override val flagSpec = "inrvlqwEcF"
    override val valueSpec = "e:"
    override val longOptions = mapOf("include" to true)

    private const val NO_MATCH = 0
    private const val MATCHED = 1
    private const val MATCHED_STOP = 2
    private const val CANCELLED = 3

    /** A NUL byte is what makes a file binary; a decoded U+0000 in a text line is the same thing. */
    private const val NUL = '\u0000'

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        val fixed = flags.indexOf('F') >= 0
        val wholeWord = flags.indexOf('w') >= 0
        val ignoreCase = flags.indexOf('i') >= 0
        val fromE = options["e"]
        val rawPatterns: List<String>
        val files: List<String>
        if (fromE != null) {
            rawPatterns = listOf(fromE)
            files = operands
        } else {
            if (operands.isEmpty()) {
                printUsage(ctx.stderr, usageLine(ctx))
                return ExecContext.EXIT_USAGE
            }
            rawPatterns = listOf(operands[0])
            files = operands.drop(1)
        }

        val patterns = ArrayList<Pattern>(rawPatterns.size)
        for (raw in rawPatterns) {
            // \b is wrong for a pattern that starts or ends with punctuation, so -w wraps the
            // pattern in look-arounds around a word character instead.
            val body = if (fixed) Pattern.quote(raw) else raw
            val expr = if (wholeWord) "(?<!\\w)(?:$body)(?!\\w)" else body
            val compiled = try {
                if (ignoreCase) Pattern.compile(expr, Pattern.CASE_INSENSITIVE) else Pattern.compile(expr)
            } catch (e: PatternSyntaxException) {
                ctx.errLine("grep: ${e.description ?: "invalid regular expression"}")
                return ExecContext.EXIT_USAGE
            }
            patterns += compiled
        }
        val include = options["include"]?.let { globRegex(it) }

        val targets = ArrayList<Target>()
        var errored = false
        if (files.isEmpty()) {
            // No file operand: grep reads standard input, which is what `cmd | grep` produces.
            targets += Target("(standard input)", "-")
        }
        val vfs = ctx.session.vfs
        for (op in files) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            val resolved = Cmds.resolve(ctx, op) ?: run { errored = true; continue }
            // `grep -r` dereferences a command-line operand, and always did here; links met inside
            // the recursion are still left alone.
            val stat = Cmds.statFollowedOrNull(vfs, resolved)
            if ('r' in flags && stat?.type == VNodeType.DIRECTORY) {
                if (!walk(ctx, vfs, op, resolved, include, targets, HashSet())) errored = true
            } else {
                targets += Target(displayName(op), op)
            }
        }

        val spec = Spec(
            patterns = patterns,
            invert = 'v' in flags,
            numbers = 'n' in flags,
            listOnly = 'l' in flags,
            quiet = 'q' in flags,
            counts = 'c' in flags,
        )
        val prefixed = targets.size > 1
        var matched = false
        for (t in targets) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            val input = Cmds.openInput(ctx, t.path)
            if (input == null) {
                errored = true
                continue
            }
            val result = try {
                scan(ctx, spec, t.display, input, prefixed)
            } catch (e: IOException) {
                Errno.report(ctx, "grep", t.path, e)
                errored = true
                NO_MATCH
            } finally {
                if (input !== ctx.stdin) input.close()
            }
            when (result) {
                CANCELLED -> return ExecContext.EXIT_INTERRUPTED
                MATCHED, MATCHED_STOP -> matched = true
            }
        }
        if (errored) return ExecContext.EXIT_USAGE
        return if (matched) ExecContext.EXIT_OK else ExecContext.EXIT_GENERAL_ERROR
    }

    private class Spec(
        val patterns: List<Pattern>,
        val invert: Boolean,
        val numbers: Boolean,
        val listOnly: Boolean,
        val quiet: Boolean,
        val counts: Boolean,
    )

    private class Target(val display: String, val path: String)

    private fun displayName(raw: String) = if (raw == "-") "(standard input)" else raw

    /** Depth-first in name order; a canonical-path set is what stops a symlink cycle. */
    private fun walk(
        ctx: ExecContext,
        vfs: Vfs,
        root: String,
        dir: String,
        include: Pattern?,
        out: MutableList<Target>,
        seen: MutableSet<String>,
    ): Boolean {
        if (!seen.add(canonicalOf(vfs, dir))) return true
        val entries = Cmds.listDir(ctx, "grep", vfs, dir) ?: return false
        var ok = true
        for (entry in entries.sortedBy { it.name }) {
            if (ctx.cancelled.get()) return false
            val child = fsChild(dir, entry.name)
            val display = root + "/" + entry.name
            // A link to a directory is walked, and the canonical set is what stops that looping.
            if (fsIsDirFollowing(vfs, child, entry.stat)) {
                if (!walk(ctx, vfs, display, child, include, out, seen)) ok = false
            } else {
                if (include != null && !include.matcher(entry.name).matches()) continue
                out += Target(display, display)
            }
        }
        return ok
    }

    private fun canonicalOf(vfs: Vfs, path: String): String = try {
        resolveSymlinks(vfs, path)
    } catch (e: FsException) {
        path
    }

    /** Only `*` and `?` are wildcards in a --include glob; everything else is literal. */
    private fun globRegex(glob: String): Pattern {
        val sb = StringBuilder()
        for (c in glob) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        return Pattern.compile(sb.toString())
    }

    /**
     * One pass over one input. A binary file stops at its first match instead of spraying the rest
     * of a .apk or a .dex over the screen.
     */
    private fun scan(
        ctx: ExecContext,
        spec: Spec,
        display: String,
        input: InputStream,
        prefixed: Boolean,
    ): Int {
        val matchers: Array<Matcher> = Array(spec.patterns.size) { spec.patterns[it].matcher("") }
        val reader = Cmds.reader(input)
        val prefix = if (prefixed) "$display:" else ""
        var binary = false
        var anyHit = false
        var count = 0
        var lineNo = 0
        while (true) {
            if (ctx.cancelled.get()) return CANCELLED
            val line = reader.readLine() ?: break
            lineNo++
            if (!binary && line.indexOf(NUL) >= 0) binary = true
            var hit = false
            for (m in matchers) {
                m.reset(line)
                if (m.find()) {
                    hit = true
                    break
                }
            }
            if (hit == spec.invert) continue
            anyHit = true
            if (spec.quiet) return MATCHED_STOP
            if (spec.listOnly) {
                ctx.outLine(display)
                return MATCHED_STOP
            }
            if (spec.counts) {
                count++
                continue
            }
            if (binary) {
                ctx.outLine(prefix + "Binary file $display matches")
                return MATCHED_STOP
            }
            ctx.outLine(if (spec.numbers) "$prefix$lineNo:$line" else prefix + line)
        }
        if (spec.counts) ctx.outLine(prefix + count)
        return if (anyHit) MATCHED else NO_MATCH
    }
}
