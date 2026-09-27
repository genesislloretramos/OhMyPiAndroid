package omp.vm

import omp.shell.exec.Command
import omp.shell.exec.CommandTable
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.VNodeType
import omp.shell.fs.Vfs

/**
 * The VM's exec path: what it takes for a file in the rootfs to be a program.
 *
 * A file is a program when its first line is exactly [SHEBANG] + a name — the same idea as
 * `#!`, with a marker that cannot collide with a real kernel shebang because there is no kernel
 * here to read one. The name is then looked up in the VM's [CommandTable], so `/usr/bin/ls` is a
 * real, listable, `stat`-able file in the rootfs that happens to run the same Kotlin `Ls` the
 * phone runs.
 *
 * Anything else reached as a command is [FsErrno.INVALID_ARGUMENT] with the wording
 * `sh: <path>: cannot execute binary file: Exec format error`, which is exactly what the phone says
 * for a file it cannot run. Nothing is executed: there is no `Runtime.exec` here, no NDK and no
 * ELF, and a program file is a *routing* decision, never a permission to run host code.
 */
object VmExec {

    /** The first line of a program file. Deliberately not a real `#!` shebang. */
    const val SHEBANG = "#!omp/v1 program "

    /** The diagnostic a non-program file gets, verbatim, so a user sees one wording in both places. */
    const val NOT_A_PROGRAM = "cannot execute binary file: Exec format error"

    /** True when [path] is a regular file whose first line names a program. */
    fun isProgramFile(vfs: Vfs, path: String): Boolean = programName(vfs, path) != null

    /** @return the program name, or null when the file is absent or not a program. */
    fun programName(vfs: Vfs, path: String): String? {
        val real = try {
            vfs.realpath(path)
        } catch (e: FsException) {
            return null
        }
        val stat = try {
            vfs.stat(real)
        } catch (e: FsException) {
            return null
        }
        if (stat.type != VNodeType.FILE) return null
        val line = try {
            firstLine(vfs, real)
        } catch (e: FsException) {
            return null
        }
        if (!line.startsWith(SHEBANG)) return null
        val name = line.substring(SHEBANG.length).trim()
        return name.ifEmpty { null }
    }

    /**
     * The command a program file runs. A program that names something the VM's table does not have
     * is [FsErrno.NO_SUCH_FILE] naming the *program*, not the file, because "no such command" is
     * what a user can act on.
     */
    fun lookup(vfs: Vfs, table: CommandTable, path: String): Command {
        val name = programName(vfs, path)
            ?: throw FsException(FsErrno.INVALID_ARGUMENT, "$path: $NOT_A_PROGRAM")
        return table.lookup(name) ?: throw FsException(FsErrno.NO_SUCH_FILE, name)
    }

    /**
     * The first line of a file, and nothing else.
     *
     * A 128-byte buffer is the whole read: the question is "does this file start with 19 bytes I
     * recognise", and a `BufferedReader` here would allocate 8 kB to answer it. The stream is closed
     * by the caller of the [Vfs], so this is a plain loop over [java.io.InputStream].
     */
    private fun firstLine(vfs: Vfs, path: String): String {
        val buf = ByteArray(128)
        var size = 0
        vfs.openRead(path).use { stream ->
            while (size < buf.size) {
                val n = stream.read(buf, size, buf.size - size)
                if (n < 0) break
                size += n
                val newline = indexOf(buf, size, 0x0A.toByte())
                if (newline >= 0) return String(buf, 0, newline, Charsets.UTF_8)
            }
        }
        return String(buf, 0, size, Charsets.UTF_8)
    }

    /** `indexOf` for a byte, which the stdlib does not have. */
    private fun indexOf(buf: ByteArray, size: Int, byte: Byte): Int {
        for (i in 0 until size) {
            if (buf[i] == byte) return i
        }
        return -1
    }
}
