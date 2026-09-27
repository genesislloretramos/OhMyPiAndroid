package omp.vm.provision

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * A [Transport] with no socket in it, and a tar writer to feed it.
 *
 * **A mock is worthless for a download and this is not one.** Everything interesting about the
 * path this layer walks happens in bytes and in offsets: a body that stops half way, a server that
 * answers a `Range` with 200 and the whole file, a `sha256` that does not match, a tar member
 * called `../escape`. None of those exist in a return value, and a fake that answered "here are
 * your bytes" could not express any of them, which is why [open] here is a real two-call seam with
 * a real [Transport.Connection] behind it — the same shape `HttpTransport` implements.
 */
class FakeTransport : Transport {

    /** url to the bytes the "server" has for it. */
    val bodies = HashMap<String, ByteArray>()

    /** Every `(url, from)` this was asked for, in order: the record of what a resume asked for. */
    val requests = ArrayList<Pair<String, Long>>()

    /** The status to answer with. 200 for a whole body, 206 for a range, anything else is a failure. */
    var status: Int = 200

    /** Stop the body after this many bytes, -1 for all of it. How an interrupted download is written. */
    var stopAfter: Long = -1L

    /** Answer a ranged request with the whole file, the way a server that ignores `Range` does. */
    var ignoreRange: Boolean = false

    /** Fail the request outright, before a connection exists. */
    var failWith: IOException? = null

    /** Throw out of the body after this many bytes, the way a radio that loses the call does. */
    var breakAfter: Int = -1

    /** Zero this many bytes at the end of the body, which is a gzip stream that ends early. */
    var corruptTail: Int = 0

    override fun open(url: String, from: Long): Transport.Connection {
        requests += url to from
        failWith?.let { throw it }
        val body = bodies[url] ?: throw IOException("no body for $url")
        val start = if (ignoreRange) 0 else minOf(from, body.size.toLong()).toInt()
        val end = if (stopAfter < 0) body.size else minOf(body.size.toLong(), start + stopAfter).toInt()
        val served = body.copyOfRange(start, end)
        if (corruptTail > 0) {
            for (i in served.size - corruptTail until served.size) served[i] = 0
        }
        val ranged = from > 0 && start.toLong() == from
        val limit = if (breakAfter < 0) served.size else minOf(served.size, breakAfter)
        return object : Transport.Connection {
            override val status: Int = if (ranged) 206 else this@FakeTransport.status
            override val from: Long = if (ranged) start.toLong() else 0L
            override val totalBytes: Long? = body.size.toLong()
            override fun body(): InputStream = object : InputStream() {
                private var sent = 0

                /** A finished body ends; a body the radio dropped does not, and says so. */
                private fun end(): Int {
                    if (breakAfter < 0) return -1
                    throw IOException("connection reset")
                }

                override fun read(): Int {
                    if (sent >= limit) return end()
                    sent++
                    return served[sent - 1].toInt() and 0xff
                }

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (sent >= limit) return end()
                    val count = minOf(length, limit - sent)
                    System.arraycopy(served, sent, bytes, offset, count)
                    sent += count
                    return count
                }
            }

            override fun close() = Unit
        }
    }
}

/**
 * A gzip'd tar, written by hand, because there is no tar library in this project and there is not
 * going to be one.
 *
 * The header checksum is written as spaces and never computed, which is deliberate and is the one
 * thing a reader is not being asked to get right: [TarReader] does not look at it, because every
 * archive that reaches it has already been checked against a digest or an exact length, and a
 * header that survived that cannot be corrupt. A member can be given a type flag, a mode and a
 * symlink target, which is how the `../` and absolute-name cases are built from the same writer
 * as the honest ones.
 */
object Tar {

    /** A regular file, [mode] and all. */
    fun file(name: String, body: ByteArray, mode: Int = 0x1A4): ByteArray =
        member(name, body, '0', mode)

    /** A directory: no body, and the trailing slash a tar uses for one. */
    fun directory(name: String, mode: Int = 0x1ED): ByteArray =
        member(if (name.endsWith("/")) name else "$name/", ByteArray(0), '5', mode)

    /** A symlink: the body is empty and the target is the header's link field. */
    fun symlink(name: String, target: String): ByteArray =
        member(name, ByteArray(0), '2', 0x1FF, link = target)

    /** A member whose real name is in its body, the way GNU tar writes a path over 100 bytes. */
    fun longName(realName: String, body: ByteArray, mode: Int = 0x1A4): ByteArray {
        val nameBytes = realName.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        val truncated = if (realName.length > 100) realName.substring(0, 100) else realName
        return header(truncated, nameBytes.size.toLong(), 'L', 0x1A4) + pad(nameBytes) +
            file(realName, body, mode)
    }

    /** The whole archive, gzipped, with the two zero blocks that end one. */
    fun gz(vararg members: ByteArray): ByteArray {
        val raw = ByteArrayOutputStream()
        for (member in members) raw.write(member)
        raw.write(ByteArray(1024))
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(raw.toByteArray()) }
        return gz.toByteArray()
    }

    private fun member(
        name: String,
        body: ByteArray,
        type: Char,
        mode: Int,
        link: String = "",
    ): ByteArray = header(name, body.size.toLong(), type, mode, link) + pad(body)

    private fun header(name: String, size: Long, type: Char, mode: Int, link: String = ""): ByteArray {
        val block = ByteArray(512)
        write(block, 0, 100, name)
        writeOctal(block, 100, 8, mode)
        writeOctal(block, 108, 8, 0)
        writeOctal(block, 116, 8, 0)
        writeOctal(block, 124, 12, size.toInt())
        writeOctal(block, 136, 12, 0)
        block[156] = type.code.toByte()
        write(block, 157, 100, link)
        write(block, 257, 6, "ustar")
        return block
    }

    private fun pad(body: ByteArray): ByteArray {
        val slack = (512 - body.size % 512) % 512
        return body + ByteArray(slack)
    }

    private fun write(block: ByteArray, offset: Int, length: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(length - 1, bytes.size)) block[offset + i] = bytes[i]
    }

    private fun writeOctal(block: ByteArray, offset: Int, length: Int, value: Int) {
        val text = value.toString(8).padStart(length - 1, '0')
        write(block, offset, length, text)
    }
}

/** The sha256 of [bytes], lower-case hex — the same string the manifest pins for the real agent. */
fun sha256Of(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(bytes)
    return buildString(64) {
        for (byte in digest.digest()) {
            val value = byte.toInt() and 0xff
            append("0123456789abcdef"[value shr 4])
            append("0123456789abcdef"[value and 0x0f])
        }
    }
}
