package omp.term

/**
 * Byte-driven VT parser. Bytes are decoded as UTF-8 incrementally, so a
 * multi-byte character split across two [Screen.write] calls is reassembled
 * rather than corrupted; the state machine itself works on codepoints, which is
 * safe because every control, parameter and intermediate byte is ASCII.
 *
 * Every sequence is bounded: at most [MAX_PARAMS] parameters, [MAX_INTERMEDIATES]
 * intermediate bytes, and [SEQ_LIMIT] codepoints. Exceeding any bound emits
 * `ESC [ 0 m` and then swallows bytes up to the next final byte, so a program
 * emitting something unrecognised cannot wedge the parser or leak buffers.
 */
class Parser(
    private val screen: Screen,
    private val onResponse: (ByteArray) -> Unit = {},
    private val onClipboard: (String) -> Unit = {},
) {

    private enum class State {
        GROUND, ESC, CSI_ENTRY, CSI_PARAM, CSI_INTERMEDIATE, OSC_STRING, DCS_IGNORE, CHARSET_SHIFT,
    }

    private var state = State.GROUND
    private var budget = SEQ_LIMIT
    private var prefix = ' '
    private val params = IntArray(MAX_PARAMS)
    private var paramCount = 0
    private val intermediates = IntArray(MAX_INTERMEDIATES)
    private var interCount = 0
    private var swallowFinal = false
    private var ignoreEscPending = false
    private val osc = StringBuilder()

    private var u8Need = 0
    private var u8Acc = 0
    private var u8Min = 0

    fun feed(bytes: ByteArray) {
        for (i in bytes.indices) decode(bytes[i].toInt() and 0xFF)
    }

    fun feed(text: String) {
        if (u8Need > 0) {
            u8Need = 0
            step(REPLACEMENT)
        }
        for (i in text.indices) step(text[i].code)
    }

    // ------------------------------------------------------------- decoding

    private fun decode(b: Int) {
        if (u8Need == 0) {
            when {
                b < 0x80 -> step(b)
                b in 0xC2..0xDF -> {
                    u8Need = 1
                    u8Acc = b and 0x1F
                    u8Min = 0x80
                }
                b in 0xE0..0xEF -> {
                    u8Need = 2
                    u8Acc = b and 0x0F
                    u8Min = 0x800
                }
                b in 0xF0..0xF4 -> {
                    u8Need = 3
                    u8Acc = b and 0x07
                    u8Min = 0x10000
                }
                else -> step(REPLACEMENT)
            }
            return
        }
        if (b and 0xC0 != 0x80) {
            // Truncated sequence: emit the replacement, then re-dispatch the
            // offending byte, which may be the start of the next character or
            // an escape introducer.
            u8Need = 0
            step(REPLACEMENT)
            decode(b)
            return
        }
        u8Acc = (u8Acc shl 6) or (b and 0x3F)
        if (--u8Need == 0) {
            val cp = u8Acc
            step(if (cp >= u8Min && cp <= 0x10FFFF && cp !in 0xD800..0xDFFF) cp else REPLACEMENT)
        }
    }

    // -------------------------------------------------------- state machine

    private fun step(c: Int) {
        if (state == State.OSC_STRING) {
            oscString(c)
            return
        }
        if (state == State.DCS_IGNORE) {
            if (ignoreEscPending) {
                ignoreEscPending = false
                if (c == '\\'.code) {
                    state = State.GROUND
                    swallowFinal = false
                }
                return
            }
            if (c == Ansi.ESC) {
                ignoreEscPending = true
                return
            }
            if (swallowFinal && c in 0x40..0x7E) {
                state = State.GROUND
                swallowFinal = false
            }
            return
        }
        if (c == Ansi.ESC) {
            beginEscape()
            return
        }
        if (state == State.GROUND) {
            ground(c)
            return
        }
        if (--budget <= 0) {
            abort()
            return
        }
        when (state) {
            State.ESC -> escape(c)
            State.CSI_ENTRY, State.CSI_PARAM -> csi(c)
            State.CSI_INTERMEDIATE -> csiIntermediate(c)
            State.CHARSET_SHIFT -> if (c >= 0x20) state = State.GROUND
            State.GROUND, State.OSC_STRING, State.DCS_IGNORE -> Unit
        }
    }

    private fun ground(c: Int) {
        when {
            c == Ansi.BEL -> screen.bell()
            c == Ansi.BS -> screen.backspace()
            c == Ansi.HT -> screen.tab()
            c == Ansi.LF || c == 0x0B || c == 0x0C -> {
                screen.lineFeed()
                if (screen.newlineMode()) screen.carriageReturn()
            }
            c == Ansi.CR -> screen.carriageReturn()
            c < 0x20 || c == Ansi.DEL -> Unit
            else -> screen.print(c)
        }
    }

    private fun beginEscape() {
        state = State.ESC
        budget = SEQ_LIMIT
        prefix = ' '
        paramCount = 0
        interCount = 0
        swallowFinal = false
    }

    private fun escape(c: Int) {
        if (c > 0x7F) {
            state = State.GROUND
            return
        }
        when (c.toChar()) {
            '[' -> {
                params.fill(-1)
                paramCount = 0
                state = State.CSI_ENTRY
            }
            ']' -> {
                osc.setLength(0)
                state = State.OSC_STRING
            }
            'P', '^', '_', 'X' -> {
                state = State.DCS_IGNORE
                swallowFinal = false
                ignoreEscPending = false
            }
            '(', ')', '*', '+', '-', '.', '/' -> {
                // Character set designation: consumed, never translated.
                state = State.CHARSET_SHIFT
            }
            '7' -> {
                screen.saveCursor()
                state = State.GROUND
            }
            '8' -> {
                screen.restoreCursor()
                state = State.GROUND
            }
            'D' -> {
                screen.lineFeed()
                state = State.GROUND
            }
            'M' -> {
                screen.reverseIndex()
                state = State.GROUND
            }
            'E' -> {
                screen.lineFeed()
                screen.carriageReturn()
                state = State.GROUND
            }
            'c' -> {
                screen.reset()
                state = State.GROUND
            }
            'Z' -> {
                emit(Ansi.DA_PRIMARY)
                state = State.GROUND
            }
            '\\', '=', '>', 'H' -> state = State.GROUND
            in ' '..'/' -> {
                if (addIntermediate(c)) state = State.CSI_INTERMEDIATE else state = State.GROUND
            }
            else -> state = State.GROUND
        }
    }

    private fun csi(c: Int) {
        if (c > 0x7F) {
            state = State.GROUND
            return
        }
        when (c.toChar()) {
            in '0'..'9' -> {
                if (paramCount == 0) paramCount = 1
                val i = paramCount - 1
                val cur = if (params[i] < 0) 0 else params[i]
                params[i] = if (cur > 6553) 65535 else cur * 10 + (c - '0'.code)
            }
            ';', ':' -> {
                if (paramCount == 0) paramCount = 1
                if (paramCount >= MAX_PARAMS) {
                    abort()
                    return
                }
                params[paramCount] = -1
                paramCount++
            }
            '?', '>', '<', '=' -> prefix = c.toChar()
            in ' '..'/' -> {
                if (addIntermediate(c)) state = State.CSI_INTERMEDIATE else return
            }
            in '@'..'~' -> {
                dispatch(c)
                state = State.GROUND
            }
            else -> abort()
        }
    }

    private fun csiIntermediate(c: Int) {
        if (c > 0x7F) {
            state = State.GROUND
            return
        }
        when (c.toChar()) {
            in ' '..'/' -> if (!addIntermediate(c)) return
            in '@'..'~' -> {
                dispatch(c)
                state = State.GROUND
            }
            else -> abort()
        }
    }

    private fun addIntermediate(c: Int): Boolean {
        if (interCount >= MAX_INTERMEDIATES) {
            abort()
            return false
        }
        intermediates[interCount++] = c
        return true
    }

    private fun abort() {
        screen.sgrReset()
        emit(Ansi.SGR_RESET)
        state = State.DCS_IGNORE
        swallowFinal = true
        ignoreEscPending = false
    }

    // ------------------------------------------------------------- dispatch

    private fun param(index: Int, fallback: Int): Int {
        val v = if (index < paramCount) params[index] else -1
        return if (v < 0) fallback else v
    }

    private fun hasIntermediate(c: Char): Boolean {
        for (i in 0 until interCount) if (intermediates[i] == c.code) return true
        return false
    }

    private fun dispatch(c: Int) {
        when (c.toChar()) {
            'A' -> screen.cursorUp(param(0, 1))
            'B' -> screen.cursorDown(param(0, 1))
            'C' -> screen.cursorForward(param(0, 1))
            'D' -> screen.cursorBack(param(0, 1))
            'E' -> {
                screen.cursorDown(param(0, 1))
                screen.carriageReturn()
            }
            'F' -> {
                screen.cursorUp(param(0, 1))
                screen.carriageReturn()
            }
            'G' -> screen.cursorTo(screen.cursorRow, param(0, 1) - 1)
            'd' -> screen.cursorTo(param(0, 1) - 1, screen.cursorColumn)
            'H', 'f' -> screen.cursorTo(param(0, 1) - 1, param(1, 1) - 1)
            'J' -> screen.eraseInDisplay(param(0, 0))
            'K' -> screen.eraseInLine(param(0, 0))
            'L' -> screen.insertLines(param(0, 1))
            'M' -> screen.deleteLines(param(0, 1))
            'P' -> screen.deleteChars(param(0, 1))
            '@' -> screen.insertChars(param(0, 1))
            'X' -> screen.eraseChars(param(0, 1))
            'S' -> screen.scrollUpLines(param(0, 1))
            'T' -> screen.scrollDownLines(param(0, 1))
            'b' -> screen.repeatLastPrinted(param(0, 1))
            'm' -> screen.applySgr(params, paramCount)
            'r' -> if (prefix == ' ') {
                val bottom = if (paramCount < 2 || param(1, 0) <= 0) screen.rows else param(1, screen.rows)
                screen.setScrollRegion(param(0, 1), bottom)
            }
            's' -> if (prefix != '?') screen.saveCursor()
            'u' -> screen.restoreCursor()
            'h' -> setMode(true)
            'l' -> setMode(false)
            'n' -> deviceStatus()
            'c' -> if (prefix == ' ') emit(Ansi.DA_PRIMARY)
            'p' -> if (prefix == '?' && hasIntermediate('$')) modeReport()
            'q' -> if (prefix == '?' && hasIntermediate(' ')) emit("\u001B[1 q")
            else -> Unit
        }
    }

    private fun setMode(on: Boolean) {
        for (i in 0 until paramCount) {
            val m = param(i, 0)
            if (prefix == '?') {
                when (m) {
                    1 -> screen.setApplicationCursorKeys(on)
                    7 -> screen.setAutoWrap(on)
                    25 -> screen.setCursorVisible(on)
                    47, 1047, 1049 -> screen.setAltScreen(on)
                    2004 -> screen.setBracketedPaste(on)
                    1000, 1002, 1003 -> if (on) screen.setMouseTracking(m) else mouseOff(m)
                    else -> Unit
                }
            } else {
                when (m) {
                    4 -> screen.setInsertMode(on)
                    20 -> screen.setNewlineMode(on)
                    else -> Unit
                }
            }
        }
    }

    private fun mouseOff(mode: Int) {
        if (screen.mouseTracking == mode) screen.setMouseTracking(0)
    }

    private fun deviceStatus() {
        if (prefix == '?') {
            if (param(0, 0) == 6) {
                emit("\u001B[?${screen.cursorRow + 1};${screen.cursorColumn + 1};1R")
            } else {
                emit("\u001B[?0n")
            }
            return
        }
        when (param(0, 0)) {
            5 -> emit("\u001B[0n")
            6 -> emit("\u001B[${screen.cursorRow + 1};${screen.cursorColumn + 1}R")
        }
    }

    /** DECRQM always answers, and answers "not recognised", so a probe cannot hang. */
    private fun modeReport() {
        emit("\u001B[?${param(0, 0)};0\$y")
    }

    // ------------------------------------------------------------------ OSC

    private fun oscString(c: Int) {
        when {
            c == Ansi.BEL -> {
                flushOsc()
                state = State.GROUND
            }
            c == Ansi.ESC -> {
                flushOsc()
                beginEscape()
            }
            osc.length < OSC_LIMIT -> osc.appendCodePoint(c)
            else -> Unit
        }
    }

    private fun flushOsc() {
        if (osc.isEmpty()) return
        val body = osc.toString()
        osc.setLength(0)
        val sep = body.indexOf(';')
        val code = if (sep < 0) body else body.substring(0, sep)
        val data = if (sep < 0) "" else body.substring(sep + 1)
        when (code) {
            "0", "1", "2" -> screen.setTitle(data)
            "4" -> setPalette(data)
            // Hyperlink markers are consumed; the label is rendered plainly.
            "8" -> Unit
            "52" -> clipboard(data)
        }
    }

    private fun setPalette(data: String) {
        val parts = data.split(';')
        var i = 0
        while (i + 1 < parts.size) {
            val index = parts[i].trim().toIntOrNull() ?: return
            if (index < 0) return
            if (parts[i + 1] == "?") {
                val c = screen.paletteColor(index)
                emit("\u001B]4;$index;rgb:${c shr 16 and 0xFF}/${c shr 8 and 0xFF}/${c and 0xFF}\u0007")
            } else {
                val rgb = parseColor(parts[i + 1])
                if (rgb >= 0) screen.setPaletteColor(index, rgb)
            }
            i += 2
        }
    }

    private fun clipboard(data: String) {
        val sep = data.indexOf(';')
        if (sep < 0) return
        val payload = data.substring(sep + 1).trim()
        if (payload.isEmpty() || payload == "?") {
            emit("\u001B]52;c;OSC 52 read is disabled\u0007")
            return
        }
        runCatching { String(java.util.Base64.getDecoder().decode(payload), Charsets.UTF_8) }
            .onSuccess(onClipboard)
    }

    private fun parseColor(spec: String): Int {
        if (spec.startsWith("rgb:")) {
            val parts = spec.substring(4).split('/')
            if (parts.size != 3) return -1
            val r = scale(parts[0])
            val g = scale(parts[1])
            val b = scale(parts[2])
            if (r < 0 || g < 0 || b < 0) return -1
            return (r shl 16) or (g shl 8) or b
        }
        if (!spec.startsWith("#")) return -1
        val hex = spec.substring(1)
        if (hex.isEmpty() || hex.length % 3 != 0) return -1
        val size = hex.length / 3
        val r = scale(hex.substring(0, size))
        val g = scale(hex.substring(size, size * 2))
        val b = scale(hex.substring(size * 2))
        if (r < 0 || g < 0 || b < 0) return -1
        return (r shl 16) or (g shl 8) or b
    }

    private fun scale(part: String): Int {
        if (part.isEmpty() || part.length > 4) return -1
        var v = 0
        for (i in part.indices) {
            val d = Character.digit(part[i], 16)
            if (d < 0) return -1
            v = (v shl 4) or d
        }
        val max = (1 shl (part.length * 4)) - 1
        return v * 255 / max
    }

    private fun emit(text: String) {
        onResponse(text.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val MAX_PARAMS = 32
        const val SEQ_LIMIT = 32
        const val MAX_INTERMEDIATES = 4
        const val OSC_LIMIT = 4096
        private const val REPLACEMENT = 0xFFFD
    }
}
