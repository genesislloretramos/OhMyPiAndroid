package omp.shell.cmd

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

@CommandSpec(
    name = "file",
    synopsis = "file ...",
    group = "files",
    notes = "reads the first 16 bytes and reports what it checked; anything unrecognised is reported as data",
)
object FileCmd : Command {

    private const val SNIFF = 16

    override fun run(ctx: ExecContext): Int {
        if (ctx.args.isEmpty()) return ctx.fail("file: missing operand")
        var status = ExecContext.EXIT_OK
        for (op in ctx.args) {
            val path = Cmds.resolve(ctx, op) ?: run {
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            val file = File(path)
            val attrs = try {
                Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (e: IOException) {
                ctx.errLine("file: $op: ${Errno.messageFor(e)}")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            } catch (e: SecurityException) {
                ctx.errLine("file: $op: Permission denied")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            ctx.outLine("$op: ${describe(file, attrs)}")
        }
        return status
    }

    private fun describe(file: File, attrs: BasicFileAttributes): String {
        if (attrs.isDirectory) return "directory"
        if (attrs.isSymbolicLink) {
            val target = fsLinkTarget(file) ?: "unknown"
            return "symbolic link to $target"
        }
        if (attrs.size() == 0L) return "empty"
        val head = readHead(file) ?: return "data"
        val extName = extensionOf(file.name)
        val byName = if (extName != null) byExtension(extName) else null
        // The magic byte decides the family; the extension only refines it. Guessing from the name
        // first would claim a type the bytes do not support.
        val sniffed = sniff(head)
        val base = if (sniffed != null) refine(sniffed, extName) else null
        if (base != null) return base
        if (byName != null) return byName
        return textKind(head, file) ?: "data"
    }

    private fun readHead(file: File): ByteArray? = try {
        FileInputStream(file).use { input ->
            val buf = ByteArray(SNIFF)
            var read = 0
            while (read < SNIFF) {
                val n = input.read(buf, read, SNIFF - read)
                if (n < 0) break
                read += n
            }
            if (read <= 0) ByteArray(0) else buf.copyOf(read)
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    private fun sniff(head: ByteArray): String? {
        if (startsWith(head, 0x7F, 0x45, 0x4C, 0x46)) return elf(head)
        if (startsWith(head, 0x50, 0x4B, 0x03, 0x04)) return "Zip archive data"
        if (startsWith(head, 0x50, 0x4B, 0x05, 0x06)) return "Zip archive data (empty)"
        if (startsWith(head, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return "PNG image data"
        if (startsWith(head, 0xFF, 0xD8, 0xFF)) return "JPEG image data"
        if (startsWith(head, 0x1F, 0x8B)) return "gzip compressed data"
        if (startsWith(head, 0x42, 0x5A, 0x68)) return "bzip2 compressed data"
        if (startsWith(head, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)) return "XZ compressed data"
        if (startsWith(head, 0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66, 0x6F, 0x72, 0x6D, 0x61, 0x74, 0x20, 0x33)) {
            return "SQLite 3.x database"
        }
        if (startsWith(head, 0x64, 0x65, 0x78, 0x0A)) return "Dalvik dex file, version ${dexVersion(head)}"
        if (startsWith(head, 0x25, 0x50, 0x44, 0x46)) return "PDF document"
        if (startsWith(head, 0x47, 0x49, 0x46, 0x38)) return "GIF image data"
        if (startsWith(head, 0x00, 0x61, 0x73, 0x6D)) return "WebAssembly binary module"
        if (startsWith(head, 0xCA, 0xFE, 0xBA, 0xBE)) return "Java class data"
        if (startsWith(head, 0x21)) return null // a comment or a shebang: text is decided by the bytes
        return null
    }

    private fun elf(head: ByteArray): String {
        val bits = when (at(head, 4)) {
            1 -> "32-bit"
            2 -> "64-bit"
            else -> "ELF"
        }
        val endian = when (at(head, 5)) {
            1 -> "LSB"
            2 -> "MSB"
            else -> ""
        }
        val kind = if (head.size < 18) "object" else when (le16(head, 16)) {
            1 -> "relocatable"
            2 -> "executable"
            3 -> "pie executable"
            else -> "object"
        }
        return "ELF $bits $endian $kind".replace("  ", " ").trim()
    }

    private fun dexVersion(head: ByteArray): String {
        val v = buildString {
            for (i in 4 until minOf(7, head.size)) {
                val c = at(head, i)
                if (c in 0x30..0x39) append(c.toChar()) else return "035"
            }
        }
        return if (v.isEmpty()) "035" else v
    }

    private fun refine(family: String, extension: String?): String {
        if (family != "Zip archive data") return family
        return when (extension) {
            "jar" -> "Java archive data (JAR)"
            "apk" -> "Android package (APK)"
            "docx", "xlsx", "pptx", "odt", "ods", "odp" -> "Zip archive data, $extension container"
            else -> family
        }
    }

    private fun extensionOf(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        return name.substring(dot + 1).lowercase()
    }

    private fun byExtension(extension: String): String? = when (extension) {
        "txt", "log", "md", "ini", "conf", "cfg", "list" -> "ASCII text"
        "sh", "bash", "zsh" -> "shell script, ASCII text"
        "py" -> "Python script, ASCII text"
        "json" -> "JSON text data"
        "xml" -> "XML text data"
        "html", "htm" -> "HTML document text data"
        "csv" -> "CSV text"
        "c", "h", "java", "kt", "kts", "go", "rs", "cpp", "cc" -> "source text"
        "tar" -> "POSIX tar archive"
        "so" -> "ELF shared object"
        "zip" -> "Zip archive data"
        "gz", "tgz" -> "gzip compressed data"
        "bz2" -> "bzip2 compressed data"
        "xz" -> "XZ compressed data"
        "7z" -> "7-zip archive data"
        "rar" -> "RAR archive data"
        "mp3" -> "MPEG audio"
        "mp4", "mkv" -> "MPEG-4 video"
        "wav" -> "Waveform audio"
        "ogg" -> "Ogg container"
        "apk" -> "Android package (APK)"
        "jar" -> "Java archive data (JAR)"
        "class" -> "Java class data"
        "dex" -> "Dalvik dex file"
        "db", "sqlite" -> "SQLite 3.x database"
        else -> null
    }

    private fun textKind(head: ByteArray, file: File): String? {
        var ascii = true
        for (b in head) {
            val v = b.toInt() and 0xFF
            val printable = v in 0x20..0x7E || v == 0x09 || v == 0x0A || v == 0x0D || v == 0x1B || v == 0x0C
            if (!printable) {
                ascii = false
                break
            }
        }
        val kind = when {
            ascii -> "ASCII text"
            isValidUtf8(head) -> "Unicode text, UTF-8 text"
            else -> null
        } ?: return null
        return if (file.canExecute()) "$kind executable" else kind
    }

    /** Decodes only a complete prefix, so a code point cut in half by the sniff window is not a claim. */
    private fun isValidUtf8(head: ByteArray): Boolean {
        var end = head.size
        while (end > 0) {
            val text = try {
                String(head, 0, end, Charsets.UTF_8)
            } catch (e: Exception) {
                return false
            }
            if (text.toByteArray(Charsets.UTF_8).contentEquals(head.copyOfRange(0, end))) {
                for (c in text) {
                    if (c.code <= 0x1F || c.code == 0x7F) return false
                }
                return true
            }
            end--
        }
        return false
    }

    private fun startsWith(head: ByteArray, vararg bytes: Int): Boolean {
        if (head.size < bytes.size) return false
        for (i in bytes.indices) {
            if ((head[i].toInt() and 0xFF) != bytes[i]) return false
        }
        return true
    }

    private fun at(head: ByteArray, index: Int): Int = if (index < head.size) head[index].toInt() and 0xFF else -1

    private fun le16(head: ByteArray, index: Int): Int {
        if (index + 1 >= head.size) return 0
        val lo = head[index].toInt() and 0xFF
        val hi = head[index + 1].toInt() and 0xFF
        return if (at(head, 5) == 2) (lo shl 8) or hi else (hi shl 8) or lo
    }
}
