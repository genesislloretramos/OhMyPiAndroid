package omp.shell

import omp.shell.exec.CommandTable
import omp.term.Screen

private const val ESC = 27.toChar()
private const val CSI = "$ESC["
private const val ESC_2K = "${CSI}2K"
private const val CSI_DOWN = "${CSI}1B"

/** [nextKey] reports end of input with this, which no real key can collide with. */
private const val NO_KEY = -1

/**
 * How long the editor waits for a key before looking at the world again. Long enough that an idle
 * prompt costs nothing, short enough that Back out of a pushed session feels immediate.
 */
private const val EXIT_POLL_MS = 100L

/** Decoded keys. Printable characters arrive as their code point; control keys are negative. */
object Key {
    const val UP = -101
    const val DOWN = -102
    const val LEFT = -103
    const val RIGHT = -104
    const val HOME = -105
    const val END = -106
    const val PGUP = -107
    const val PGDN = -108
    const val INSERT = -109
    const val DELETE = -110
    const val F1 = -111
    const val F12 = -122

    const val ENTER = 0x0D
    const val TAB = 0x09
    const val BACKSPACE = 0x7F
    const val ESCAPE = 0x1B
    const val CTRL_C = 0x03
    const val CTRL_D = 0x04
    const val CTRL_R = 0x12
    const val CTRL_Y = 0x19
    const val CTRL_A = 0x01
    const val CTRL_B = 0x02
    const val CTRL_E = 0x05
    const val CTRL_F = 0x06
    const val CTRL_K = 0x0B
    const val CTRL_U = 0x15
    const val CTRL_W = 0x17
    const val CTRL_L = 0x0C
    const val CTRL_T = 0x14
}

sealed class ReadResult {
    data class Line(val text: String) : ReadResult()
    object Interrupted : ReadResult()
    object Eof : ReadResult()
}

/**
 * Emacs line editing over the terminal screen, because a soft keyboard cannot send control
 * characters on its own and the extra-keys bar feeds the same code path.
 */
class LineEditor(
    private val session: Session,
    private val screen: Screen,
    private val input: InputChannel,
) {
    private val bytes = ArrayList<Byte>()
    private val keys = ArrayList<Int>()

    private val buf = StringBuilder()
    private var pos = 0
    private var anchorRow = 0
    private var anchorCol = 0
    private var lastRow = 0
    private var historyIndex = -1
    private var stash = ""
    private var killRing = ""
    private var lastCompletionToken: String? = null
    private var lastCompletionCandidates: List<String> = emptyList()

    private var searchActive = false
    private var searchQuery = ""
    private var searchMatch: String? = null
    private var searchOriginal = ""
    private var searchBaseIndex = -1

    /** Blocks until a key is available, decoding bytes into keys on the way. */
    fun nextKey(): Int {
        while (true) {
            decodeAvailable()
            if (keys.isNotEmpty()) return keys.removeAt(0)
            val b = input.readByte()
            if (b < 0) {
                decodeAvailable()
                return if (keys.isNotEmpty()) keys.removeAt(0) else -1
            }
            bytes.add(b.toByte())
        }
    }

    /**
     * A key, or null when none arrived within [timeoutMs]. Same decoding as [nextKey], but the wait
     * is a poll so the caller's loop can look at state the rest of the app changes behind it.
     */
    private fun nextKeyWithin(timeoutMs: Long): Int? {
        while (true) {
            decodeAvailable()
            if (keys.isNotEmpty()) return keys.removeAt(0)
            val b = input.pollByte(timeoutMs)
            if (b == null) return null
            if (b < 0) {
                decodeAvailable()
                return if (keys.isNotEmpty()) keys.removeAt(0) else -1
            }
            // A key that is still arriving loops round and waits for the rest, as nextKey does.
            bytes.add(b.toByte())
        }
    }

    /** Non-blocking drain, for the watcher that runs while an interactive command owns the terminal. */
    fun pollKey(timeoutMs: Long): Int? {
        val b = input.pollByte(timeoutMs)
        if (b != null && b >= 0) bytes.add(b.toByte())
        decodeAvailable()
        return if (keys.isNotEmpty()) keys.removeAt(0) else null
    }

    fun hasPendingInput(): Boolean = keys.isNotEmpty() || bytes.isNotEmpty()

    private var lastTyped: String = ""

    /**
     * Turns buffered bytes into keys. A partial escape sequence or a split UTF-8 character stays in
     * [bytes] until the rest arrives, so no input is ever misread as a different key.
     */
    private fun decodeAvailable() {
        while (bytes.isNotEmpty()) {
            val b = bytes[0].toInt() and 0xFF
            when {
                b == 0x1B -> {
                    if (bytes.size == 1) return
                    val n = bytes[1].toInt() and 0xFF
                    if (n == '['.code || n == 'O'.code) {
                        val end = findCsiEnd()
                        if (end < 0) {
                            return
                        }
                        val seq = StringBuilder()
                        for (i in 1..end) seq.append((bytes[i].toInt() and 0xFF).toChar())
                        for (i in 0..end) bytes.removeAt(0)
                        keys.add(mapCsi(seq.toString(), n == 'O'.code))
                    } else {
                        bytes.removeAt(0)
                        keys.add(Key.ESCAPE)
                    }
                }
                b < 0x20 || b == 0x7F -> {
                    bytes.removeAt(0)
                    keys.add(b)
                }
                else -> {
                    val need = utf8Length(b)
                    if (bytes.size < need) {
                        return
                    }
                    val arr = ByteArray(need)
                    for (i in 0 until need) arr[i] = bytes.removeAt(0)
                    keys.add(String(arr, Charsets.UTF_8).codePointAt(0))
                }
            }
        }
    }

    private fun findCsiEnd(): Int {
        var i = 2
        while (i < bytes.size) {
            val c = bytes[i].toInt() and 0xFF
            if (c in 0x40..0x7E) return i
            i++
        }
        return -1
    }

    private fun mapCsi(seq: String, ss3: Boolean): Int {
        val final = seq.last()
        if (ss3) {
            return when (final) {
                'P' -> Key.F1
                'Q' -> Key.F1 + 1
                'R' -> Key.F1 + 2
                'S' -> Key.F1 + 3
                else -> -1
            }
        }
        return when (final) {
            'A' -> Key.UP
            'B' -> Key.DOWN
            'C' -> Key.RIGHT
            'D' -> Key.LEFT
            'H' -> Key.HOME
            'F' -> Key.END
            else -> when (seq) {
                "[5~" -> Key.PGUP
                "[6~" -> Key.PGDN
                "[2~" -> Key.INSERT
                "[3~" -> Key.DELETE
                "[1~", "[7~" -> Key.HOME
                "[4~", "[8~" -> Key.END
                "[15~" -> Key.F1 + 4
                "[17~" -> Key.F1 + 5
                "[18~" -> Key.F1 + 6
                "[19~" -> Key.F1 + 7
                "[20~" -> Key.F1 + 8
                "[21~" -> Key.F1 + 9
                "[23~" -> Key.F1 + 10
                "[24~" -> Key.F1 + 11
                else -> -1
            }
        }
    }

    private fun utf8Length(lead: Int): Int = when {
        lead < 0x80 -> 1
        lead and 0xE0 == 0xC0 -> 2
        lead and 0xF0 == 0xE0 -> 3
        lead and 0xF8 == 0xF0 -> 4
        else -> 1
    }

    // ---- reading a line -------------------------------------------------------------

    fun readLine(prompt: String): ReadResult {
        reset()
        anchorRow = screen.cursorRow
        anchorCol = screen.cursorColumn
        lastRow = anchorRow
        render(prompt)
        while (true) {
            // A poll, not a blocking read: `exitRequested` is set from another thread when a pushed
            // session is popped, and a blocked editor would wait for a key that never arrives.
            val k = nextKeyWithin(EXIT_POLL_MS) ?: run {
                if (session.exitRequested) return ReadResult.Eof
                continue
            }
            if (k == NO_KEY) return ReadResult.Eof
            if (k == Key.CTRL_C) {
                screen.write("^C\r\n")
                return ReadResult.Interrupted
            }
            if (k == Key.CTRL_D) {
                if (buf.isEmpty()) return ReadResult.Eof
                deleteForward()
                render(prompt)
                continue
            }
            if (handle(k, prompt)) {
                session.history.add(buf.toString())
                finishLine(prompt)
                return ReadResult.Line(buf.toString())
            }
            render(prompt)
        }
    }

    private fun reset() {
        buf.setLength(0)
        pos = 0
        lastRow = anchorRow
        historyIndex = -1
        searchActive = false
        lastCompletionToken = null
        lastCompletionCandidates = emptyList()
    }

    private fun finishLine(prompt: String) {
        moveTo(screen.cursorRow, screen.cursorColumn)
        screen.write("\r\n")
        anchorRow = screen.cursorRow
        anchorCol = screen.cursorColumn
        lastRow = anchorRow
    }

    /** @return true when the line is complete and should be returned. */
    private fun handle(k: Int, prompt: String): Boolean {
        if (searchActive) return handleSearch(k, prompt)
        when {
            k == Key.ENTER -> return true
            k == Key.CTRL_A || k == Key.HOME -> pos = 0
            k == Key.CTRL_E || k == Key.END -> pos = buf.length
            k == Key.CTRL_B || k == Key.LEFT -> if (pos > 0) pos--
            k == Key.CTRL_F || k == Key.RIGHT -> if (pos < buf.length) pos++
            k == Key.CTRL_D || k == Key.DELETE -> deleteForward()
            k == Key.BACKSPACE || k == 0x08 -> deleteBackward()
            k == Key.CTRL_K -> killRing += killToEnd()
            k == Key.CTRL_U -> killRing += killWhole()
            k == Key.CTRL_W -> killRing += killWordBackward()
            k == 0x15 -> buf.setLength(0).also { pos = 0 }
            k == Key.CTRL_Y -> {
                if (killRing.isNotEmpty()) insert(killRing)
            }
            k == 0x14 -> transpose()
            k == Key.UP -> historyMove(-1)
            k == Key.DOWN -> historyMove(1)
            k == Key.TAB -> complete()
            k == Key.CTRL_R -> startSearch()
            k == 0x07 -> {
                // Ctrl-G: abandon a search, otherwise nothing.
            }
            k == Key.PGUP || k == Key.PGDN -> scrollHint(k)
            k == Key.ESCAPE -> return false
            k in 0x01..0x1A -> return false
            k > 0x1F && !Character.isISOControl(k) -> insertCodePoint(k)
            else -> return false
        }
        return false
    }

    // ---- editing primitives ----------------------------------------------------------

    private fun insert(s: String) {
        buf.insert(pos, s)
        pos += s.length
    }

    private fun insertCodePoint(cp: Int) {
        val s = String(Character.toChars(cp))
        insert(s)
    }

    private fun deleteForward() {
        if (pos < buf.length) buf.deleteCharAt(pos)
    }

    private fun deleteBackward() {
        if (pos > 0) {
            buf.deleteCharAt(pos - 1)
            pos--
        }
    }

    private fun killToEnd(): String = buf.substring(pos).also { buf.setLength(pos) }

    private fun killWhole(): String {
        val s = buf.toString()
        buf.setLength(0)
        pos = 0
        return s
    }

    private fun killWordBackward(): String {
        var end = pos
        while (end > 0 && buf[end - 1] == ' ') end--
        while (end > 0 && buf[end - 1] != ' ') end--
        val s = buf.substring(end, pos)
        buf.delete(end, pos)
        pos = end
        return s
    }

    private fun wordLeft(): Int {
        var i = pos
        while (i > 0 && buf[i - 1] == ' ') i--
        while (i > 0 && buf[i - 1] != ' ') i--
        return i
    }

    private fun wordRight(): Int {
        var i = pos
        val n = buf.length
        while (i < n && buf[i] == ' ') i++
        while (i < n && buf[i] != ' ') i++
        return i
    }

    private fun transpose() {
        if (buf.isEmpty()) return
        if (pos == 0) pos = 1
        if (pos >= buf.length) pos = buf.length - 1
        val a = buf[pos - 1]
        val b = buf[pos]
        buf[pos - 1] = b
        buf[pos] = a
        pos++
    }

    // ---- history ---------------------------------------------------------------------

    private fun historyMove(delta: Int) {
        val all = session.history.all()
        if (all.isEmpty()) return
        if (historyIndex == -1) {
            if (delta > 0) return
            stash = buf.toString()
            historyIndex = all.size
        }
        val next = historyIndex + delta
        if (next < 0) return
        if (next >= all.size) {
            historyIndex = -1
            setBuffer(stash)
        } else {
            historyIndex = next
            setBuffer(all[next])
        }
    }

    private fun setBuffer(s: String) {
        buf.setLength(0)
        buf.append(s)
        pos = buf.length
    }

    // ---- reverse incremental search --------------------------------------------------

    private fun startSearch() {
        searchActive = true
        searchQuery = ""
        searchBaseIndex = session.history.size()
        searchMatch = null
        searchOriginal = buf.toString()
    }

    private fun handleSearch(k: Int, prompt: String): Boolean {
        when (k) {
            Key.CTRL_R -> {
                searchQuery += lastTyped
                runSearch()
                return false
            }
            Key.CTRL_C -> {
                searchActive = false
                screen.write("^C\r\n")
                return true
            }
            Key.ENTER -> {
                searchActive = false
                if (searchMatch != null) {
                    setBuffer(searchMatch!!)
                    session.history.add(buf.toString())
                    finishLine(prompt)
                    return true
                }
                setBuffer(searchOriginal)
                return false
            }
            0x07, Key.ESCAPE -> {
                searchActive = false
                setBuffer(searchOriginal)
                return false
            }
            Key.BACKSPACE, 0x08 -> {
                if (searchQuery.isEmpty()) {
                    searchActive = false
                } else {
                    searchQuery = searchQuery.dropLast(1)
                    runSearch()
                }
                return false
            }
            else -> {
                if (k > 0x1F && !Character.isISOControl(k)) {
                    lastTyped = String(Character.toChars(k))
                    searchQuery += lastTyped
                    runSearch()
                }
                return false
            }
        }
    }
    private fun runSearch() {
        val all = session.history.all()
        var found: String? = null
        for (i in all.size - 1 downTo 0) {
            if (searchQuery.isEmpty() || all[i].contains(searchQuery)) {
                found = all[i]
                searchBaseIndex = i
                break
            }
        }
        searchMatch = found
        if (found != null) setBuffer(found) else setBuffer("")
    }

    // ---- completion ------------------------------------------------------------------

    private fun complete() {
        val start = tokenStart()
        val raw = buf.substring(start, pos)
        val (candidates, insertSuffix) = candidatesFor(raw)
        if (candidates.isEmpty()) {
            lastCompletionToken = null
            return
        }
        val token = raw
        if (token == lastCompletionToken && candidates.size > 1) {
            lastCompletionToken = null
            moveTo(screen.cursorRow, screen.cursorColumn)
            screen.write("\r\n")
            for (c in candidates) screen.write(c + "\r\n")
            anchorRow = screen.cursorRow
            anchorCol = screen.cursorColumn
            lastRow = anchorRow
            render("")
            screen.write("\r")
            return
        }
        if (candidates.size == 1) {
            val completion = candidates[0].removePrefix(raw)
            buf.insert(pos, completion + insertSuffix)
            pos += completion.length + insertSuffix.length
            lastCompletionToken = null
            return
        }
        val common = longestCommonPrefix(candidates)
        if (common.length > raw.length) {
            buf.insert(pos, common.removePrefix(raw))
            pos += common.length - raw.length
        }
        lastCompletionToken = token
        lastCompletionCandidates = candidates
    }

    private fun candidatesFor(raw: String): Pair<List<String>, String> {
        if (raw.isEmpty()) return Pair(emptyList(), "")
        if (!raw.contains('/') && raw.startsWith("$")) {
            val prefix = raw.drop(1)
            val names = session.env.keys.filter { it.startsWith(prefix) }.sorted()
            return Pair(names.map { "\$$it" }, " ")
        }
        val expanded = try {
            omp.shell.parser.Expander(session) { "" }.expandWords(listOf(omp.shell.parser.Lexer(raw).tokenize()[0].word!!))
        } catch (e: Exception) {
            return Pair(emptyList(), "")
        }
        val path = expanded.firstOrNull() ?: return Pair(emptyList(), "")
        if (raw.contains('/') || expanded.size != 1 && path.contains('/')) {
            return Pair(pathCandidates(path), "")
        }
        if (raw.contains('/')) return Pair(pathCandidates(path), "")
        val names = (session.table.names() + session.aliases.keys).distinct().sorted()
        val matches = names.filter { it.startsWith(path) && it.length > path.length }
        return Pair(if (matches.isEmpty()) emptyList() else matches, " ")
    }

    private fun pathCandidates(path: String): List<String> {
        val slash = path.lastIndexOf('/')
        val dir = if (slash < 0) session.cwd else if (slash == 0) "/" else path.substring(0, slash)
        val prefix = if (slash < 0) path else path.substring(slash + 1)
        val entries = try {
            session.vfs.readDir(if (dir.isEmpty()) "/" else dir)
        } catch (e: omp.shell.fs.FsException) {
            null
        } ?: return emptyList()
        val base = if (dir.endsWith("/")) dir else dir + "/"
        return entries.filter { it.name.startsWith(prefix) && it.name.length > prefix.length }
            .map { base + it.name }
            .sorted()
    }

    private fun longestCommonPrefix(list: List<String>): String {
        if (list.isEmpty()) return ""
        var prefix = list[0]
        for (s in list) {
            var i = 0
            while (i < prefix.length && i < s.length && prefix[i] == s[i]) i++
            prefix = prefix.substring(0, i)
            if (prefix.isEmpty()) break
        }
        return prefix
    }

    private fun tokenStart(): Int {
        var i = pos
        while (i > 0 && buf[i - 1] != ' ') i--
        return i
    }

    private fun scrollHint(k: Int) {
        if (k == Key.PGUP) screen.scrollUp(1) else screen.scrollDown(1)
    }

    // ---- rendering -------------------------------------------------------------------

    private fun render(prompt: String) {
        // Erase exactly the rows this prompt occupies. Erasing to the end of the screen instead
        // would wipe anything that wrapped onto the line below, which is command output.
        val occupied = (lastRow - anchorRow).coerceAtLeast(0)
        moveTo(anchorRow, anchorCol)
        repeat(occupied + 1) {
            screen.write(ESC_2K)
            screen.write(CSI_DOWN)
        }
        moveTo(anchorRow, anchorCol)
        screen.write(prompt)
        screen.write(buf.toString())
        val total = anchorCol + plainLength(prompt) + buf.length
        val cols = screen.cols
        val row = anchorRow + total / cols
        val col = total % cols
        lastRow = row
        moveTo(row, col)
    }

    private fun plainLength(text: String): Int = stripEscapes(text).length

    private fun stripEscapes(text: String): String {
        if (!text.contains(ESC)) return text
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i] == ESC && i + 1 < text.length && text[i + 1] == '[') {
                i += 2
                while (i < text.length && text[i] !in "@ABCDEFGHJKSTfmnsulh") i++
                i++
                continue
            }
            sb.append(text[i])
            i++
        }
        return sb.toString()
    }

    private fun moveTo(row: Int, col: Int) {
        screen.write("$CSI${row + 1};${col + 1}H")
    }
}
