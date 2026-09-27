package omp.vm.pkg

import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.Vfs

/**
 * One Debian control stanza: the `Key: value` lines dpkg keeps, in the order they were written.
 * Continuation lines (a space then more text) belong to the key above them, which is how a long
 * `Description:` is wrapped.
 */
class Stanza(val fields: LinkedHashMap<String, String> = LinkedHashMap()) {

    fun get(key: String): String? = fields[key]

    fun set(key: String, value: String): Stanza {
        fields[key] = value
        return this
    }

    fun status(): String = get("Status") ?: "unknown ok not-installed"

    fun installed(): Boolean = isInstalled(status())

    /** The text dpkg writes, stanza for stanza. */
    fun render(): String = buildString {
        for ((key, value) in fields) {
            for ((i, line) in value.split('\n').withIndex()) {
                append(if (i == 0) "$key: $line" else " $line")
                append('\n')
            }
        }
    }

    companion object {
        fun isInstalled(status: String): Boolean = status == "install ok installed"

        /** Parses one stanza's text. A blank line ends it; anything unparsable is skipped. */
        fun parse(text: String): Stanza {
            val out = Stanza()
            var key: String? = null
            for (line in text.split('\n')) {
                if (line.isBlank()) continue
                if (line.startsWith(" ") || line.startsWith("\t")) {
                    val k = key ?: continue
                    val existing = out.fields[k] ?: ""
                    out.fields[k] = existing + "\n" + line.trim()
                    continue
                }
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                key = line.substring(0, colon).trim()
                out.fields[key] = line.substring(colon + 1).trim()
            }
            return out
        }

        /** The whole file, one stanza per blank-line-separated block, in file order. */
        fun parseAll(text: String): List<Stanza> =
            text.split("\n\n").filter { it.isNotBlank() }.map { parse(it) }
    }
}

/**
 * The package database on disk: `/var/lib/dpkg/status` for the stanzas, `/var/lib/dpkg/available`
 * for what the index knows, and `/var/lib/dpkg/info/<pkg>.list` for the files each package owns.
 *
 * It is a real database in a real place, written through the [Vfs] like everything else, which is
 * what makes `dpkg -L` answer with files that exist and `dpkg -S` resolve a path back to its owner
 * after a reboot. `dpkg` itself keeps file lists in exactly this way, and a tool that reads the file
 * is reading the same file dpkg would have written.
 */
class DpkgDatabase(
    private val vfs: Vfs,
    /**
     * The architecture every stanza this class writes is stamped with, read once per stanza so a
     * device that reports a different ABI gets a database that agrees with `dpkg --print-architecture`
     * and with `uname -m`. Defaults to [omp.vm.VmArch.DEFAULT] so a caller with no platform behind
     * it is unchanged.
     */
    private val arch: () -> String = { omp.vm.VmArch.DEFAULT },
) {

    /** Every stanza in `status`, in file order. */
    fun stanzas(): List<Stanza> = read(STATUS).let { Stanza.parseAll(it) }

    fun available(): List<Stanza> = read(AVAILABLE).let { Stanza.parseAll(it) }

    fun status(name: String): Stanza? = stanzas().firstOrNull { it.get("Package") == name }

    fun installedNames(): List<String> = stanzas().filter { it.installed() }.mapNotNull { it.get("Package") }

    /** Writes the whole file from a list of stanzas, which is how dpkg does it too. */
    fun writeStanzas(stanzas: List<Stanza>) {
        write(STATUS, stanzas.joinToString("\n") { it.render().trimEnd() + "\n" })
    }

    fun setStanza(stanza: Stanza) {
        val all = stanzas().toMutableList()
        val name = stanza.get("Package")
        val at = all.indexOfFirst { it.get("Package") == name }
        if (at >= 0) all[at] = stanza else all += stanza
        writeStanzas(all)
    }

    fun removeStanza(name: String) {
        writeStanzas(stanzas().filter { it.get("Package") != name })
    }

    /**
     * Seeds `status` from the index the first time, so a fresh rootfs has a database that says the
     * base userland is installed. Idempotent: an existing status file is never rewritten, which is
     * what keeps a user's removals across a reboot.
     */
    fun seedFromIndex(): Boolean {
        if (exists(STATUS)) return false
        val stanzas = PackageIndex.all.map { pkg ->
            Stanza().apply {
                set("Package", pkg.name)
                set("Status", if (pkg.base) "install ok installed" else "install ok not-installed")
                set("Priority", pkg.priority)
                set("Section", pkg.section)
                set("Installed-Size", pkg.installedSizeKb.toString())
                set("Maintainer", pkg.maintainer)
                set("Architecture", arch())
                set("Version", pkg.version)
                if (pkg.depends.isNotEmpty()) set("Depends", pkg.depends.joinToString(", "))
                set("Description", pkg.description)
            }
        }
        writeStanzas(stanzas)
        write(AVAILABLE, stanzas.joinToString("\n") { it.render().trimEnd() + "\n" })
        for (pkg in PackageIndex.all.filter { it.base }) writeList(pkg)
        return true
    }

    /** `/var/lib/dpkg/info/<pkg>.list`: the files a package owns, the way dpkg records them. */
    fun listOf(name: String): List<String> = read("$INFO$name.list")
        .split('\n')
        .filter { it.isNotBlank() }

    fun writeList(pkg: VmPackage, files: List<String> = filesOf(pkg)) {
        write("$INFO${pkg.name}.list", files.joinToString("\n") { it } + if (files.isEmpty()) "" else "\n")
    }

    fun removeList(name: String) {
        try {
            vfs.delete("$INFO$name.list")
        } catch (e: FsException) {
            if (e.errno != FsErrno.NO_SUCH_FILE) throw e
        }
    }

    /** @return the package that owns [path], by its file list, or null. */
    fun ownerOf(path: String): String? {
        for (stanza in stanzas()) {
            if (!stanza.installed()) continue
            val name = stanza.get("Package") ?: continue
            if (this.listOf(name).contains(path)) return name
        }
        return null
    }

    /** The paths a package owns: its program files and its conffiles, deduplicated. */
    fun filesOf(pkg: VmPackage, sbin: Set<String> = emptySet()): List<String> {
        val out = ArrayList<String>()
        for (program in pkg.programs) out += PackageIndex.programPath(program, sbin)
        for (conffile in pkg.conffiles) {
            if (exists(conffile)) out += conffile
        }
        return out.distinct()
    }

    /** The file dpkg writes when it records a configuration change. */
    fun conffiles(name: String): List<String> =
        try {
            read("$INFO$name.conffiles").split('\n').filter { it.isNotBlank() }
        } catch (e: FsException) {
            emptyList()
        }

    fun writeConffiles(name: String, paths: List<String>) {
        if (paths.isEmpty()) return
        write("$INFO$name.conffiles", paths.joinToString("\n") + "\n")
    }

    fun removeConffiles(name: String) {
        try {
            vfs.delete("$INFO$name.conffiles")
        } catch (e: FsException) {
            if (e.errno != FsErrno.NO_SUCH_FILE) throw e
        }
    }

    // ---- the file helpers ---------------------------------------------------------------

    private fun read(path: String): String = try {
        String(vfs.readBytes(path), Charsets.UTF_8)
    } catch (e: FsException) {
        if (e.errno == FsErrno.NO_SUCH_FILE) "" else throw e
    }

    private fun write(path: String, text: String) {
        try {
            vfs.writeBytes(path, text.toByteArray(Charsets.UTF_8))
        } catch (e: FsException) {
            if (e.errno == FsErrno.NO_SUCH_FILE) {
                // A missing parent means the database directory was removed; make it and try once.
                vfs.mkdir(path.substringBeforeLast('/'))
                vfs.writeBytes(path, text.toByteArray(Charsets.UTF_8))
            } else {
                throw e
            }
        }
    }

    private fun exists(path: String): Boolean = try {
        vfs.stat(path)
        true
    } catch (e: FsException) {
        false
    }

    companion object {
        const val STATUS = "/var/lib/dpkg/status"
        const val AVAILABLE = "/var/lib/dpkg/available"
        const val INFO = "/var/lib/dpkg/info/"
    }
}
