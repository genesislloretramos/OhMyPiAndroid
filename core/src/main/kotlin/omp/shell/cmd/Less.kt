package omp.shell.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

@CommandSpec(
    name = "less",
    synopsis = "file ...",
    group = "files",
    notes = "paginates on the alternate screen: SPACE/b, j/k, g/G, / then a pattern, n, h, q",
)
object Less : Command {

    private val esc = 27.toChar()

    private fun csi(body: String) = "$esc[$body"

    private val enterAlt = csi("?1049h")
    private val leaveAlt = csi("?1049l")
    private val hideCursor = csi("?25l")
    private val showCursor = csi("?25h")
    private val toTopLeft = csi("1G")
    private val eraseLine = csi("2K")

    /** Not a byte value, so it cannot collide with a key. */
    private const val KEY_IGNORED = -2
    private const val KEY_UP = -3
    private const val KEY_DOWN = -4

    private class Data(val status: Int, val lines: List<String>)

    private class Jump(val top: Int, val message: String)

    override fun run(ctx: ExecContext): Int {
        // Nothing is going to press a key, so with a pipe or a redirect this is `cat`.
        if (!ctx.isTty) return drain(ctx)
        val data = readAll(ctx)
        if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
        return paginate(ctx, data)
    }

    private fun drain(ctx: ExecContext): Int {
        if (ctx.args.isEmpty()) {
            copy(ctx, ctx.stdin, null)
            return ExecContext.EXIT_OK
        }
        var status = ExecContext.EXIT_OK
        for (op in ctx.args) {
            val source = Cmds.openInput(ctx, op)
            if (source == null) {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val rc = copy(ctx, source, op)
            if (rc != ExecContext.EXIT_OK) status = rc
            if (source !== ctx.stdin) closeQuietly(source)
        }
        return status
    }

    private fun copy(ctx: ExecContext, source: InputStream, op: String?): Int {
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = try {
                source.read(buf)
            } catch (e: IOException) {
                return if (op == null) ctx.fail("${ctx.name}: ${Errno.messageFor(e)}") else Errno.report(ctx, ctx.name, op, e)
            }
            if (n < 0) return ExecContext.EXIT_OK
            ctx.stdout.write(buf, 0, n)
        }
    }

    private fun readAll(ctx: ExecContext): Data {
        val lines = ArrayList<String>()
        var status = ExecContext.EXIT_OK
        if (ctx.args.isEmpty()) {
            readStream(ctx, ctx.stdin, lines)
            return Data(status, lines)
        }
        for (op in ctx.args) {
            val source = Cmds.openInput(ctx, op)
            if (source == null) {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            readStream(ctx, source, lines)
            if (source !== ctx.stdin) closeQuietly(source)
        }
        return Data(status, lines)
    }

    private fun readStream(ctx: ExecContext, source: InputStream, out: MutableList<String>) {
        val reader = InputStreamReader(source, Charsets.UTF_8)
        val line = StringBuilder()
        while (!ctx.cancelled.get()) {
            val c = try {
                reader.read()
            } catch (e: IOException) {
                break
            }
            if (c < 0) break
            if (c == 0x0A) {
                out.add(line.toString())
                line.setLength(0)
            } else {
                line.append(c.toChar())
            }
        }
        if (line.isNotEmpty()) out.add(line.toString())
    }

    private fun paginate(ctx: ExecContext, data: Data): Int {
        val screen = ctx.session.screen
        val cols = maxOf(20, screen.cols)
        val rows = maxOf(4, screen.rows)
        val pageRows = rows - 1
        val wrapped = wrap(data.lines, cols)
        val name = ctx.args.firstOrNull() ?: "-"
        var top = 0
        var search: String? = null
        var message = ""
        var interrupted = false

        ctx.out(enterAlt)
        // The text area is the scroll region while the pager owns the screen, and the status line
        // below it must not scroll away.
        screen.setScrollRegion(1, rows - 1)
        try {
            while (true) {
                render(ctx, wrapped, top, pageRows, rows, status(wrapped, top, pageRows, cols, name, message))
                val key = readKey(ctx)
                if (key == null) {
                    interrupted = ctx.cancelled.get()
                    break
                }
                var keepMessage = false
                when (key) {
                    'q'.code, 'Q'.code -> break
                    0x03 -> {
                        interrupted = true
                        break
                    }
                    ' '.code, 'f'.code, 0x06 -> top = minOf(maxTop(wrapped, pageRows), top + pageRows)
                    'b'.code, 0x10 -> top = maxOf(0, top - pageRows)
                    'j'.code, 0x0A, 0x0E, KEY_DOWN -> top = minOf(maxTop(wrapped, pageRows), top + 1)
                    'k'.code, KEY_UP -> top = maxOf(0, top - 1)
                    'g'.code -> top = 0
                    'G'.code -> top = maxTop(wrapped, pageRows)
                    '/'.code -> {
                        val query = prompt(ctx, rows)
                        if (query == null) {
                            interrupted = true
                            break
                        }
                        if (query.isNotEmpty()) {
                            search = query
                            val jump = jumpTo(wrapped, top, query, pageRows)
                            top = jump.top
                            message = jump.message
                            keepMessage = true
                        }
                    }
                    'n'.code -> {
                        val query = search
                        if (query != null) {
                            val jump = jumpTo(wrapped, top, query, pageRows)
                            top = jump.top
                            message = jump.message
                            keepMessage = true
                        }
                    }
                    'h'.code, 'H'.code -> {
                        help(ctx, rows, cols)
                        keepMessage = false
                    }
                }
                if (!keepMessage) message = ""
            }
        } finally {
            ctx.out(showCursor)
            ctx.out(leaveAlt)
            screen.setScrollRegion(1, rows)
            ctx.flush()
        }
        return if (interrupted) ExecContext.EXIT_INTERRUPTED else data.status
    }

    private fun maxTop(wrapped: List<String>, pageRows: Int): Int = maxOf(0, wrapped.size - pageRows)

    private fun render(ctx: ExecContext, wrapped: List<String>, top: Int, pageRows: Int, rows: Int, status: String) {
        val sb = StringBuilder(1024)
        sb.append(hideCursor)
        for (r in 0 until pageRows) {
            sb.append(csi("${r + 1};1H")).append(eraseLine)
            val index = top + r
            if (index < wrapped.size) sb.append(wrapped[index])
        }
        sb.append(csi("${rows};1H")).append(eraseLine).append(toTopLeft).append(status)
        sb.append(showCursor)
        ctx.out(sb.toString())
        ctx.flush()
    }

    private fun status(wrapped: List<String>, top: Int, pageRows: Int, cols: Int, name: String, message: String): String {
        if (message.isNotEmpty()) return message.take(cols)
        val total = wrapped.size
        if (total == 0) return "$name (no lines)  [q:quit h:help]".take(cols)
        val percent = if (total <= pageRows) 100 else (top + pageRows) * 100 / total
        val end = if (top >= maxTop(wrapped, pageRows)) "  (END)" else ""
        return "$name line ${top + 1}/$total ($percent%)$end  [q:quit h:help /:search]".take(cols)
    }

    private fun jumpTo(wrapped: List<String>, top: Int, query: String, pageRows: Int): Jump {
        for (i in top until wrapped.size) {
            if (wrapped[i].contains(query)) return Jump(minOf(i, maxTop(wrapped, pageRows)), "")
        }
        for (i in 0 until minOf(top, wrapped.size)) {
            if (wrapped[i].contains(query)) return Jump(i, "")
        }
        return Jump(top, "Pattern not found")
    }

    private fun prompt(ctx: ExecContext, rows: Int): String? {
        val query = StringBuilder()
        while (true) {
            val c = ctx.stdin.read()
            if (c < 0 || c == 0x03) return null
            if (c == 0x0A || c == 0x0D) {
                ctx.out(csi("${rows};1H") + eraseLine + toTopLeft + showCursor)
                ctx.flush()
                return query.toString()
            }
            if (c == 0x08 || c == 0x7F) {
                if (query.isNotEmpty()) query.setLength(query.length - 1)
            } else {
                query.append(c.toChar())
            }
            ctx.out(csi("${rows};1H") + eraseLine + toTopLeft + showCursor + "/" + query)
            ctx.flush()
        }
    }

    private fun help(ctx: ExecContext, rows: Int, cols: Int) {
        val lines = listOf(
            "SPACE, f, Ctrl-F   page forward",
            "b, Ctrl-B          page backward",
            "j, Down, Enter     one line forward",
            "k, Up              one line backward",
            "g / G              first / last line",
            "/pattern, n        search forward, next match",
            "h                  this help",
            "q                  quit",
        )
        for (i in lines.indices) {
            if (i >= rows - 1) break
            ctx.out(csi("${i + 1};1H") + eraseLine + lines[i].take(cols))
        }
        ctx.out(csi("${rows};1H") + eraseLine + toTopLeft + "press any key to continue" + showCursor)
        ctx.flush()
        readKey(ctx)
    }

    private fun readKey(ctx: ExecContext): Int? {
        if (ctx.cancelled.get()) return null
        val first = ctx.stdin.read()
        if (first < 0) return null
        if (first != 27) return first
        val second = ctx.stdin.read()
        if (second < 0) return KEY_IGNORED
        if (second == '['.code || second == 'O'.code) {
            val third = ctx.stdin.read()
            if (third < 0) return KEY_IGNORED
            if (third == 'A'.code) return KEY_UP
            if (third == 'B'.code) return KEY_DOWN
            // Everything else is a sequence this pager does not bind; swallow it whole.
            while (true) {
                val c = ctx.stdin.read()
                if (c < 0 || c in 0x40..0x7E) break
            }
        }
        return KEY_IGNORED
    }

    private fun wrap(lines: List<String>, cols: Int): List<String> {
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            if (line.isEmpty()) {
                out.add("")
                continue
            }
            var i = 0
            while (i < line.length) {
                var end = minOf(line.length, i + cols)
                if (end < line.length && Character.isHighSurrogate(line[end - 1])) end--
                out.add(line.substring(i, end))
                i = end
            }
        }
        return out
    }

    private fun closeQuietly(stream: InputStream) {
        try {
            stream.close()
        } catch (e: IOException) {
        }
    }
}
