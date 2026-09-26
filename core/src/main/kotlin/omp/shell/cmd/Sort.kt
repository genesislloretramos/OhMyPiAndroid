package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.exec.printUsage
import java.io.IOException
import java.io.InputStream
import java.util.Locale

@CommandSpec(
    name = "sort",
    synopsis = "[-nrfuhb] [-k START[,END]] [-t SEP] [file ...]",
    group = "text",
    notes = "C-locale byte order; -b ignores leading blanks, -h orders human numbers such as 1K or 2M",
)
object Sort : FileCommand() {

    override val flagSpec = "nrfuhb"
    override val valueSpec = "k:t:"

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        val sep = options["t"]?.let {
            if (it.length != 1) {
                ctx.errLine("sort: delimiter must be a single character")
                printUsage(ctx.stderr, usageLine(ctx))
                return ExecContext.EXIT_USAGE
            }
            it[0]
        }
        val key = options["k"]?.let { parseKey(ctx, it) ?: return ExecContext.EXIT_USAGE }

        val numeric = 'n' in flags || 'h' in flags
        val human = 'h' in flags
        val fold = 'f' in flags
        val ignoreBlanks = 'b' in flags
        val reverse = 'r' in flags
        val unique = 'u' in flags

        val rows = ArrayList<Row>()
        var errored = false
        if (operands.isEmpty()) {
            if (!collect(ctx, ctx.stdin, key, sep, ignoreBlanks, fold, human, rows)) {
                return ExecContext.EXIT_INTERRUPTED
            }
        } else {
            for (op in operands) {
                if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
                val input = Cmds.openInput(ctx, op) ?: run { errored = true; continue }
                val ok = try {
                    collect(ctx, input, key, sep, ignoreBlanks, fold, human, rows)
                } catch (e: IOException) {
                    Errno.report(ctx, "sort", op, e)
                    errored = true
                    true
                } finally {
                    if (input !== ctx.stdin) input.close()
                }
                if (!ok) return ExecContext.EXIT_INTERRUPTED
            }
        }

        rows.sortWith { a, b ->
            var c = if (numeric) compareNumeric(a, b) else 0
            if (c == 0) c = compareBytes(a.key, b.key)
            if (c == 0) c = compareBytes(a.full, b.full)
            if (reverse) -c else c
        }

        val sb = StringBuilder()
        if (unique) {
            val seen = HashSet<String>()
            for (row in rows) {
                if (seen.add(row.fold)) sb.append(row.text).append('\n')
            }
        } else {
            for (row in rows) sb.append(row.text).append('\n')
        }
        ctx.out(sb.toString())
        return if (errored) ExecContext.EXIT_GENERAL_ERROR else ExecContext.EXIT_OK
    }

    private class Row(
        val text: String,
        /** The key with leading blanks removed and case folded when the flags say so. */
        val key: ByteArray,
        /** The whole line, folded too: the last-resort comparison and what -u compares. */
        val full: ByteArray,
        val fold: String,
        val number: Double,
        val hasNumber: Boolean,
    )

    /**
     * A line that starts with a number sorts before one that does not; two unparsable values
     * compare equal so the last-resort comparison decides. `-h` scales K/M/G/T/P suffixes.
     */
    private fun compareNumeric(a: Row, b: Row): Int {
        if (!a.hasNumber && !b.hasNumber) return 0
        if (a.hasNumber && !b.hasNumber) return -1
        if (!a.hasNumber && b.hasNumber) return 1
        if (a.number.isNaN() || b.number.isNaN()) return 0
        return a.number.compareTo(b.number)
    }

    /** Unsigned byte order, which is what LC_ALL=C means. */
    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    private fun collect(
        ctx: ExecContext,
        input: InputStream,
        key: KeySpec?,
        sep: Char?,
        ignoreBlanks: Boolean,
        fold: Boolean,
        human: Boolean,
        out: MutableList<Row>,
    ): Boolean {
        val reader = Cmds.reader(input)
        while (true) {
            if (ctx.cancelled.get()) return false
            val line = reader.readLine() ?: return true
            val keyText = keyText(line, key, sep, ignoreBlanks)
            val foldedKey = if (fold) keyText.lowercase(Locale.ROOT) else keyText
            val foldedLine = if (fold) line.lowercase(Locale.ROOT) else line
            val number = parseNumber(foldedKey, human)
            out += Row(
                text = line,
                key = foldedKey.toByteArray(Charsets.UTF_8),
                full = foldedLine.toByteArray(Charsets.UTF_8),
                fold = foldedLine,
                number = number.first,
                hasNumber = number.second,
            )
        }
    }

    /** The key text for one line, or the whole line when no `-k` was given. */
    private fun keyText(line: String, key: KeySpec?, sep: Char?, ignoreBlanks: Boolean): String {
        if (key == null) {
            return if (ignoreBlanks) line.dropWhile { it == ' ' || it == '\t' } else line
        }
        val ranges = fieldRanges(line, sep)
        if (ranges.isEmpty()) return ""
        var start = offset(ranges, line.length, key.startField, key.startChar, false)
            .coerceIn(0, line.length)
        if (sep == null && key.startField == 1 && key.startChar <= 1) {
            start = start.coerceAtLeast(ranges[0])
        }
        val end = (if (key.endField < 0) {
            line.length
        } else {
            offset(ranges, line.length, key.endField, key.endChar, true)
        }).coerceIn(start, line.length)
        return line.substring(start, end)
    }

    /** Flat [start, end) pairs, one per field: exact with -t, non-blank runs without it. */
    private fun fieldRanges(line: String, sep: Char?): IntArray {
        // A line of nothing but separators has line.length + 1 fields, two indices each.
        val out = IntArray((line.length + 1) * 2)
        var n = 0
        if (sep != null) {
            var start = 0
            for (i in 0..line.length) {
                if (i == line.length || line[i] == sep) {
                    out[n++] = start
                    out[n++] = i
                    start = i + 1
                }
            }
        } else {
            var i = 0
            while (i < line.length) {
                while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
                val start = i
                while (i < line.length && line[i] != ' ' && line[i] != '\t') i++
                if (i > start) {
                    out[n++] = start
                    out[n++] = i
                }
            }
        }
        return if (n == 0) IntArray(0) else out.copyOf(n)
    }

    private fun offset(ranges: IntArray, length: Int, field: Int, charOff: Int, end: Boolean): Int {
        if (field <= 1) {
            val start = if (ranges.isEmpty()) 0 else ranges[0]
            return if (end && charOff <= 0) {
                if (ranges.isEmpty()) length else ranges[1]
            } else {
                start + charOff - 1
            }
        }
        val idx = (field - 1) * 2
        if (idx >= ranges.size) return length
        return if (end && charOff <= 0) ranges[idx + 1] else ranges[idx] + charOff - 1
    }

    private class KeySpec(
        val startField: Int,
        val startChar: Int,
        val endField: Int,
        val endChar: Int,
    )

    private fun parseKey(ctx: ExecContext, spec: String): KeySpec? {
        val comma = spec.indexOf(',')
        val startText = if (comma < 0) spec else spec.substring(0, comma)
        val endText = if (comma < 0) null else spec.substring(comma + 1)
        val start = parseField(startText)
        if (start == null) {
            ctx.errLine("sort: invalid key '$spec'")
            printUsage(ctx.stderr, usageLine(ctx))
            return null
        }
        if (endText == null) return KeySpec(start.first, start.second, -1, -1)
        val end = parseField(endText)
        if (end == null) {
            ctx.errLine("sort: invalid key '$spec'")
            printUsage(ctx.stderr, usageLine(ctx))
            return null
        }
        return KeySpec(start.first, start.second, end.first, end.second)
    }

    /** `N` or `N.M`, both 1-based; a field of 0 or a char of 0 means "from the very start". */
    private fun parseField(text: String): Pair<Int, Int>? {
        if (text.isEmpty()) return null
        val dot = text.indexOf('.')
        val fieldPart = if (dot < 0) text else text.substring(0, dot)
        val charPart = if (dot < 0) "1" else text.substring(dot + 1)
        val field = fieldPart.toIntOrNull() ?: return null
        val char = charPart.toIntOrNull() ?: return null
        if (field < 0 || char < 0) return null
        return field to char
    }

    private fun parseNumber(s: String, human: Boolean): Pair<Double, Boolean> {
        var i = 0
        if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
        val start = i
        while (i < s.length && s[i] in '0'..'9') i++
        if (i == start) return 0.0 to false
        var end = i
        if (i < s.length && s[i] == '.') {
            var j = i + 1
            val fracStart = j
            while (j < s.length && s[j] in '0'..'9') j++
            if (j > fracStart) end = j
        }
        var scale = 1.0
        if (human) {
            var j = end
            while (j < s.length && s[j] == ' ') j++
            if (j < s.length) {
                scale = when (Character.toUpperCase(s[j])) {
                    'K' -> 1024.0
                    'M' -> 1024.0 * 1024
                    'G' -> 1024.0 * 1024 * 1024
                    'T' -> 1024.0 * 1024 * 1024 * 1024
                    'P' -> 1024.0 * 1024 * 1024 * 1024 * 1024
                    else -> 1.0
                }
            }
        }
        val v = s.substring(0, end).toDoubleOrNull() ?: return 0.0 to false
        return v * scale to true
    }
}
