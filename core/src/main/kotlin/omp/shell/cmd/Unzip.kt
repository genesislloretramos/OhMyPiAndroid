package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.fs.FsException
import omp.shell.fs.VNodeType
import omp.shell.fs.Vfs
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

@CommandSpec(
    name = "unzip",
    synopsis = "[-l] [-d dir] archive.zip",
    group = "files",
    notes = "every entry name is checked before anything is written, and an escaping one is refused outright",
)
object Unzip : FileCommand() {

    override val flagSpec = "l"
    override val valueSpec = "d:"

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /** One entry of the archive: its name, its timestamp, and the bytes it holds. */
    private class Member(val name: String, val time: Long, val bytes: ByteArray) {
        val isDirectory: Boolean get() = name.endsWith("/")
        val size: Long get() = bytes.size.toLong()
    }

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        if (operands.isEmpty()) return ctx.fail("unzip: missing archive")
        if (operands.size > 1) return ctx.fail("unzip: only one archive at a time")
        val archiveRaw = operands[0]
        val path = Cmds.resolve(ctx, archiveRaw) ?: return ExecContext.EXIT_GENERAL_ERROR
        val vfs = ctx.session.vfs
        if (Cmds.statFollowedOrNull(vfs, path)?.type != VNodeType.FILE) {
            ctx.errLine("unzip: $archiveRaw: No such file or directory")
            return ExecContext.EXIT_GENERAL_ERROR
        }
        // The archive is read through the seam in one go and the entries taken apart here, so an
        // archive that is not one is refused before anything on disk is touched.
        val members = try {
            read(vfs.readBytes(path))
        } catch (e: IOException) {
            return Errno.report(ctx, "unzip", archiveRaw, e)
        } catch (e: FsException) {
            return Errno.report(ctx, "unzip", archiveRaw, e)
        } catch (e: SecurityException) {
            return Errno.report(ctx, "unzip", archiveRaw, e)
        }
        for (member in members) {
            if (fsEntryEscapes(member.name)) {
                ctx.errLine("unzip: ${member.name}: refusing path traversal")
                return ExecContext.EXIT_GENERAL_ERROR
            }
        }
        if ('l' in flags) return list(ctx, archiveRaw, members)
        return extract(ctx, vfs, members, options["d"])
    }

    /**
     * Every entry with its bytes, in the order the archive stores them.
     *
     * A stream reader cannot tell an empty archive from a file that is not one: both hand back no
     * entry at all. `ZipFile` refused the second with `error in opening zip file`, and it was right
     * to — an archive opens with a local file header, an empty one with just the end-of-central-
     * directory record, and neither can be a text file. The same words are used so the diagnostic
     * is the one this has always printed.
     */
    private fun read(bytes: ByteArray): List<Member> {
        if (!looksLikeArchive(bytes)) throw ZipException("error in opening zip file")
        val out = ArrayList<Member>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val buffer = ByteArrayOutputStream()
                if (!entry.isDirectory) zip.copyTo(buffer)
                out += Member(entry.name, entry.time, buffer.toByteArray())
                entry = zip.nextEntry
            }
        }
        return out
    }

    /** `PK\003\004` a local file header, `PK\001\002` a central directory, `PK\005\006` an empty one. */
    private fun looksLikeArchive(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return false
        return when (bytes[2].toInt()) {
            1, 3 -> bytes[3].toInt() == 2 || bytes[3].toInt() == 4
            5 -> bytes[3].toInt() == 6
            else -> false
        }
    }

    private fun list(ctx: ExecContext, archiveRaw: String, members: List<Member>): Int {
        ctx.outLine("Archive:  $archiveRaw")
        ctx.outLine("  Length      Date    Time    Name")
        ctx.outLine("---------  ---------- -----   ----")
        var total = 0L
        var files = 0
        var folders = 0
        for (member in members) {
            if (member.isDirectory) {
                folders++
                ctx.outLine("        0    ${stamp.format(Date(member.time))}   ${member.name}")
            } else {
                files++
                total += member.size
                ctx.outLine(String.format(Locale.US, "%9d  %s   %s", member.size, stamp.format(Date(member.time)), member.name))
            }
        }
        ctx.outLine("---------                     -------")
        val summary = StringBuilder()
        summary.append(String.format(Locale.US, "%9d                     ", total))
        val parts = ArrayList<String>()
        if (files > 0) parts.add("$files ${plural(files, "file", "files")}")
        if (folders > 0) parts.add("$folders ${plural(folders, "folder", "folders")}")
        summary.append(if (parts.isEmpty()) "0 files" else parts.joinToString(", "))
        ctx.outLine(summary.toString())
        return ExecContext.EXIT_OK
    }

    private fun plural(n: Int, one: String, many: String) = if (n == 1) one else many

    private fun extract(ctx: ExecContext, vfs: Vfs, members: List<Member>, destRaw: String?): Int {
        val root: String = if (destRaw == null) {
            ctx.session.cwd
        } else {
            Cmds.resolve(ctx, destRaw) ?: return ExecContext.EXIT_GENERAL_ERROR
        }
        if (!Cmds.isDirFollowed(vfs, root) && !fsMakeDirs(vfs, root)) {
            ctx.errLine("unzip: $destRaw: could not create the destination directory")
            return ExecContext.EXIT_GENERAL_ERROR
        }
        var status = ExecContext.EXIT_OK
        for (member in members) {
            if (ctx.cancelled.get()) return ExecContext.EXIT_INTERRUPTED
            val out = fsChild(root, member.name)
            if (member.isDirectory) {
                if (!fsMakeDirs(vfs, out)) {
                    ctx.errLine("unzip: ${member.name}: could not create the directory")
                    status = ExecContext.EXIT_GENERAL_ERROR
                }
                continue
            }
            if (!fsMakeDirs(vfs, out.substringBeforeLast('/', root))) {
                ctx.errLine("unzip: ${member.name}: could not create the directory")
                status = ExecContext.EXIT_GENERAL_ERROR
                continue
            }
            try {
                vfs.writeBytes(out, member.bytes)
                ctx.outLine("  inflating: ${member.name}")
            } catch (e: FsException) {
                ctx.errLine("unzip: ${member.name}: ${e.errno.text}")
                status = ExecContext.EXIT_GENERAL_ERROR
            } catch (e: SecurityException) {
                ctx.errLine("unzip: ${member.name}: Permission denied")
                status = ExecContext.EXIT_GENERAL_ERROR
            }
        }
        return status
    }
}
