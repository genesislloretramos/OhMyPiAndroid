package com.omp.terminal

import android.view.KeyEvent

enum class Modifier {
    NONE, CTRL, ALT
}

/**
 * The single place a key becomes bytes, so the extra-keys bar and the hardware
 * keyboard cannot drift apart. Control codes are applied here rather than taken
 * from the keymap, because OEM keymaps do not always report them.
 */
object InputEncoder {

    private val NO_BYTES = ByteArray(0)
    private val ESC = 0x1B.toByte()

    private val SHIFT_META =
        KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_RIGHT_ON

    fun encode(event: KeyEvent): ByteArray {
        val bytes = baseBytes(event) ?: return NO_BYTES
        return if (event.isAltPressed || event.isMetaPressed) byteArrayOf(ESC, *bytes) else bytes
    }

    fun encodeArmed(mod: Modifier, text: String): ByteArray {
        if (text.isEmpty()) return NO_BYTES
        // The bar emits one key per tap, but an IME insertion can carry a whole string.
        val codePoint = text.codePointAt(0)
        val bytes = (if (mod == Modifier.CTRL) ctrlBytes(codePoint) else charBytes(codePoint))
            ?: return NO_BYTES
        return if (mod == Modifier.ALT) byteArrayOf(ESC, *bytes) else bytes
    }

    private fun baseBytes(event: KeyEvent): ByteArray? {
        val sequence = sequenceFor(event.keyCode)
        if (sequence != null) return sequence.toByteArray(Charsets.ISO_8859_1)
        if (event.isCtrlPressed) {
            // With Ctrl held the keymap reports no printable character, so the letter has to come
            // from the keycode: Ctrl-A..Ctrl-Z are the same on every layout.
            ctrlFromKeyCode(event.keyCode)?.let { return byteArrayOf(it) }
        }
        // Shift is honoured from the event; Ctrl and Alt are this object's business.
        val codePoint = event.getUnicodeChar(event.metaState and SHIFT_META)
        return if (event.isCtrlPressed) ctrlBytes(codePoint) else charBytes(codePoint)
    }

    private fun ctrlFromKeyCode(keyCode: Int): Byte? = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
            ((keyCode - KeyEvent.KEYCODE_A + 1) and 0xFF).toByte()

        KeyEvent.KEYCODE_SPACE -> 0x00
        KeyEvent.KEYCODE_2 -> 0x00.toByte() // Ctrl-@ ; kept so Ctrl+2 is not silently dropped
        KeyEvent.KEYCODE_6 -> 0x1E.toByte() // Ctrl-^
        KeyEvent.KEYCODE_MINUS -> 0x1F.toByte() // Ctrl-_
        else -> null
    }

    private fun charBytes(codePoint: Int): ByteArray? = when {
        codePoint == 0 -> null
        codePoint < 0x80 -> byteArrayOf(codePoint.toByte())
        else -> String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
    }

    /** The xterm control codes; anything outside this table produces no bytes at all. */
    private fun ctrlBytes(codePoint: Int): ByteArray? {
        val control = when (codePoint) {
            in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
            in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
            ' '.code -> 0x00
            '['.code -> 0x1B
            '\\'.code -> 0x1C
            ']'.code -> 0x1D
            '^'.code -> 0x1E
            '_'.code -> 0x1F
            else -> return null
        }
        return byteArrayOf(control.toByte())
    }

    // Arrows, home/end and the two delete keys are the DPAD, MOVE and FORWARD_DEL spellings the SDK
    // stub actually carries; they are the same key codes as UP/LEFT/RIGHT/DOWN/END/BACKSPACE/DELETE.
    private fun sequenceFor(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER -> "\r"
        KeyEvent.KEYCODE_TAB -> "\t"
        KeyEvent.KEYCODE_DEL -> "\u007F"
        KeyEvent.KEYCODE_ESCAPE -> "\u001B"
        KeyEvent.KEYCODE_INSERT -> "\u001B[2~"
        KeyEvent.KEYCODE_FORWARD_DEL -> "\u001B[3~"
        KeyEvent.KEYCODE_DPAD_UP -> "\u001B[A"
        KeyEvent.KEYCODE_DPAD_DOWN -> "\u001B[B"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001B[C"
        KeyEvent.KEYCODE_DPAD_LEFT -> "\u001B[D"
        KeyEvent.KEYCODE_HOME -> "\u001B[H"
        KeyEvent.KEYCODE_MOVE_END -> "\u001B[F"
        KeyEvent.KEYCODE_PAGE_UP -> "\u001B[5~"
        KeyEvent.KEYCODE_PAGE_DOWN -> "\u001B[6~"
        // SS3, then the xterm F5..F12 numbers.
        KeyEvent.KEYCODE_F1 -> "\u001BOP"
        KeyEvent.KEYCODE_F2 -> "\u001BOQ"
        KeyEvent.KEYCODE_F3 -> "\u001BOR"
        KeyEvent.KEYCODE_F4 -> "\u001BOS"
        KeyEvent.KEYCODE_F5 -> "\u001B[15~"
        KeyEvent.KEYCODE_F6 -> "\u001B[17~"
        KeyEvent.KEYCODE_F7 -> "\u001B[18~"
        KeyEvent.KEYCODE_F8 -> "\u001B[19~"
        KeyEvent.KEYCODE_F9 -> "\u001B[20~"
        KeyEvent.KEYCODE_F10 -> "\u001B[21~"
        KeyEvent.KEYCODE_F11 -> "\u001B[23~"
        KeyEvent.KEYCODE_F12 -> "\u001B[24~"
        else -> null
    }
}
