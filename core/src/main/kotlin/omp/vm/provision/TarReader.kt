package omp.vm.provision

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * One member of a tar archive, as the header describes it.
 *
 * [type] is the tar type flag as a character, because the interesting members are the ones that are
 * *not* a regular file: a Debian rootfs is a few thousand symlinks, and an unpacker that wrote every
 * member as a file would turn `/bin` into a 4 KB file of a path.
 */
class TarEntry(
    val name: String,
    val size: Long,
    val type: Char,
    val mode: Int,
    val linkName: String,
) {
    /** A NUL flag is the old-style spelling of a regular file, and is what an old tar writes. */
    val isFile: Boolean get() = type == '0' || type.code == NUL

    val isDirectory: Boolean get() = type == '5'
    val isSymlink: Boolean get() = type == '2'

    /**
     * The member's path relative to the directory being unpacked into, or null when it would leave
     * it — an absolute name, or one with a `..` component in it.
     *
     * **A tar member named `../../files/agent/openai.key` is a real thing**, not a hypothetical: it
     * is what an archive built by a tool with a bug, or by somebody who wanted it, looks like on
     * the wire, and it is invisible in `tar tzf` on a desktop that resolves the path for you. An
     * unpacker that concatenates the member name onto a target directory hands it whatever the name
     * says, so the check belongs at the one place the two strings meet — here — and the answer is
     * null rather than an exception, so the caller can name the member it refused in a report
     * instead of unwinding with a stack trace a user will never read.
     */
    fun relativeName(): String? {
        if (name.isEmpty() || name.startsWith("/")) return null
        val parts = name.split('/')
        if (parts.any { it == ".." }) return null
        return parts.filter { it.isNotEmpty() }.joinToString("/").ifEmpty { null }
    }

    internal companion object {
        /** The NUL type flag, the original tar format's way of saying a regular file. */
        const val NUL = 0
    }
}

/**
 * A tar reader that streams, and the archive extensions a Debian rootfs actually uses.
 *
 * **It is a reader, not a library, and it never holds a member in memory.** A rootfs is a few
 * thousand members and a couple of hundred megabytes behind them, and the whole reason the JVM is
 * the right place to do this is that a stream can be fed through a 64 KiB buffer a thousand times.
 * [next] returns one header at a time and [copyContentTo] moves that member's bytes and no others;
 * a caller that stops reading early is not left with a stream positioned in the wrong place,
 * because [next] drains whatever is left of the current member first.
 *
 * **The extensions are not optional.** A member whose path is longer than 100 characters — and a
 * Debian rootfs is full of them, under `usr/lib/<triplet>/` and `usr/share/` — is written by GNU
 * tar as an `'L'` member whose *body* is the real name, followed by a header carrying the
 * truncated one; a pax archive writes an `'x'` member with a `path=` record instead. Both are
 * handled, because a reader that quietly truncated those names would create a directory named after
 * the first 100 characters and then fail on the member after it, which is a bug report about a
 * rootfs that is "corrupted" and a cause nobody can see from the phone. `'K'` is the same trick for
 * a long symlink target and is handled for the same reason.
 *
 * A member that needs a GNU base-256 size (anything over 8 GB) is refused with a named error rather
 * than misread: the high bit means the field is not octal, and a reader that assumed it was would
 * produce a plausible wrong length.
 */
class TarReader(private val input: InputStream) : Closeable {

    private var remaining = 0L
    private var padding = 0L

    /** @return the next member, or null at the end-of-archive marker. */
    fun next(): TarEntry? {
        drain()
        var header = header() ?: return null
        var name = text(header, 0, 100)
        var link = text(header, 157, 100)
        var type = flag(header)
        if (type == GNU_LONG_NAME || type == GNU_LONG_LINK || type == PAX_EXTENDED) {
            val body = metadata(header)
            when (type) {
                GNU_LONG_NAME -> name = trimFiller(body)
                GNU_LONG_LINK -> link = trimFiller(body)
                else -> paxPath(body)?.let { name = it }
            }
            drain()
            header = header() ?: throw IOException("tar: a header member is the last one in the archive")
            type = flag(header)
        }
        val entry = TarEntry(
            name = name,
            size = octal(header, 124, 12, "size"),
            type = type,
            mode = octal(header, 100, 8, "mode").toInt(),
            linkName = link,
        )
        remaining = entry.size
        padding = (BLOCK - entry.size % BLOCK) % BLOCK
        return entry
    }

    /** Copies the current member's bytes to [sink] and leaves the reader on the next header. */
    fun copyContentTo(sink: OutputStream) {
        val buffer = ByteArray(BUFFER)
        var moved = 0L
        while (moved < remaining) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining - moved).toInt())
            if (read < 0) throw EOFException("tar: the archive ends in the middle of a member")
            sink.write(buffer, 0, read)
            moved += read
        }
        remaining = 0L
    }

    /** Skips the rest of the current member, for a caller that decided it did not want it. */
    fun skip() {
        drain()
    }

    override fun close() {
        input.close()
    }

    /** Consumes what is left of the current member and the padding to the next 512-byte boundary. */
    private fun drain() {
        val buffer = ByteArray(BUFFER)
        var left = remaining + padding
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) return
            left -= read
        }
        remaining = 0L
        padding = 0L
    }

    /** @return one 512-byte header block, or null at the zero block that ends every archive. */
    private fun header(): ByteArray? {
        val block = ByteArray(BLOCK)
        var filled = 0
        while (filled < BLOCK) {
            val read = input.read(block, filled, BLOCK - filled)
            if (read < 0) {
                if (filled == 0) return null
                throw EOFException("tar: the archive ends in the middle of a header")
            }
            filled += read
        }
        if (block.all { it == 0.toByte() }) return null
        return block
    }

    /**
     * Reads a member's whole body, for the header types whose body *is* the metadata: a name, a
     * link target, a pax record. Capped, because a member claiming to be 4 GB is not a name.
     */
    private fun metadata(header: ByteArray): String {
        val size = octal(header, 124, 12, "size")
        if (size < 0 || size > MAX_METADATA) {
            throw IOException("tar: a header member claims $size bytes, which is not a name")
        }
        val bytes = ByteArray(size.toInt())
        var filled = 0
        while (filled < bytes.size) {
            val read = input.read(bytes, filled, bytes.size - filled)
            if (read < 0) throw EOFException("tar: the archive ends inside a header member")
            filled += read
        }
        padding = (BLOCK - size % BLOCK) % BLOCK
        return String(bytes, Charsets.UTF_8)
    }

    private fun text(block: ByteArray, offset: Int, length: Int): String {
        val end = minOf(offset + length, block.size)
        return trimFiller(String(block, offset, end - offset, Charsets.UTF_8))
    }

    private fun flag(block: ByteArray): Char {
        val raw = block[156].toInt() and 0xff
        return if (raw == TarEntry.NUL) '0' else raw.toChar()
    }

    private fun octal(block: ByteArray, offset: Int, length: Int, what: String): Long {
        if ((block[offset].toInt() and 0x80) != 0) {
            throw IOException("tar: $what is a GNU base-256 value, which this reader does not accept")
        }
        var value = 0L
        var seen = false
        for (i in offset until minOf(offset + length, block.size)) {
            val byte = block[i].toInt() and 0xff
            if (byte == 0 || byte == ' '.code) {
                if (seen) break
                continue
            }
            if (byte < '0'.code || byte > '7'.code) {
                throw IOException("tar: $what is not an octal number")
            }
            value = value * 8 + (byte - '0'.code)
            seen = true
        }
        return value
    }

    /** The `path=` record of a pax extended header, which is the only one this reader looks for. */
    private fun paxPath(body: String): String? = body.split('\n')
        .firstOrNull { it.startsWith("path=") }
        ?.removePrefix("path=")
        ?.trim { it.code == TarEntry.NUL }
        ?.ifEmpty { null }

    private fun trimFiller(value: String): String = value.trim { it.code == TarEntry.NUL || it == ' ' }

    private companion object {
        const val BLOCK = 512
        const val BUFFER = 64 * 1024
        const val MAX_METADATA = 1L * 1024 * 1024
        const val GNU_LONG_NAME = 'L'
        const val GNU_LONG_LINK = 'K'
        const val PAX_EXTENDED = 'x'
    }
}
