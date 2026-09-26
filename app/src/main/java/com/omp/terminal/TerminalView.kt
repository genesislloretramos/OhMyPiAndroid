package com.omp.terminal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.LongSparseArray
import android.util.TypedValue
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import omp.term.Attr
import omp.term.Cell
import omp.term.CharWidth
import omp.term.RenderRow
import omp.term.Screen
import omp.term.WIDE_PAD

/**
 * Draws the shell's [Screen] directly. No `TextView`, no `StaticLayout`: the shell's screen buffer is
 * already a grid of cells, and per-cell `drawText` is what drops frames on long output, so glyphs
 * are rasterised once into a cache keyed by (codepoint, attribute) and blitted after that.
 */
class TerminalView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    /**
     * Set by the Activity straight after inflation, before the first frame. A view that has not
     * been attached yet draws nothing rather than guessing at a screen.
     */
    lateinit var screen: Screen
        private set

    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val fillPaint = Paint()
    private val atlas = LongSparseArray<Bitmap>()
    private val dst = Rect()

    var cellHeightPx: Int = 0
        private set
    var cellWidthPx: Int = 0
        private set
    var baselinePx: Int = 0
        private set

    var cols: Int = 1
        private set
    var rows: Int = 1
        private set

    var copyOnSelect: Boolean = true

    /**
     * Fired once the view has a real size and the screen has been resized to match. The shell must
     * not write before this, or its first output is laid out for the wrong grid and then truncated.
     */
    var onFirstLayout: (() -> Unit)? = null

    private var firstLayoutDone = false

    /** Receives the selected text when a selection is released. */
    var onCopy: ((String) -> Unit)? = null

    /** Told when a paste request comes from the extra-keys or a long-press on the status row. */
    var onRequestPaste: (() -> Unit)? = null

    private var scaleDetector: ScaleGestureDetector? = null
    private var cellHeightPref: Int = 0
    private var forceRedraw = true

    // ---- touch state -------------------------------------------------------------------

    private enum class Gesture { NONE, SCROLL, SELECT }

    private var gesture = Gesture.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var scrollAtDown = 0
    private var selStartRow = 0
    private var selStartCol = 0
    private var selEndRow = 0
    private var selEndCol = 0
    private var selection = false
    private var longPressScheduled = false

    private val longPressRunnable = Runnable {
        longPressScheduled = false
        if (gesture == Gesture.NONE || downX < 0 || downY < 0) return@Runnable
        gesture = Gesture.SELECT
        val row = rowAt(downY)
        val col = colAt(downX)
        selStartRow = row
        selStartCol = col
        selEndRow = row
        selEndCol = col
        selection = true
        invalidate()
    }

    // ---- metrics -----------------------------------------------------------------------

    fun attach(screen: Screen, cellHeightPx: Int, copyOnSelect: Boolean) {
        this.screen = screen
        this.copyOnSelect = copyOnSelect
        setCellHeight(cellHeightPx)
        measureFont()
        // The grid is sized in onSizeChanged: laying out here would resize the screen to 1x1 and
        // the shell would wrap its first output into a single column.
        screen.markAllDirty()
    }

    fun setCellHeight(px: Int) {
        val clamped = px.coerceIn(dpToPx(MIN_DP), dpToPx(MAX_DP))
        if (clamped == cellHeightPx) return
        cellHeightPref = clamped
        cellHeightPx = clamped
        measureFont()
        layoutGrid()
        screen.markAllDirty()
        invalidate()
    }

    fun cellHeightPref(): Int = cellHeightPref

    private fun measureFont() {
        val textSize = cellHeightPx * GLYPH_HEIGHT_RATIO
        glyphPaint.textSize = textSize.toFloat()
        glyphPaint.typeface = Typeface.MONOSPACE
        cellWidthPx = Math.round(glyphPaint.measureText("M")).coerceAtLeast(1)
        val fm = glyphPaint.fontMetricsInt
        // Centre the ascent..descent box in the cell: top margin is (H - (descent - ascent)) / 2,
        // and the baseline sits one ascent below it.
        baselinePx = (cellHeightPx - fm.descent - fm.ascent) / 2
        atlas.clear()
    }

    private fun layoutGrid() {
        val newRows = (height / cellHeightPx).coerceAtLeast(1)
        val newCols = (width / cellWidthPx).coerceAtLeast(1)
        if (newRows == rows && newCols == cols) return
        rows = newRows
        cols = newCols
        screen.resize(rows, cols)
        screen.markAllDirty()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!this::screen.isInitialized) return
        if (cellHeightPx == 0) {
            cellHeightPx = dpToPx(DEFAULT_CELL_DP)
            cellHeightPref = cellHeightPx
        }
        measureFont()
        layoutGrid()
        atlas.clear()
        if (!firstLayoutDone) {
            firstLayoutDone = true
            onFirstLayout?.invoke()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!this::screen.isInitialized) return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        if (screen.isDirty() && screen.scrollbackOffset > 0) screen.scrollToBottom()
        forceRedraw = false
        screen.clearDirty()
        fillPaint.color = BACKGROUND
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fillPaint)

        val snapshot = screen.snapshot()
        val usedRows = minOf(snapshot.size, rows)
        val usedCols = minOf(cols, w / cellWidthPx)

        for (r in 0 until usedRows) {
            val row = snapshot[r]
            drawRow(canvas, row, r, usedCols)
        }
        if (screen.cursorVisible && screen.scrollbackOffset == 0) {
            fillPaint.color = FOREGROUND
            val cx = screen.cursorColumn * cellWidthPx
            val cy = screen.cursorRow * cellHeightPx
            if (cx in 0..w && cy in 0..h) {
                canvas.drawRect(
                    cx.toFloat(), cy.toFloat(),
                    (cx + cellWidthPx).toFloat(), (cy + cellHeightPx).toFloat(),
                    fillPaint,
                )
            }
        }
        if (selection) drawSelectionOverlay(canvas, snapshot)
    }

    private fun drawRow(canvas: Canvas, row: RenderRow, rowIndex: Int, usedCols: Int) {
        val top = rowIndex * cellHeightPx
        var col = 0
        while (col < usedCols) {
            val attr = row.attrs[col]
            val bgWord = row.bgAt(col)
            val code = row.codes[col]
            if (code == WIDE_PAD) {
                col++
                continue
            }
            if (!Attr.bgIsDefault(bgWord)) {
                // One rect per run of identical background: a run of spaces is then a single fill.
                var end = col + 1
                while (end < usedCols && row.bgAt(end) == bgWord && row.codes[end] == ' '.code) end++
                fillPaint.color = resolveBackground(attr, bgWord)
                canvas.drawRect(
                    (col * cellWidthPx).toFloat(), top.toFloat(),
                    (end * cellWidthPx).toFloat(), (top + cellHeightPx).toFloat(),
                    fillPaint,
                )
                if (code != ' '.code && code != 0) drawGlyph(canvas, code, attr, bgWord, col, top)
                col = end
                continue
            }
            if (code != ' '.code && code != 0) drawGlyph(canvas, code, attr, bgWord, col, top)
            col++
        }
    }

    private fun drawGlyph(canvas: Canvas, code: Int, attr: Int, bgWord: Int, col: Int, top: Int) {
        val key = glyphKey(code, attr, bgWord)
        var bmp = atlas.get(key)
        if (bmp == null) {
            bmp = rasterize(code, attr, bgWord)
            atlas.put(key, bmp)
            if (atlas.size() > MAX_ATLAS_ENTRIES) atlas.clear()
        }
        val widthCells = CharWidth.width(code).coerceAtLeast(1)
        dst.set(col * cellWidthPx, top, (col + widthCells) * cellWidthPx, top + cellHeightPx)
        canvas.drawBitmap(bmp, null, dst, null)
    }

    /**
     * A codepoint, two 24-bit colours and four style bits do not fit in one Int, so the key is a
     * Long: bg 0..23, flags 24..27, fg 28..51, codepoint 52..72.
     */
    private fun glyphKey(code: Int, attr: Int, bgWord: Int): Long {
        val flags = (if (Attr.bold(attr)) 1 else 0) or
            (if (Attr.italic(attr)) 2 else 0) or
            (if (Attr.underline(attr)) 4 else 0) or
            (if (Attr.doubleUnderline(attr)) 8 else 0) or
            (if (Attr.inverse(attr)) 16 else 0)
        return (resolveBackground(attr, bgWord) and 0xFFFFFF).toLong() or
            (flags.toLong() shl 24) or
            ((resolveForeground(attr) and 0xFFFFFF).toLong() shl 28) or
            (code.toLong() shl 52)
    }

    private fun rasterize(code: Int, attr: Int, bgWord: Int): Bitmap {
        val widthCells = CharWidth.width(code).coerceAtLeast(1)
        val w = cellWidthPx * widthCells
        val h = cellHeightPx
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(resolveBackground(attr, bgWord))
        val ink = resolveForeground(attr, bgWord)
        glyphPaint.color = if (Attr.inverse(attr)) resolveBackground(attr, bgWord) else ink
        glyphPaint.isFakeBoldText = Attr.bold(attr)
        glyphPaint.textSkewX = if (Attr.italic(attr)) -0.25f else 0f
        glyphPaint.textSize = (cellHeightPx * GLYPH_HEIGHT_RATIO).toFloat()
        glyphPaint.typeface = Typeface.MONOSPACE
        c.drawText(String(Character.toChars(code)), 0f, baselinePx.toFloat(), glyphPaint)
        if (Attr.underline(attr)) {
            val p = Paint()
            p.color = glyphPaint.color
            p.strokeWidth = 1f
            val y = (baselinePx + 2).toFloat()
            c.drawLine(0f, y, w.toFloat(), y, p)
            if (Attr.doubleUnderline(attr)) c.drawLine(0f, y + 2f, w.toFloat(), y + 2f, p)
        }
        return bmp
    }

    // Attr.fg/Attr.bg carry 24-bit RGB with a zero alpha byte, which a Paint would read as fully
    // transparent; every colour has to be re-packed opaque before it reaches a canvas.
    private fun resolveForeground(attr: Int, bgWord: Int = 0): Int {
        if (Attr.inverse(attr)) return opaque(if (Attr.bgIsDefault(bgWord)) BACKGROUND else Attr.bg(bgWord))
        val base = if (Attr.fgIsDefault(attr)) FOREGROUND else opaque(Attr.fg(attr))
        val lit = if (Attr.bold(attr)) lighten(base) else base
        return if (Attr.dim(attr)) darken(lit) else lit
    }

    private fun resolveBackground(attr: Int, bgWord: Int): Int {
        if (Attr.inverse(attr)) return opaque(if (Attr.fgIsDefault(attr)) FOREGROUND else Attr.fg(attr))
        return opaque(if (Attr.bgIsDefault(bgWord)) BACKGROUND else Attr.bg(bgWord))
    }

    private fun opaque(rgb: Int): Int = 0xFF000000.toInt() or (rgb and 0xFFFFFF)

    private fun darken(rgb: Int): Int = Color.rgb(
        (Color.red(rgb) * DIM_FACTOR).toInt(),
        (Color.green(rgb) * DIM_FACTOR).toInt(),
        (Color.blue(rgb) * DIM_FACTOR).toInt(),
    )

    private fun lighten(rgb: Int): Int = Color.rgb(
        Math.min(255, Color.red(rgb) + BOLD_LIFT),
        Math.min(255, Color.green(rgb) + BOLD_LIFT),
        Math.min(255, Color.blue(rgb) + BOLD_LIFT),
    )

    // ---- selection ---------------------------------------------------------------------

    private fun drawSelectionOverlay(canvas: Canvas, snapshot: List<RenderRow>) {
        val (r0, c0, r1, c1) = orderedSelection()
        fillPaint.color = SELECTION
        for (r in r0..r1) {
            if (r >= snapshot.size) break
            val from = if (r == r0) c0 else 0
            val to = if (r == r1) c1 + 1 else cols
            canvas.drawRect(
                (from * cellWidthPx).toFloat(), (r * cellHeightPx).toFloat(),
                (to * cellWidthPx).toFloat(), ((r + 1) * cellHeightPx).toFloat(),
                fillPaint,
            )
        }
    }

    private fun orderedSelection(): IntArray {
        var r0 = minOf(selStartRow, selEndRow)
        var r1 = maxOf(selStartRow, selEndRow)
        val c0: Int
        val c1: Int
        if (r0 == r1) {
            c0 = minOf(selStartCol, selEndCol)
            c1 = maxOf(selStartCol, selEndCol)
        } else if (selStartRow < selEndRow) {
            c0 = selStartCol
            c1 = selEndCol
        } else {
            r0 = minOf(selStartRow, selEndRow)
            r1 = maxOf(selStartRow, selEndRow)
            c0 = selEndCol
            c1 = selStartCol
        }
        return intArrayOf(r0, c0, r1, c1)
    }

    private fun selectedText(snapshot: List<RenderRow>): String {
        val (r0, c0, r1, c1) = orderedSelection()
        val sb = StringBuilder()
        for (r in r0..r1) {
            if (r >= snapshot.size) break
            val row = snapshot[r]
            val from = (if (r == r0) c0 else 0).coerceAtMost(cols - 1)
            val to = (if (r == r1) c1 + 1 else cols).coerceAtMost(row.codes.size)
            if (to <= from) continue
            for (i in from until to) {
                val c = row.codes[i]
                if (c == WIDE_PAD || c == 0) continue
                sb.appendCodePoint(c)
            }
            if (r != r1) sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    // ---- touch -------------------------------------------------------------------------

    fun cellHeight(): Int = cellHeightPx

    /** Ask for one more frame; the poll loop calls this only when the screen is dirty. */
    fun requestRedraw() {
        if (!forceRedraw) {
            forceRedraw = true
            postInvalidateOnAnimation()
        }
    }

    fun needsRedraw(): Boolean = forceRedraw

    fun installScaling() {
        val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = (cellHeightPx * detector.scaleFactor).toInt()
                setCellHeight(next)
                return true
            }
        })
        scaleDetector = detector
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector?.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastY = event.y
                scrollAtDown = screen.scrollbackOffset
                gesture = if (screen.scrollbackOffset > 0) Gesture.SCROLL else Gesture.NONE
                if (copyOnSelect) {
                    longPressScheduled = true
                    postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (gesture == Gesture.SELECT) {
                    selEndRow = rowAt(event.y)
                    selEndCol = colAt(event.x)
                    invalidate()
                    return true
                }
                val dy = lastY - event.y
                lastY = event.y
                if (gesture == Gesture.NONE && (event.y - downY) * (event.y - downY) > SCROLL_SLOP_PX * SCROLL_SLOP_PX) {
                    cancelPendingLongPress()
                    gesture = Gesture.SCROLL
                }
                if (gesture == Gesture.SCROLL) {
                    val lines = Math.round(dy / cellHeightPx)
                    if (lines != 0) {
                        if (scrollAtDown + lines > 0) {
                            screen.scrollUp(lines)
                            scrollAtDown += lines
                        } else {
                            screen.scrollToBottom()
                            scrollAtDown = 0
                        }
                        invalidate()
                    }
                    return true
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPendingLongPress()
                val wasSelecting = gesture == Gesture.SELECT
                gesture = Gesture.NONE
                if (wasSelecting) {
                    val text = if (copyOnSelect) selectedText(screen.snapshot()) else ""
                    selection = false
                    if (text.isNotEmpty()) onCopy?.invoke(text)
                    invalidate()
                } else if (screen.scrollbackOffset != 0) {
                    screen.scrollToBottom()
                    invalidate()
                }
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun cancelPendingLongPress() {
        if (longPressScheduled) {
            removeCallbacks(longPressRunnable)
            longPressScheduled = false
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun rowAt(y: Float): Int = (y / cellHeightPx).toInt().coerceIn(0, rows - 1)

    private fun colAt(x: Float): Int = (x / cellWidthPx).toInt().coerceIn(0, cols - 1)

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics,
    ).toInt()

    fun destroy() {
        cancelPendingLongPress()
        for (i in 0 until atlas.size()) atlas.valueAt(i).recycle()
        atlas.clear()
    }

    companion object {
        const val BACKGROUND = 0xFF101216.toInt()
        const val FOREGROUND = 0xFFD8DEE9.toInt()
        const val SELECTION = 0x55374A6B
        const val DEFAULT_CELL_DP = 16
        const val MIN_DP = 8
        const val MAX_DP = 32
        private const val GLYPH_HEIGHT_RATIO = 0.72f
        private const val MAX_ATLAS_ENTRIES = 4096
        private const val BOLD_LIFT = 40
        private const val DIM_FACTOR = 0.6f
        private const val LONG_PRESS_MS = 320L
        private const val SCROLL_SLOP_PX = 36f
    }
}
