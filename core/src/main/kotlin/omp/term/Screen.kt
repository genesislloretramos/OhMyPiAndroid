package omp.term

import java.util.concurrent.locks.ReentrantLock

/**
 * The terminal screen: a grid of [CellArray] rows, a cursor, a scroll region,
 * an alternate buffer and a scrollback ring.
 *
 * All mutation happens on the shell thread; the draw thread only ever reads a
 * [snapshot] copy taken under [lock]. [lock] is reentrant because the parser
 * runs inside a `write` call and calls back into the screen.
 */
class Screen(
    rows: Int,
    cols: Int,
    scrollbackLines: Int = DEFAULT_SCROLLBACK,
    private val onBell: () -> Unit = {},
) {

    var rows: Int = maxOf(1, rows)
        private set

    var cols: Int = maxOf(1, cols)
        private set

    val scrollbackLines: Int = maxOf(0, scrollbackLines)

    val lock = ReentrantLock()

    /** Parser-generated output (DSR/DA replies, an aborted sequence's reset). */
    var onOutput: ((ByteArray) -> Unit)? = null

    /** OSC 52 clipboard writes land here. */
    var onClipboard: ((String) -> Unit)? = null

    var title: String = ""
        private set

    private var mainGrid: Array<CellArray> = Array(this.rows) { CellArray(this.cols) }
    private var altGrid: Array<CellArray> = Array(this.rows) { CellArray(this.cols) }
    private var grid: Array<CellArray> = mainGrid
    private var altActive = false

    private val scrollback = Scrollback(this.scrollbackLines)
    private var scrollOffset = 0

    private var curRow = 0
    private var curCol = 0
    private var pen = 0L
    private var pendingWrap = false
    private var insertMode = false
    private var newlineMode = false
    private var lastPrinted = 0

    private var scrollTop = 0
    private var scrollBottom = this.rows - 1
    private val savedMain = SavedCursor()
    private val savedAlt = SavedCursor()

    private var cursorVisibleFlag = true
    private var autoWrapFlag = true
    private var bracketedPasteFlag = false
    private var mouseTrackingMode = 0
    private var appCursorKeys = false

    private val palette = IntArray(256) { Attr.color(it) }

    private var dirtyTopRow = 0
    private var dirtyBottomRow = this.rows
    private val blankRow = CellArray(this.cols)

    val cursorRow: Int get() = curRow

    val cursorColumn: Int get() = curCol

    val cursorVisible: Boolean get() = cursorVisibleFlag

    val autoWrap: Boolean get() = autoWrapFlag

    val bracketedPaste: Boolean get() = bracketedPasteFlag

    val mouseTracking: Int get() = mouseTrackingMode

    val applicationCursorKeys: Boolean get() = appCursorKeys

    val altScreen: Boolean get() = altActive

    /** The current foreground word: style flags plus foreground colour. */
    val currentAttr: Int get() = Attr.fgWord(pen)

    /** The current background word. */
    val currentBg: Int get() = Attr.bgWord(pen)

    val scrollbackSize: Int get() = scrollback.size

    val dirtyTop: Int get() = dirtyTopRow

    val dirtyBottom: Int get() = dirtyBottomRow

    private val parser = Parser(
        this,
        { bytes -> onOutput?.invoke(bytes) },
        { text -> onClipboard?.invoke(text) },
    )

    init {
        for (row in mainGrid) row.clear(Attr.DEFAULT_ATTR)
        for (row in altGrid) row.clear(Attr.DEFAULT_ATTR)
    }

    // ---------------------------------------------------------------- output

    fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        lock.lock()
        try {
            parser.feed(bytes)
        } finally {
            lock.unlock()
        }
    }

    fun write(text: String) {
        if (text.isEmpty()) return
        lock.lock()
        try {
            parser.feed(text)
        } finally {
            lock.unlock()
        }
    }


    /** BEL. There is no audible bell on a phone, so this is a visual hook. */
    fun bell() {
        onBell()
    }

    // ----------------------------------------------------------------- view

    /** How far the view is scrolled back from live output; 0 means pinned to the bottom. */
    val scrollbackOffset: Int get() = scrollOffset

    fun snapshot(): List<RenderRow> {
        lock.lock()
        try {
            val out = ArrayList<RenderRow>(rows)
            if (scrollOffset <= 0) {
                for (row in grid) out.add(RenderRow(row))
                return out
            }
            val first = maxOf(0, scrollback.size - scrollOffset)
            for (i in first until scrollback.size) out.add(RenderRow(scrollback.lineAt(i)))
            var screenRow = 0
            while (screenRow < rows && out.size < rows) {
                out.add(RenderRow(grid[screenRow]))
                screenRow++
            }
            blankRow.clear(Attr.DEFAULT_ATTR)
            while (out.size < rows) out.add(RenderRow(blankRow))
            return out
        } finally {
            lock.unlock()
        }
    }

    /** Scroll the viewport `n` lines back into the scrollback ring. */
    fun scrollUp(n: Int) {
        if (n <= 0) return
        lock.lock()
        try {
            scrollOffset = (scrollOffset + n).coerceAtMost(scrollback.size)
        } finally {
            lock.unlock()
        }
    }

    /** Scroll the viewport `n` lines forward. */
    fun scrollDown(n: Int) {
        if (n <= 0) return
        lock.lock()
        try {
            scrollOffset = (scrollOffset - n).coerceAtLeast(0)
        } finally {
            lock.unlock()
        }
    }

    fun scrollToBottom() {
        lock.lock()
        try {
            scrollOffset = 0
        } finally {
            lock.unlock()
        }
    }

    fun clearScrollback() {
        lock.lock()
        try {
            scrollback.clear()
            scrollOffset = 0
        } finally {
            lock.unlock()
        }
    }

    fun isDirty(): Boolean = dirtyTopRow < dirtyBottomRow

    fun markAllDirty() {
        dirtyTopRow = 0
        dirtyBottomRow = rows
    }

    fun clearDirty() {
        dirtyTopRow = rows
        dirtyBottomRow = 0
    }

    // --------------------------------------------------------------- resize

    fun resize(newRows: Int, newCols: Int) {
        val r = maxOf(1, newRows)
        val c = maxOf(1, newCols)
        if (r == rows && c == cols) return
        lock.lock()
        try {
            mainGrid = resized(mainGrid, r, c)
            altGrid = resized(altGrid, r, c)
            grid = if (altActive) altGrid else mainGrid
            rows = r
            cols = c
            blankRow.resize(c)
            scrollTop = 0
            scrollBottom = rows - 1
            curRow = curRow.coerceIn(0, rows - 1)
            curCol = curCol.coerceIn(0, cols - 1)
            pendingWrap = false
            scrollback.resizeCols(cols)
            scrollOffset = scrollOffset.coerceAtMost(scrollback.size)
            markAllDirty()
        } finally {
            lock.unlock()
        }
    }

    private fun resized(src: Array<CellArray>, r: Int, c: Int): Array<CellArray> {
        val out = Array(r) { CellArray(c) }
        for (i in 0 until minOf(src.size, r)) out[i].copyFrom(src[i])
        return out
    }

    // ------------------------------------------------------ parser callbacks

    internal fun print(code: Int) {
        if (code < 0x20 || code == 0x7F || code == 0) return
        val w = if (cols < 2) 1 else CharWidth.width(code)
        if (w == 0) {
            attachCombining(code)
            return
        }
        if (pendingWrap) {
            if (autoWrapFlag) {
                lineFeed()
                curCol = 0
            }
            pendingWrap = false
        }
        if (w == 2 && curCol + 1 >= cols) {
            if (autoWrapFlag) {
                lineFeed()
                curCol = 0
            } else {
                curCol = cols - 2
            }
        }
        val row = grid[curRow]
        val fg = Attr.fgWord(pen)
        val bg = Attr.bgWord(pen)
        if (insertMode) shiftRight(row, curCol, 1)
        row.codes[curCol] = code
        row.attrs[curCol] = fg
        row.bgs[curCol] = bg
        if (w == 2) {
            row.codes[curCol + 1] = WIDE_PAD
            row.attrs[curCol + 1] = fg
            row.bgs[curCol + 1] = bg
        }
        lastPrinted = code
        curCol += w
        if (curCol >= cols) {
            curCol = cols - 1
            if (autoWrapFlag) pendingWrap = true
        }
        row.wrapped = pendingWrap
        touchRow(curRow)
    }

    /**
     * Zero-width marks are folded onto the cell to the left. There is no
     * composition table, so a mark landing on a written cell is dropped rather
     * than displacing its base glyph.
     */
    private fun attachCombining(code: Int) {
        val col = if (pendingWrap) curCol else curCol - 1
        if (col < 0) return
        val row = grid[curRow]
        if (row.codes[col] == ' '.code) {
            row.codes[col] = code
            row.attrs[col] = Attr.fgWord(pen)
            row.bgs[col] = Attr.bgWord(pen)
            touchRow(curRow)
        }
    }

    internal fun repeatLastPrinted(n: Int) {
        if (lastPrinted == 0) return
        repeat(n.coerceAtLeast(0).coerceAtMost(cols * rows)) { print(lastPrinted) }
    }

    internal fun lineFeed() {
        pendingWrap = false
        if (curRow == scrollBottom) scrollRegionUp(1)
        else if (curRow < rows - 1) curRow++
        touchRow(curRow)
    }

    internal fun reverseIndex() {
        pendingWrap = false
        if (curRow == scrollTop) scrollRegionDown(1)
        else if (curRow > 0) curRow--
        touchRow(curRow)
    }

    internal fun carriageReturn() {
        curCol = 0
        pendingWrap = false
    }

    internal fun backspace() {
        if (curCol > 0) curCol--
        pendingWrap = false
    }

    internal fun tab() {
        val next = (curCol / TAB_WIDTH + 1) * TAB_WIDTH
        curCol = if (next >= cols) cols - 1 else next
        pendingWrap = false
    }

    internal fun cursorUp(n: Int) {
        curRow = (curRow - n.coerceAtLeast(1)).coerceAtLeast(0)
        pendingWrap = false
    }

    internal fun cursorDown(n: Int) {
        curRow = (curRow + n.coerceAtLeast(1)).coerceAtMost(rows - 1)
        pendingWrap = false
    }

    internal fun cursorForward(n: Int) {
        curCol = (curCol + n.coerceAtLeast(1)).coerceAtMost(cols - 1)
        pendingWrap = false
    }

    internal fun cursorBack(n: Int) {
        curCol = (curCol - n.coerceAtLeast(1)).coerceAtLeast(0)
        pendingWrap = false
    }

    internal fun cursorTo(row: Int, col: Int) {
        curRow = row.coerceIn(0, rows - 1)
        curCol = col.coerceIn(0, cols - 1)
        pendingWrap = false
    }

    internal fun saveCursor() {
        val s = if (altActive) savedAlt else savedMain
        s.row = curRow
        s.col = curCol
        s.pen = pen
        s.wrap = pendingWrap
    }

    internal fun restoreCursor() {
        val s = if (altActive) savedAlt else savedMain
        curRow = s.row.coerceIn(0, rows - 1)
        curCol = s.col.coerceIn(0, cols - 1)
        pen = s.pen
        pendingWrap = s.wrap
    }

    internal fun setScrollRegion(top: Int, bottom: Int) {
        val t = (top - 1).coerceIn(0, rows - 1)
        val b = (bottom - 1).coerceIn(0, rows - 1)
        if (b <= t) return
        scrollTop = t
        scrollBottom = b
        cursorTo(0, 0)
    }

    internal fun eraseInDisplay(mode: Int) {
        val e = Attr.bgWord(pen)
        when (mode) {
            0 -> {
                eraseLineFrom(curCol)
                for (r in curRow + 1 until rows) grid[r].clear(e)
            }
            1 -> {
                for (r in 0 until curRow) grid[r].clear(e)
                eraseLineTo(curCol)
            }
            2 -> for (row in grid) row.clear(e)
            3 -> clearScrollback()
        }
        pendingWrap = false
        markAllDirty()
    }

    internal fun eraseInLine(mode: Int) {
        val e = Attr.bgWord(pen)
        when (mode) {
            0 -> eraseLineFrom(curCol)
            1 -> eraseLineTo(curCol)
            2 -> grid[curRow].clear(e)
        }
        pendingWrap = false
        touchRow(curRow)
    }

    private fun eraseLineFrom(col: Int) {
        val row = grid[curRow]
        val e = Attr.bgWord(pen)
        for (c in col until cols) {
            row.codes[c] = ' '.code
            row.attrs[c] = Attr.DEFAULT_ATTR
            row.bgs[c] = e
        }
    }

    private fun eraseLineTo(col: Int) {
        val row = grid[curRow]
        val e = Attr.bgWord(pen)
        for (c in 0..minOf(col, cols - 1)) {
            row.codes[c] = ' '.code
            row.attrs[c] = Attr.DEFAULT_ATTR
            row.bgs[c] = e
        }
    }

    internal fun eraseChars(n: Int) {
        val row = grid[curRow]
        val e = Attr.bgWord(pen)
        val end = minOf(cols, curCol + n.coerceAtLeast(1))
        for (c in curCol until end) {
            row.codes[c] = ' '.code
            row.attrs[c] = Attr.DEFAULT_ATTR
            row.bgs[c] = e
        }
        touchRow(curRow)
    }

    internal fun insertChars(n: Int) {
        val row = grid[curRow]
        shiftRight(row, curCol, n.coerceAtLeast(1))
        touchRow(curRow)
    }

    internal fun deleteChars(n: Int) {
        val row = grid[curRow]
        val e = Attr.bgWord(pen)
        val count = n.coerceAtLeast(1).coerceAtMost(cols - curCol)
        val move = cols - curCol - count
        if (move > 0) {
            System.arraycopy(row.codes, curCol + count, row.codes, curCol, move)
            System.arraycopy(row.attrs, curCol + count, row.attrs, curCol, move)
            System.arraycopy(row.bgs, curCol + count, row.bgs, curCol, move)
        }
        for (c in cols - count until cols) {
            row.codes[c] = ' '.code
            row.attrs[c] = Attr.DEFAULT_ATTR
            row.bgs[c] = e
        }
        touchRow(curRow)
    }

    private fun shiftRight(row: CellArray, from: Int, count: Int) {
        val n = count.coerceAtLeast(0)
        if (from >= cols || n == 0) return
        val e = Attr.bgWord(pen)
        val move = (cols - from - n).coerceAtLeast(0)
        if (move > 0) {
            System.arraycopy(row.codes, from, row.codes, from + n, move)
            System.arraycopy(row.attrs, from, row.attrs, from + n, move)
            System.arraycopy(row.bgs, from, row.bgs, from + n, move)
        }
        for (c in from until minOf(cols, from + n)) {
            row.codes[c] = ' '.code
            row.attrs[c] = Attr.DEFAULT_ATTR
            row.bgs[c] = e
        }
    }

    internal fun insertLines(n: Int) {
        if (curRow < scrollTop || curRow > scrollBottom) return
        val e = Attr.bgWord(pen)
        repeat(n.coerceAtLeast(1)) {
            for (r in scrollBottom downTo curRow + 1) grid[r].copyFrom(grid[r - 1])
            grid[curRow].clear(e)
        }
        markAllDirty()
    }

    internal fun deleteLines(n: Int) {
        if (curRow < scrollTop || curRow > scrollBottom) return
        val e = Attr.bgWord(pen)
        repeat(n.coerceAtLeast(1)) {
            for (r in curRow until scrollBottom) grid[r].copyFrom(grid[r + 1])
            grid[scrollBottom].clear(e)
        }
        markAllDirty()
    }

    internal fun scrollUpLines(n: Int) = scrollRegionUp(n.coerceAtLeast(1))

    internal fun scrollDownLines(n: Int) = scrollRegionDown(n.coerceAtLeast(1))

    private fun scrollRegionUp(n: Int) {
        if (n <= 0) return
        val count = n.coerceAtMost(scrollBottom - scrollTop + 1)
        if (scrollTop == 0 && !altActive) {
            for (i in 0 until count) scrollback.push(grid[scrollTop + i])
        }
        val e = Attr.bgWord(pen)
        repeat(count) {
            for (r in scrollTop until scrollBottom) grid[r].copyFrom(grid[r + 1])
            grid[scrollBottom].clear(e)
        }
        markAllDirty()
    }

    private fun scrollRegionDown(n: Int) {
        if (n <= 0) return
        val count = n.coerceAtMost(scrollBottom - scrollTop + 1)
        val e = Attr.bgWord(pen)
        repeat(count) {
            for (r in scrollBottom downTo scrollTop + 1) grid[r].copyFrom(grid[r - 1])
            grid[scrollTop].clear(e)
        }
        markAllDirty()
    }

    internal fun applySgr(params: IntArray, count: Int) {
        pen = Attr.applySgr(pen, params, count)
    }

    internal fun sgrReset() {
        pen = 0L
    }

    internal fun setCursorVisible(visible: Boolean) {
        cursorVisibleFlag = visible
    }

    internal fun setAutoWrap(enabled: Boolean) {
        autoWrapFlag = enabled
        if (!enabled) pendingWrap = false
    }

    internal fun setInsertMode(enabled: Boolean) {
        insertMode = enabled
    }

    internal fun setNewlineMode(enabled: Boolean) {
        newlineMode = enabled
    }

    internal fun newlineMode(): Boolean = newlineMode

    internal fun setApplicationCursorKeys(enabled: Boolean) {
        appCursorKeys = enabled
    }

    internal fun setBracketedPaste(enabled: Boolean) {
        bracketedPasteFlag = enabled
    }

    internal fun setMouseTracking(mode: Int) {
        mouseTrackingMode = mode
    }

    /** 1049/1047/47: the alternate buffer keeps no scrollback. */
    internal fun setAltScreen(enabled: Boolean) {
        if (enabled == altActive) return
        if (enabled) {
            saveCursor()
            altActive = true
            grid = altGrid
            for (row in grid) row.clear(Attr.DEFAULT_ATTR)
            cursorTo(0, 0)
        } else {
            altActive = false
            grid = mainGrid
            restoreCursor()
        }
        markAllDirty()
    }

    internal fun reset() {
        if (altActive) {
            altActive = false
            grid = mainGrid
        }
        for (row in mainGrid) row.clear(Attr.DEFAULT_ATTR)
        for (row in altGrid) row.clear(Attr.DEFAULT_ATTR)
        pen = 0L
        curRow = 0
        curCol = 0
        pendingWrap = false
        insertMode = false
        newlineMode = false
        scrollTop = 0
        scrollBottom = rows - 1
        cursorVisibleFlag = true
        autoWrapFlag = true
        bracketedPasteFlag = false
        mouseTrackingMode = 0
        appCursorKeys = false
        markAllDirty()
    }

    internal fun setTitle(text: String) {
        title = text
    }

    internal fun setPaletteColor(index: Int, rgb: Int) {
        if (index in palette.indices) palette[index] = rgb and 0xFFFFFF
    }

    internal fun paletteColor(index: Int): Int = if (index in palette.indices) palette[index] else 0

    private fun touchRow(r: Int) {
        if (r < dirtyTopRow) dirtyTopRow = r
        if (r + 1 > dirtyBottomRow) dirtyBottomRow = r + 1
    }

    private class SavedCursor {
        var row = 0
        var col = 0
        var pen = 0L
        var wrap = false
    }

    companion object {
        const val DEFAULT_SCROLLBACK = 2000
        private const val TAB_WIDTH = 8
    }
}

/** Fixed-capacity ring of lines that have scrolled off the top of the screen. */
internal class Scrollback(val capacity: Int) {

    private val buf = arrayOfNulls<CellArray>(capacity)
    private var head = 0

    var size = 0
        private set

    fun push(line: CellArray) {
        if (capacity == 0) return
        val copy = CellArray(line.cols)
        copy.copyFrom(line)
        buf[head] = copy
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    fun lineAt(index: Int): CellArray = buf[(head - size + index + capacity) % capacity]!!

    fun clear() {
        buf.fill(null)
        head = 0
        size = 0
    }

    fun resizeCols(cols: Int) {
        for (line in buf) line?.resize(cols)
    }
}
