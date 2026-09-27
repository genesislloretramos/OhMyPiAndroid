package omp.vm.pkg

import omp.shell.PlatformServices
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.Vfs
import omp.vm.rootfs.Rootfs

/** The `apt`/`dpkg` exit code for "no such package", which is 100 and not 1. */
const val EXIT_NO_PACKAGE = 100

/** `<cmd>: <path>: <reason>`, the shell's way of saying a filesystem said no. */
fun packageFsError(ctx: ExecContext, path: String, e: Throwable): Int =
    ctx.fail("${ctx.name}: $path: ${Errno.messageFor(e)}")

/**
 * The one place an install or a removal actually happens, so `apt`, `apt-get` and `dpkg` cannot
 * disagree about what a package owns.
 *
 * What it never does: print a `Get:` line, write a byte it did not produce, or claim a program was
 * unpacked when no archive was opened. The files come from [Rootfs]'s program-file writer, which
 * writes a shebang line and a routing decision, and the conffiles come from the rootfs, which is
 * where a conffile lives in any case. Everything else is bookkeeping in the dpkg database, which is
 * what dpkg itself does once the bytes are on disk.
 */
class PackageOps(
    private val vfs: Vfs,
    private val database: DpkgDatabase,
    private val services: PlatformServices,
    /** Makes a written program file executable; the [omp.shell.fs.Vfs] has no chmod. */
    private val makeExecutable: (String) -> Unit = { },
    /** The programs the VM's own table provides, which decides what a package can own here. */
    private val availablePrograms: Set<String> = emptySet(),
) {
    /** The programs of a package this userland really has. */
    fun provided(pkg: VmPackage): List<String> = pkg.programs.filter { it in availablePrograms }

    fun missing(pkg: VmPackage): List<String> = pkg.programs.filter { it !in availablePrograms }

    data class Outcome(
        val name: String,
        val written: List<String>,
        val kept: List<String>,
        val removed: List<String>,
        val notProvided: List<String>,
    )

    /**
     * Installs [pkg] if it is not installed, writing its program files and conffiles and its stanza.
     *
     * @param onLine called with each line the command should print, in order.
     * @return the outcome, or null when the package is already installed (the caller says so).
     */
    fun install(pkg: VmPackage, onLine: (String) -> Unit): Outcome? {
        val stanza = database.status(pkg.name)
        if (stanza != null && stanza.installed()) return null

        onLine("Selecting previously unselected package ${pkg.name} (${pkg.version}).")
        val provided = provided(pkg)
        val notProvided = missing(pkg)
        onLine("The files for ${pkg.name} come from the in-process userland; nothing is downloaded.")
        if (provided.isEmpty() && notProvided.isEmpty()) {
            onLine("  ${pkg.name} owns no files in this userland.")
        } else {
            for (program in provided) {
                val path = PackageIndex.programPath(program, SBIN)
                writeProgram(path, program)
                onLine("  Setting up $program: $path")
            }
        }
        if (notProvided.isNotEmpty()) {
            onLine("  ${notProvided.size} program(s) are not in this userland: ${notProvided.joinToString(", ")}")
        }
        // A package ships its conffiles. The only honest source for that text is the rootfs writer,
        // so a conffile the userland knows how to write is written now, and one it does not is
        // reported rather than invented.
        for (conffile in pkg.conffiles) {
            if (exists(conffile)) {
                onLine("  Keeping existing conffile $conffile")
                continue
            }
            val text = Rootfs.configFor(conffile)
            if (text == null) {
                onLine("  No content for $conffile in this userland; it was not created")
                continue
            }
            writeWithParents(conffile, text.toByteArray(Charsets.UTF_8))
            onLine("  Setting up $conffile")
        }
        database.setStanza(stanzaOf(pkg, "install ok installed"))
        database.writeList(pkg)
        database.writeConffiles(pkg.name, pkg.conffiles.filter { exists(it) })
        return Outcome(pkg.name, provided.map { PackageIndex.programPath(it, SBIN) }, emptyList(), emptyList(), notProvided)
    }

    /**
     * Removes [pkg]: its program files go, its conffiles stay, and dpkg's state says
     * `deinstall ok config-files`. That is what `apt remove` does and the difference is the whole
     * reason `purge` exists.
     *
     * One interaction worth knowing about, because it looks like a bug and is not: the userland sync
     * writes a program file for every command the VM table has, so a program file unlinked here comes
     * back at the next boot. The database is the record of what the package manager did, and it says
     * `deinstall` whatever the file list looks like afterwards. Unregistering the command is the way
     * to keep the file gone.
     */
    fun remove(pkg: VmPackage, purge: Boolean, onLine: (String) -> Unit): Outcome {
        val kept = ArrayList<String>()
        val removed = ArrayList<String>()
        val purged = ArrayList<String>()
        for (file in database.listOf(pkg.name)) {
            if (pkg.conffiles.contains(file)) {
                // apt's own wording: a conffile that stays is "kept on the system", and one that goes
                // is "purging", never "removing". The distinction is why purge exists.
                if (!purge) {
                    kept += file
                } else if (delete(file)) {
                    purged += file
                }
                continue
            }
            if (delete(file)) removed += file
        }
        onLine("Removing ${pkg.name} (${pkg.version}) ...")
        for (file in removed) onLine("  Removing $file")
        for (file in kept) onLine("  Configuration file $file, kept on the system")
        for (file in purged) onLine("  Purging configuration file $file")
        database.setStanza(stanzaOf(pkg, if (purge) "purge ok not-installed" else "deinstall ok config-files"))
        if (purge) {
            database.removeList(pkg.name)
            database.removeConffiles(pkg.name)
        } else {
            database.writeConffiles(pkg.name, kept)
        }
        return Outcome(pkg.name, emptyList(), kept, removed + purged, emptyList())
    }

    /** Writes one program file through the rootfs writer, so the shebang is the same everywhere. */
    private fun writeProgram(path: String, program: String) {
        vfs.writeBytes(path, Rootfs.programFile(program).toByteArray(Charsets.UTF_8))
        makeExecutable(path)
    }

    /** [omp.shell.fs.Vfs] has no mkdir -p, and a package's config directory may not exist yet. */
    private fun writeWithParents(path: String, bytes: ByteArray) {
        val parent = path.substringBeforeLast('/')
        if (parent.isNotEmpty()) {
            try {
                vfs.mkdir(parent)
            } catch (e: FsException) {
                if (e.errno != FsErrno.FILE_EXISTS) throw e
            }
        }
        vfs.writeBytes(path, bytes)
    }

    private fun delete(path: String): Boolean = try {
        vfs.delete(path)
        true
    } catch (e: FsException) {
        e.errno == FsErrno.NO_SUCH_FILE
    }

    private fun exists(path: String): Boolean = try {
        vfs.stat(path)
        true
    } catch (e: FsException) {
        false
    }

    /** The stanza dpkg writes for a package, in the field order the status file uses. */
    fun stanzaOf(pkg: VmPackage, status: String): Stanza = Stanza().apply {
        set("Package", pkg.name)
        set("Status", status)
        set("Priority", pkg.priority)
        set("Section", pkg.section)
        set("Installed-Size", if (status == "install ok installed") pkg.installedSizeKb.toString() else "0")
        set("Maintainer", pkg.maintainer)
        set("Architecture", omp.vm.VmArch.of(services))
        set("Version", pkg.version)
        if (pkg.depends.isNotEmpty()) set("Depends", pkg.depends.joinToString(", "))
        set("Description", pkg.description)
    }

    companion object {
        /** The names a real Ubuntu keeps in `/usr/sbin`. Mirrors [Rootfs]'s own list. */
        val SBIN = setOf("su", "sudo", "systemctl", "journalctl")
    }
}
