package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.exec.printUsage
import java.io.IOException

@CommandSpec(
    name = "tr",
    synopsis = "[-dsc] SET1 [SET2]",
    group = "text",
    notes = "SET takes a-z ranges, [:alpha:] [:digit:] [:alnum:] [:space:] [:blank:] [:punct:] " +
        "[:upper:] [:lower:] [:xdigit:], and \\a \\b \\f \\n \\r \\t \\v \\\\ \\octal; " +
        "a SET2 shorter than SET1 is padded with its last character",
)
object Tr : FileCommand() {

    override val flagSpec = "dsc"

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        if (operands.isEmpty()) {
            ctx.errLine("tr: missing operand")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }
        if (operands.size > 2) {
            ctx.errLine("tr: extra operand '${operands[2]}'")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }
        val delete = 'd' in flags
        val complement = 'c' in flags
        if (operands.size < 2 && !delete && !complement) {
            ctx.errLine("tr: missing operand after SET1")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }
        val first = expandSet(ctx, operands[0]) ?: return ExecContext.EXIT_GENERAL_ERROR
        val second = if (operands.size > 1) {
            expandSet(ctx, operands[1]) ?: return ExecContext.EXIT_GENERAL_ERROR
        } else {
            IntArray(0)
        }
        val table = IntArray(256) { it }
        val source = if (complement) complementOf(first) else first
        val target = if (complement) complementOf(second) else second
        if (delete || target.isEmpty()) {
            for (c in source) table[c] = -1
        } else {
            for (i in source.indices) {
                // A short SET2 is padded with its last character, which is how `tr a-z a-` works.
                table[source[i]] = target[minOf(i, target.size - 1)]
            }
        }

        val buf = ByteArray(32 * 1024)
        val out = ByteArray(buf.size)
        while (true) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            val n = try {
                ctx.stdin.read(buf)
            } catch (e: IOException) {
                ctx.errLine("tr: ${e.message}")
                return ExecContext.EXIT_GENERAL_ERROR
            }
            if (n <= 0) break
            var m = 0
            for (i in 0 until n) {
                val mapped = table[buf[i].toInt() and 0xFF]
                if (mapped >= 0) out[m++] = mapped.toByte()
            }
            if (m > 0) ctx.stdout.write(out, 0, m)
        }
        return ExecContext.EXIT_OK
    }

    private fun complementOf(set: IntArray): IntArray {
        val inSet = BooleanArray(256)
        for (c in set) inSet[c] = true
        val out = IntArray(256)
        var n = 0
        for (c in 0..255) if (!inSet[c]) out[n++] = c
        return out.copyOf(n)
    }

    /** One element: a character, an escape, or a whole character class. */
    private class Element(val codes: IntArray, val next: Int)

    private fun expandSet(ctx: ExecContext, spec: String): IntArray? {
        val out = ArrayList<Int>()
        var i = 0
        while (i < spec.length) {
            val e = nextElement(ctx, spec, i) ?: return null
            val dash = i + 1
            if (e.codes.size == 1 && dash < spec.length && spec[dash] == '-' && dash + 1 < spec.length) {
                val end = nextElement(ctx, spec, dash + 1)
                if (end != null && end.codes.size == 1) {
                    val from = e.codes[0]
                    val to = end.codes[0]
                    if (to - from > 255 || from - to > 255) {
                        ctx.errLine("tr: the range $from-$to is too large for a byte set")
                        return null
                    }
                    for (v in from..to) out += v and 0xFF
                    i = end.next
                    continue
                }
            }
            for (v in e.codes) out += v and 0xFF
            i = e.next
        }
        return out.toIntArray()
    }

    private fun nextElement(ctx: ExecContext, spec: String, at: Int): Element? {
        if (at >= spec.length) return null
        if (spec[at] == '[' && at + 1 < spec.length && spec[at + 1] == ':') {
            val end = spec.indexOf(":]", at + 2)
            if (end < 0) return null
            val values = expandClass(spec.substring(at + 2, end))
            if (values == null) {
                ctx.errLine("tr: unknown character class '${spec.substring(at + 2, end)}'")
                return null
            }
            return Element(values, end + 2)
        }
        if (spec[at] == '\\') {
            if (at + 1 >= spec.length) return Element(intArrayOf('\\'.code), at + 1)
            return when (val c = spec[at + 1]) {
                'a' -> Element(intArrayOf(7), at + 2)
                'b' -> Element(intArrayOf(8), at + 2)
                'f' -> Element(intArrayOf(12), at + 2)
                'n' -> Element(intArrayOf(10), at + 2)
                'r' -> Element(intArrayOf(13), at + 2)
                't' -> Element(intArrayOf(9), at + 2)
                'v' -> Element(intArrayOf(11), at + 2)
                '\\' -> Element(intArrayOf('\\'.code), at + 2)
                in '0'..'7' -> {
                    var value = 0
                    var j = at + 1
                    var digits = 0
                    while (j < spec.length && digits < 3 && spec[j] in '0'..'7') {
                        value = value * 8 + (spec[j] - '0')
                        j++
                        digits++
                    }
                    Element(intArrayOf(value and 0xFF), j)
                }
                else -> Element(intArrayOf(c.code), at + 2)
            }
        }
        return Element(intArrayOf(spec[at].code), at + 1)
    }

    private fun expandClass(name: String): IntArray? {
        val out = ArrayList<Int>()
        when (name) {
            "alpha" -> { out += span('a', 'z'); out += span('A', 'Z') }
            "digit" -> out += span('0', '9')
            "alnum" -> {
                out += span('0', '9')
                out += span('a', 'z')
                out += span('A', 'Z')
            }
            "upper" -> out += span('A', 'Z')
            "lower" -> out += span('a', 'z')
            "space" -> out += listOf(9, 10, 11, 12, 13, 32)
            "blank" -> out += listOf(9, 32)
            "xdigit" -> {
                out += span('0', '9')
                out += span('a', 'f')
                out += span('A', 'F')
            }
            "punct" -> out += listOf(
                33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47,
                58, 59, 60, 61, 62, 63, 64, 91, 92, 93, 94, 95, 96, 123, 124, 125, 126,
            )
            else -> return null
        }
        return out.toIntArray()
    }

    private fun span(from: Char, to: Char): List<Int> = (from.code..to.code).toList()
}
