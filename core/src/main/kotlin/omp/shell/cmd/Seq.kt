package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.exec.printUsage
import java.math.BigDecimal
import java.math.RoundingMode

@CommandSpec(
    name = "seq",
    synopsis = "[-w] [-s SEP] [first [incr]] last",
    group = "text",
    notes = "the increment defaults to 1 and turns negative when first is above last; -w zero-pads",
)
object Seq : FileCommand() {

    override val flagSpec = "w"
    override val valueSpec = "s:"

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        if (operands.isEmpty()) {
            ctx.errLine("seq: missing operand")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }
        if (operands.size > 3) {
            ctx.errLine("seq: extra operand '${operands[3]}'")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }
        val values = ArrayList<BigDecimal>(3)
        var decimals = 0
        for (text in operands) {
            val value = parse(text)
            if (value == null) {
                ctx.errLine("seq: invalid number '$text'")
                return ExecContext.EXIT_GENERAL_ERROR
            }
            decimals = maxOf(decimals, value.scale())
            values += value
        }
        val first: BigDecimal
        val increment: BigDecimal
        val last: BigDecimal
        when (values.size) {
            1 -> {
                first = BigDecimal.ONE
                increment = BigDecimal.ONE
                last = values[0]
            }
            2 -> {
                first = values[0]
                increment = if (first.compareTo(values[1]) > 0) BigDecimal.ONE.negate() else BigDecimal.ONE
                last = values[1]
            }
            else -> {
                first = values[0]
                increment = values[1]
                last = values[2]
            }
        }
        if (increment.signum() == 0) {
            ctx.errLine("seq: increment must not be zero")
            return ExecContext.EXIT_GENERAL_ERROR
        }
        if (increment.signum() > 0 && first.compareTo(last) > 0) return ExecContext.EXIT_OK
        if (increment.signum() < 0 && first.compareTo(last) < 0) return ExecContext.EXIT_OK

        val sep = options["s"] ?: "\n"
        val pad = 'w' in flags
        val width = if (pad) {
            maxOf(
                render(first, decimals).length,
                render(last, decimals).length,
            )
        } else {
            0
        }

        val ascending = increment.signum() > 0
        val sb = StringBuilder()
        var value = first
        while (true) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            val c = value.compareTo(last)
            if (ascending && c > 0) break
            if (!ascending && c < 0) break
            sb.append(pad(render(value, decimals), width))
            sb.append(sep)
            value += increment
            if (sb.length > 32 * 1024) {
                ctx.out(sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) ctx.out(sb.toString())
        return ExecContext.EXIT_OK
    }

    /** Only plain decimals; `1e5` is not a number `seq` is expected to accept. */
    private fun parse(text: String): BigDecimal? {
        if (text.isEmpty()) return null
        var i = 0
        if (text[i] == '+' || text[i] == '-') i++
        val start = i
        while (i < text.length && text[i] in '0'..'9') i++
        var sawDigit = i > start
        if (i < text.length && text[i] == '.') {
            i++
            val frac = i
            while (i < text.length && text[i] in '0'..'9') i++
            if (i > frac) sawDigit = true
        }
        if (!sawDigit || i != text.length) return null
        return try {
            BigDecimal(text)
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun render(value: BigDecimal, decimals: Int): String =
        value.setScale(decimals, RoundingMode.HALF_UP).toPlainString()

    private fun pad(text: String, width: Int): String {
        if (text.length >= width) return text
        val negative = text.startsWith("-")
        val body = if (negative) text.substring(1) else text
        val dot = body.indexOf('.')
        val whole = if (dot < 0) body else body.substring(0, dot)
        val rest = if (dot < 0) "" else body.substring(dot)
        val padded = whole.padStart(width - if (negative) 1 else 0, '0')
        return (if (negative) "-" else "") + padded + rest
    }
}
