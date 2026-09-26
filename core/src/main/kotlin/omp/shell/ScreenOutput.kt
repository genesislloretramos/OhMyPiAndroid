package omp.shell

import omp.term.Screen
import java.io.OutputStream

/**
 * Adapts the terminal screen to an [OutputStream] so every command writes through the same stdout
 * contract whether it is talking to the screen or to a pipe.
 *
 * Two jobs beyond plumbing. Whole buffers go straight to the screen; single bytes are held until
 * [flush] so a split UTF-8 character is never decoded twice. And `\n` becomes `\r\n`, which is what
 * a pty's ONLCR does: [Screen] is the terminal, not the tty, so a bare line feed would stair-step
 * every line to the right. The line editor writes to the screen directly and emits its own CR, so it
 * does not come through here and is never translated twice.
 */
class ScreenOutput(private val screen: Screen) : OutputStream() {

    private var pending: ByteArray? = null
    private var lastWasCR = false

    override fun write(b: Int) {
        val held = pending
        if (held == null) {
            pending = byteArrayOf(b.toByte())
            return
        }
        screen.write(held)
        pending = byteArrayOf(b.toByte())
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        val held = pending
        if (held == null) {
            emit(b, off, len)
            return
        }
        val merged = ByteArray(held.size + len)
        System.arraycopy(held, 0, merged, 0, held.size)
        System.arraycopy(b, off, merged, held.size, len)
        pending = null
        emit(merged, 0, merged.size)
    }

    override fun flush() {
        pending?.let {
            pending = null
            emit(it, 0, it.size)
        }
    }

    private fun emit(b: ByteArray, off: Int, len: Int) {
        if (len == b.size && off == 0 && !needsTranslation(b, len)) {
            lastWasCR = b.isNotEmpty() && b[b.size - 1] == CR
            screen.write(b)
            return
        }
        val out = ArrayList<Byte>(len + 8)
        for (i in off until off + len) {
            val c = b[i]
            if (c == LF && !lastWasCR) out.add(CR)
            out.add(c)
            lastWasCR = c == CR
        }
        lastWasCR = lastWasCR || out.isEmpty()
        screen.write(out.toByteArray())
    }

    private fun needsTranslation(b: ByteArray, len: Int): Boolean {
        for (i in 0 until len) {
            if (b[i] == LF && (i == 0 || b[i - 1] != CR)) return true
        }
        return false
    }

    private companion object {
        const val CR: Byte = 0x0D
        const val LF: Byte = 0x0A
    }
}
