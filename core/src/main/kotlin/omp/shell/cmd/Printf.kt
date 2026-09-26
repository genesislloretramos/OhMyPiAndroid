package omp.shell.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.printUsage
import java.util.Locale

@CommandSpec(
    name = "printf",
    synopsis = "format [arguments ...]",
    group = "text",
    notes = "%s %d %i %x %X %o %c %f %e %g %%; the format repeats while arguments remain",
)
object Printf : Command {

    override fun run(ctx: ExecContext): Int {
        val args = ctx.args
        if (args.isEmpty()) {
            printUsage(ctx.stderr, "printf format [arguments ...]")
            return ExecContext.EXIT_USAGE
        }
        val format = args[0]
        val operands = args.subList(1, args.size)
        val pass = Pass(ctx)
        val sb = StringBuilder()
        var index = 0
        while (true) {
            val before = index
            index = pass.render(sb, format, operands, index)
            if (index >= operands.size) break
            // A format of only literals consumes nothing, so repeating it would never end.
            if (index == before) break
        }
        ctx.out(sb.toString())
        return pass.status
    }

    /** One pass of the format over the argument list; a fresh instance per invocation. */
    private class Pass(private val ctx: ExecContext) {

        var status = ExecContext.EXIT_OK

        /** Renders one pass of [format] and returns the argument index it stopped at. */
        fun render(sb: StringBuilder, format: String, args: List<String>, start: Int): Int {
            var argIndex = start
            var i = 0
            while (i < format.length) {
                val c = format[i]
                if (c == '\\') {
                    i = escape(sb, format, i)
                    continue
                }
                if (c != '%') {
                    sb.append(c)
                    i++
                    continue
                }
                var k = i + 1
                var leftAlign = false
                var zeroPad = false
                while (k < format.length && format[k] in "-+ #0") {
                    if (format[k] == '-') leftAlign = true
                    if (format[k] == '0') zeroPad = true
                    k++
                }
                var width = -1
                while (k < format.length && format[k] in '0'..'9') {
                    width = (if (width < 0) 0 else width) * 10 + (format[k] - '0')
                    k++
                }
                var precision = -1
                if (k < format.length && format[k] == '.') {
                    k++
                    precision = 0
                    while (k < format.length && format[k] in '0'..'9') {
                        precision = precision * 10 + (format[k] - '0')
                        k++
                    }
                }
                if (k >= format.length) {
                    sb.append(format, i, format.length)
                    return argIndex
                }
                val conv = format[k]
                k++
                if (conv == '%') {
                    sb.append('%')
                    i = k
                    continue
                }
                val arg: String? = if (argIndex < args.size) args[argIndex++] else null
                val text = convert(conv, arg, precision)
                if (text != null) emit(sb, text, leftAlign, zeroPad && conv != 's', width)
                i = k
            }
            return argIndex
        }

        /** Appends the escaped character at [at] and returns the new format index. */
        private fun escape(sb: StringBuilder, format: String, at: Int): Int {
            if (at + 1 >= format.length) {
                sb.append('\\')
                return at + 1
            }
            when (val n = format[at + 1]) {
                'n' -> { sb.append('\n'); return at + 2 }
                't' -> { sb.append('\t'); return at + 2 }
                'r' -> { sb.append('\r'); return at + 2 }
                '\\' -> { sb.append('\\'); return at + 2 }
                '0' -> {
                    var value = 0
                    var j = at + 2
                    var digits = 0
                    while (j < format.length && digits < 3 && format[j] in '0'..'7') {
                        value = value * 8 + (format[j] - '0')
                        j++
                        digits++
                    }
                    sb.append(value.toChar())
                    return j
                }
                else -> { sb.append('\\').append(n); return at + 2 }
            }
        }

        /** Renders one converted argument, or null after reporting an invalid specifier. */
        private fun convert(conv: Char, arg: String?, precision: Int): String? {
            val s = arg ?: ""
            return when (conv) {
                's' -> if (precision >= 0 && s.length > precision) s.substring(0, precision) else s
                'd', 'i' -> {
                    val v = leadingLong(s) ?: 0L
                    if (precision == 0 && v == 0L) "" else v.toString()
                }
                'x' -> java.lang.Long.toUnsignedString(leadingLong(s) ?: 0L, 16)
                'X' -> java.lang.Long.toUnsignedString(leadingLong(s) ?: 0L, 16).uppercase(Locale.US)
                'o' -> java.lang.Long.toUnsignedString(leadingLong(s) ?: 0L, 8)
                'c' -> if (s.isEmpty()) "" else String(Character.toChars(s.codePointAt(0)))
                'f', 'F', 'e', 'E', 'g', 'G' -> {
                    val v = leadingDouble(s) ?: 0.0
                    if (v.isNaN()) return if (conv.isUpperCase()) "NAN" else "nan"
                    if (v.isInfinite()) return if (conv.isUpperCase()) "INF" else "inf"
                    val p = if (precision < 0) 6 else precision
                    String.format(Locale.US, "%.${p}%c", v, conv.lowercaseChar())
                }
                else -> {
                    ctx.errLine("printf: '%$conv': invalid format character")
                    status = ExecContext.EXIT_GENERAL_ERROR
                    null
                }
            }
        }

        private fun emit(
            sb: StringBuilder,
            value: String,
            leftAlign: Boolean,
            zeroPad: Boolean,
            width: Int,
        ) {
            val pad = width - value.length
            if (pad <= 0) {
                sb.append(value)
                return
            }
            when {
                leftAlign -> { sb.append(value); sb.append(" ".repeat(pad)) }
                zeroPad && value.startsWith("-") ->
                    sb.append('-').append("0".repeat(pad)).append(value, 1, value.length)
                zeroPad -> sb.append("0".repeat(pad)).append(value)
                else -> sb.append(" ".repeat(pad)).append(value)
            }
        }

        private fun leadingLong(s: String): Long? {
            var i = 0
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            val start = i
            while (i < s.length && s[i] in '0'..'9') i++
            if (i == start) return null
            return s.substring(0, i).toLongOrNull()
        }

        private fun leadingDouble(s: String): Double? {
            var i = 0
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            val start = i
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '.')) i++
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                var j = i + 1
                if (j < s.length && (s[j] == '+' || s[j] == '-')) j++
                val expStart = j
                while (j < s.length && s[j] in '0'..'9') j++
                if (j > expStart) i = j
            }
            if (i == start) return null
            return s.substring(0, i).toDoubleOrNull()
        }
    }
}
