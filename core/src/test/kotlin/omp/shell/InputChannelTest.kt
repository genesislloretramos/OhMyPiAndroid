package omp.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The channel is read two different ways — blocking by `cat`/`less`, polled by `top` — and a
 * command that polls must see a key that has already arrived.
 */
class InputChannelTest {

    private fun channel() = InputChannel()

    @Test
    fun readByteDeliversFedBytesInOrder() {
        val c = channel()
        c.feed("ab".toByteArray())
        assertEquals('a'.code, c.readByte())
        assertEquals('b'.code, c.readByte())
    }

    @Test
    fun availableReportsBytesThatAreStillInTheQueue() {
        val c = channel()
        c.feed("q".toByteArray())
        val stream = c.asInputStream()
        // The byte is in the blocking queue, not the read buffer; available() has to move it.
        assertEquals(1, stream.available())
        assertEquals('q'.code, stream.read())
        assertEquals(0, stream.available())
    }

    @Test
    fun anInterruptedByteCallsTheHook() {
        val c = channel()
        var fired = 0
        c.onInterrupt = { fired++ }
        c.feed(byteArrayOf('a'.code.toByte(), CTRL_C, 'b'.code.toByte()))
        assertEquals(1, fired)
    }

    @Test
    fun aSplitEscapeSequenceSurvivesTheHandover() {
        val c = channel()
        c.feed(byteArrayOf(0x1B))
        assertEquals(0x1B, c.readByte())
        c.feed("[A".toByteArray())
        val stream = c.asInputStream()
        assertEquals(2, stream.available())
        assertEquals('['.code, stream.read())
        assertEquals('A'.code, stream.read())
    }

    @Test
    fun closeEndsTheReadWithMinusOne() {
        val c = channel()
        c.close()
        assertEquals(-1, c.readByte())
        assertEquals(-1, c.asInputStream().read())
    }

    @Test
    fun aLongSequenceIsSplitAcrossFeedsWithoutLoss() {
        val c = channel()
        val text = "ls -l /proc | head -3"
        for (ch in text) {
            c.feed(ch.toString().toByteArray())
        }
        val stream = c.asInputStream()
        val read = StringBuilder()
        while (stream.available() > 0) read.append(stream.read().toChar())
        assertEquals(text, read.toString())
    }
}

private const val CTRL_C: Byte = 3
