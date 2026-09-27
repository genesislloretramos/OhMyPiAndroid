package omp.vm.pkg

/**
 * One package as the omp userland knows it: the real name, the real noble version, and the programs
 * and conffiles it owns.
 *
 * [programs] is the part that has to be honest. A package here either has programs the VM command
 * table really provides, or it has none and the tools say so out loud. Nothing pretends to unpack a
 * binary: there is no archive, no `ar`, no `dpkg-deb`, and no bytes travel anywhere.
 */
data class VmPackage(
    val name: String,
    val version: String,
    val section: String,
    val priority: String,
    val maintainer: String,
    val installedSizeKb: Int,
    val description: String,
    /** Program names this package owns, in the VM's command table or not. */
    val programs: List<String>,
    /** Configuration files this package owns; `remove` keeps them, `purge` takes them. */
    val conffiles: List<String> = emptyList(),
    val depends: List<String> = emptyList(),
    /** True in a rootfs that was just bootstrapped: the base userland, with nothing to install. */
    val base: Boolean = false,
) {
    fun isInstalled(state: String): Boolean = state == "install ok installed"
}

/**
 * The package index, compiled into the app: thirteen real noble package names with the versions and
 * metadata `apt-cache show` prints.
 *
 * It is a *local* index. `apt update` says that in one line and prints no `Get:` or `Hit:` line for
 * bytes that never travelled, because the alternative — a fetch log for a download that did not
 * happen — is the kind of detail that ends up in somebody's bug report.
 */
object PackageIndex {

    const val BASE = "coreutils"

    val all: List<VmPackage> = listOf(
        VmPackage(
            name = "coreutils",
            version = "9.4-1ubuntu6.2",
            section = "utils",
            priority = "required",
            maintainer = "Ubuntu Developers <x-devel@lists.ubuntu.com>",
            installedSizeKb = 4_216,
            description = "GNU core utilities",
            programs = listOf(
                "ls", "cat", "cp", "mv", "rm", "mkdir", "rmdir", "ln", "touch", "echo", "printf",
                "head", "tail", "wc", "sort", "uniq", "tr", "cut", "tee", "basename", "dirname",
                "stat", "readlink", "realpath", "du", "df", "md5sum", "sha256sum", "env",
                "id", "whoami", "groups", "date", "true", "false", "sleep", "seq", "base64",
                "sync", "truncate", "nproc",
            ),
            conffiles = listOf("/etc/dircolors"),
            depends = listOf("libc6", "libselinux1"),
            base = true,
        ),
        VmPackage(
            name = "bash",
            version = "5.2.21-2ubuntu4",
            section = "shells",
            priority = "required",
            maintainer = "Matthias Klose <doko@debian.org>",
            installedSizeKb = 6_570,
            description = "GNU Bourne Again SHell",
            // The userland's shell really is sh; bash is not here, and nothing claims it is.
            programs = listOf("sh"),
            conffiles = listOf("/etc/bash.bashrc", "/etc/inputrc"),
            depends = listOf("base-files", "libc6"),
            base = true,
        ),
        VmPackage(
            name = "systemd",
            version = "255.4-1ubuntu8.3",
            section = "admin",
            priority = "important",
            maintainer = "S systemd Maintainers <systemd-devel@lists.ubuntu.com>",
            installedSizeKb = 12_300,
            description = "systemd system and service manager",
            // systemd-lite: the unit files, the journal and the two commands, and no cgroups.
            programs = listOf("systemctl", "journalctl"),
            conffiles = listOf("/etc/nsswitch.conf"),
            depends = listOf("libc6", "libcap2"),
            base = true,
        ),
        VmPackage(
            name = "util-linux",
            version = "2.39.3-9ubuntu6.2",
            section = "utils",
            priority = "required",
            maintainer = "Ubuntu Developers <x-devel@lists.ubuntu.com>",
            installedSizeKb = 5_130,
            description = "miscellaneous system utilities",
            programs = listOf("mount", "kill", "su", "login", "file", "tree"),
            conffiles = listOf("/etc/login.defs"),
            depends = listOf("libc6", "libblkid1"),
            base = true,
        ),
        VmPackage(
            name = "grep",
            version = "3.8-5build2",
            section = "utils",
            priority = "optional",
            maintainer = "Ubuntu Developers <x-devel@lists.ubuntu.com>",
            installedSizeKb = 1_090,
            description = "GNU grep, a search tool",
            programs = listOf("grep"),
            depends = listOf("libc6", "libpcre2-8-0"),
            base = true,
        ),
        VmPackage(
            name = "sed",
            version = "4.9-2build2",
            section = "utils",
            priority = "optional",
            maintainer = "Ubuntu Developers <x-devel@lists.ubuntu.com>",
            installedSizeKb = 1_290,
            description = "GNU stream editor",
            programs = listOf("sed"),
            depends = listOf("libc6", "libselinux1"),
            base = true,
        ),
        VmPackage(
            name = "findutils",
            version = "4.9.0-9build2",
            section = "utils",
            priority = "optional",
            maintainer = "Ubuntu Developers <x-devel@lists.ubuntu.com>",
            installedSizeKb = 1_700,
            description = "utilities for finding files in the file system hierarchy",
            programs = listOf("find", "xargs"),
            conffiles = listOf("/etc/fstab"),
            depends = listOf("libc6"),
            base = true,
        ),
        VmPackage(
            name = "gzip",
            version = "1.12-1ubuntu3.1",
            section = "utils",
            priority = "optional",
            maintainer = "Ubuntu Developers <x-devel@lists.ubuntu.com>",
            installedSizeKb = 1_030,
            description = "GNU compression utilities",
            // The table has no gzip, so this package owns nothing here and `apt install gzip` says
            // exactly that instead of writing a file that does nothing.
            programs = emptyList(),
            conffiles = emptyList(),
            depends = listOf("libc6"),
        ),
        VmPackage(
            name = "ca-certificates",
            version = "20240203~",
            section = "misc",
            priority = "optional",
            maintainer = "Michael Shuler <michael@pbandjelly.org>",
            installedSizeKb = 380,
            description = "Common CA certificates",
            programs = emptyList(),
            conffiles = emptyList(),
            depends = listOf("openssl", "debconf"),
        ),
        VmPackage(
            name = "openssl",
            version = "3.0.13-0ubuntu3.1",
            section = "utils",
            priority = "optional",
            maintainer = "Debian OpenSSL Team <pkg-openssl-devel@lists.alioth.debian.org>",
            installedSizeKb = 1_900,
            description = "Secure Sockets Layer toolkit - cryptographic utility",
            programs = emptyList(),
            conffiles = listOf("/etc/ssl/openssl.cnf"),
            depends = listOf("libc6", "libssl3"),
        ),
        VmPackage(
            name = "openssh-server",
            version = "1:9.6p1-3ubuntu13",
            section = "net",
            priority = "optional",
            maintainer = "Ubuntu Developers <x-devel@lists.ubuntu.com>",
            installedSizeKb = 1_900,
            description = "secure shell (SSH) server, for secure access from remote machines",
            // There is no sshd in the omp userland and nothing is listening, so there is no ssh unit
            // and no ssh program. `apt install` says so rather than pretending otherwise.
            programs = emptyList(),
            conffiles = listOf("/etc/ssh/sshd_config"),
            depends = listOf("libc6", "libssl3", "zlib1g"),
        ),
        VmPackage(
            name = "net-tools",
            version = "1.60-git+1ubuntu3.1",
            section = "net",
            priority = "optional",
            maintainer = "Ubuntu Developers <x-devel@lists.ubuntu.com>",
            installedSizeKb = 660,
            description = "tools for controlling the network subsystem in Linux",
            // ifconfig, route and netstat are all absent here on purpose: the README lists them as
            // commands an app cannot be trusted with, and a package entry may not wish them back.
            programs = emptyList(),
            conffiles = emptyList(),
            depends = listOf("libc6", "libnet1"),
        ),
        VmPackage(
            name = "python3-minimal",
            version = "3.12.3-0ubuntu2",
            section = "python",
            priority = "optional",
            maintainer = "Ubuntu Developers <x-devel@lists.ubuntu.com>",
            installedSizeKb = 1_800,
            description = "minimal subset of the python3-standard-library (version 3.12)",
            programs = emptyList(),
            conffiles = listOf("/etc/python3/debian_version"),
            depends = listOf("libc6", "libpython3-stdlib"),
        ),
        VmPackage(
            name = "iproute2",
            version = "6.1.0-1ubuntu2",
            section = "net",
            priority = "important",
            maintainer = "Ubuntu Developers <x-devel@lists.ubuntu.com>",
            installedSizeKb = 1_200,
            description = "tools for controlling the network subsystem in Linux",
            // `ip` is not in the table either; what this package really owns here is rt_tables.
            programs = emptyList(),
            conffiles = listOf("/etc/iproute2/rt_tables"),
            depends = listOf("libc6", "libbpf1"),
        ),
    )

    private val byName = all.associateBy { it.name }

    fun find(name: String): VmPackage? = byName[name]

    fun names(): List<String> = all.map { it.name }

    fun installedAtBootstrap(): List<VmPackage> = all.filter { it.base }

    /** @return the package that owns [path], by program file or conffile. */
    fun ownerOf(path: String): VmPackage? = all.firstOrNull { pkg ->
        pkg.programs.any { it == programName(path) } || pkg.conffiles.contains(path)
    }

    /** `/usr/bin/ls` -> `ls`, the name dpkg -S prints. */
    fun programName(path: String): String? {
        val slash = path.lastIndexOf('/')
        if (slash < 0) return null
        return path.substring(slash + 1)
    }

    /** The path a program of this package would live at, sbin tools included. */
    fun programPath(program: String, sbin: Set<String>): String =
        if (program in sbin) "/usr/sbin/$program" else "/usr/bin/$program"
}
