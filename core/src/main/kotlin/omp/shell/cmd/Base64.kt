package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

@CommandSpec(
    name = "base64",
    synopsis = "[-d] [file]",
    group = "text",
    notes = "output wraps at 76 columns; -d ignores whitespace and rejects anything else as invalid input",
)
object Base64 : FileCommand() {

    override val flagSpec = "d"

    /** 57 bytes encode to exactly 76 characters, which is the wrap width of the real tool. */
    private const val CHUNK = 57

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        if (operands.size > 1) {
            ctx.errLine("base64: extra operand '${operands[1]}'")
            return ExecContext.EXIT_USAGE
        }
        val input = if (operands.isEmpty()) ctx.stdin else Cmds.openInput(ctx, operands[0]) ?: return ExecContext.EXIT_GENERAL_ERROR
        val status = try {
            if (flags.indexOf('d') >= 0) decode(ctx, input) else encode(ctx, input)
        } catch (e: IOException) {
            ctx.errLine("base64: ${e.message}")
            ExecContext.EXIT_GENERAL_ERROR
        } finally {
            if (input !== ctx.stdin) input.close()
        }
        if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
        return status
    }

    private fun encode(ctx: ExecContext, input: InputStream): Int {
        val encoder = java.util.Base64.getEncoder()
        val buf = ByteArray(CHUNK)
        while (true) {
            var filled = 0
            while (filled < CHUNK) {
                if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
                val n = input.read(buf, filled, CHUNK - filled)
                if (n <= 0) break
                filled += n
            }
            if (filled == 0) return ExecContext.EXIT_OK
            // Base64.Encoder has no offset/length overload, so the short tail is copied out.
            val text = encoder.encodeToString(if (filled == CHUNK) buf else buf.copyOf(filled))
            ctx.out(text)
            ctx.out("\n")
            if (filled < CHUNK) return ExecContext.EXIT_OK
        }
    }

    private fun decode(ctx: ExecContext, input: InputStream): Int {
        val out = ByteArrayOutputStream(8 * 1024)
        val buf = ByteArray(32 * 1024)
        while (true) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        val sb = StringBuilder(out.size())
        for (b in out.toByteArray()) {
            val c = b.toInt() and 0xFF
            if (c == 0x20 || c == 0x09 || c == 0x0A || c == 0x0D || c == 0x0B || c == 0x0C) continue
            sb.append(c.toChar())
        }
        if (!isValid(sb)) return ctx.fail("base64: invalid input")
        val bytes = try {
            // The MIME decoder tolerates the missing padding the real tool also tolerates.
            java.util.Base64.getMimeDecoder().decode(sb.toString())
        } catch (e: IllegalArgumentException) {
            return ctx.fail("base64: invalid input")
        }
        ctx.out(bytes)
        return ExecContext.EXIT_OK
    }

    private fun isValid(s: StringBuilder): Boolean {
        // Padding is only ever the last one or two characters; a '=' anywhere else is not base64.
        var end = s.length
        while (end > 0 && s[end - 1] == '=') end--
        if (s.length - end > 2) return false
        for (i in 0 until end) {
            val c = s[i]
            val ok = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '+' || c == '/'
            if (!ok) return false
        }
        // A group of one leftover character can never be a base64 encoding.
        return s.length % 4 != 1
    }
}
