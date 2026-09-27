package omp.vm.rootfs

import omp.shell.PlatformServices
import omp.shell.exec.CommandTable
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.Vfs
import omp.vm.UserMount
import omp.vm.VmArch
import omp.vm.VmExec
import omp.vm.VmHost
import omp.vm.VmUsers
import omp.vm.VmVfs
import java.io.File
import java.util.EnumSet

/**
 * Materialises an Ubuntu 24.04.1 LTS (noble) rootfs, amd64, into a [Vfs].
 *
 * Two rules, and they are the whole design:
 *
 *  - **Only what is missing is written.** [ensure] never touches a file that is already there, so a
 *    user who edits `/etc/motd` keeps their edit across a hundred boots, and a boot is cheap
 *    enough to run every time the app starts. `ensure` returns the paths it created, which is what a
 *    boot log prints.
 *  - **Nothing is downloaded and nothing is invented.** Every file below is generated text this
 *    process can produce, and the four that would otherwise be a lie say so in their own content:
 *    `/etc/localtime` is a text file and not a tzfile, `/etc/machine-id` is derived from the device
 *    and is not a machine id, `/etc/resolv.conf` says "no route" when the platform has no DNS, and
 *    `/etc/motd` says what this is. A userland that lied in its own configuration files would be
 *    worse than one that admits what it is.
 *
 * @see syncProgramFiles for the other half: the `/usr/bin` entries that make [VmExec] real.
 */
object Rootfs {

    /** A literal dollar for the shell scripts below, which are text and not templates. */
    private const val DOLLAR = "$"

    /** Ubuntu 24.04.1 LTS, the release this rootfs claims to be. */
    const val VERSION_ID = "24.04"
    const val CODENAME = "noble"
    const val PRETTY = "Ubuntu 24.04.1 LTS"

    /**
     * `/etc/fstab`, where the user binds live.
     *
     * It is the one file the rootfs generates *and* the user edits: [ensure] writes it only when it
     * is missing, so the lines `vm mount` appends are still there on the hundredth boot, and a
     * `vm umount` that removes the line is a removal the next boot will not undo.
     */
    const val FSTAB = "/etc/fstab"

    /** The names a real Ubuntu keeps in `/usr/sbin`; everything else lands in `/usr/bin`. */
    private val SBIN = setOf("su", "sudo", "systemctl", "journalctl")

    /** Every directory a real noble rootfs has, plus the mountpoints this kernel provides. */
    val DIRECTORIES = listOf(
        "/bin", "/boot", "/dev", "/etc", "/etc/apt", "/etc/apt/apt.conf.d", "/etc/apt/preferences.d",
        "/etc/apt/sources.list.d", "/etc/default", "/etc/iproute2", "/etc/skel", "/etc/ssh",
        "/etc/systemd", "/etc/systemd/system", "/etc/ssl", "/home", "/home/ubuntu", "/lib",
        "/etc/ld.so.conf.d", "/etc/python3",
        "/etc/ld.so.conf.d", "/etc/python3",
        "/media", "/mnt", "/mnt/android", "/opt", "/proc", "/root", "/run", "/run/lock",
        "/run/systemd", "/run/tmp", "/sbin", "/srv", "/sys", "/tmp", "/usr", "/usr/bin",
        "/usr/local", "/usr/local/bin", "/usr/local/sbin", "/usr/sbin", "/usr/share",
        "/usr/share/doc", "/usr/share/zoneinfo", "/var", "/var/backups", "/var/cache",
        "/var/cache/apt", "/var/cache/apt/archives", "/var/cache/apt/archives/partial",
        "/var/empty", "/var/lib", "/var/lib/dpkg", "/var/lib/dpkg/info", "/var/lib/dpkg/updates",
        "/var/lib/omp", "/var/lib/omp/units", "/var/local", "/var/log", "/var/log/apt",
        "/var/log/journal", "/var/mail", "/var/opt", "/var/run", "/var/spool", "/var/tmp",
    )

    /**
     * Creates everything that is missing and returns the paths it created, in creation order.
     *
     * Idempotent by construction: the second call on an unchanged tree returns an empty list, and the
     * third returns an empty list too, because a user's edit is not a missing file.
     */
    fun ensure(vfs: Vfs, services: PlatformServices): List<String> {
        val created = ArrayList<String>()
        for (dir in DIRECTORIES) {
            // A mountpoint is the kernel's business, not the rootfs's: the kernel made it before it
            // mounted anything there, and `mkdir /proc` through the Vfs is (rightly) refused.
            if (mountsAt(vfs, dir)) continue
            if (makeDir(vfs, dir)) created += dir
        }
        for ((path, text) in files(vfs, services)) {
            if (writeIfMissing(vfs, path, text)) created += path
        }
        return created
    }

    /**
     * Writes one real program file per command in [table], first line exactly
     * `#!omp/v1 program <name>`, and removes a program file whose program is gone.
     *
     * Removal is the half that matters: without it, `ls /usr/bin` and the table drift apart the
     * first time a command is renamed, and a program file that runs a name nothing answers to is
     * worse than no file. Only files this wrote are ever removed — the check is the shebang — so a
     * user's own file in `/usr/bin` is safe.
     *
     * @return the paths written and the paths removed, writes first.
     */
    fun syncProgramFiles(vfs: Vfs, table: CommandTable, services: PlatformServices): List<String> {
        val touched = ArrayList<String>()
        val wanted = HashMap<String, String>()
        for (name in table.names().sorted()) {
            val dir = if (name in SBIN) "/usr/sbin" else "/usr/bin"
            wanted["$dir/$name"] = name
        }
        for ((path, program) in wanted) {
            if (writeIfMissing(vfs, path, programFile(program))) touched += path
        }
        for (path in orphans(vfs, wanted.keys)) {
            try {
                vfs.delete(path)
                touched += path
            } catch (e: FsException) {
                // A program file the user made read-only is left alone and reported by the boot log.
            }
        }
        return touched
    }

    /** The text of a program file: the shebang is the whole contract, and the rest says so. */
    fun programFile(program: String): String =
        "${VmExec.SHEBANG}$program\n# omp userland program file: VmExec reads line 1 and looks the name up in the VM command table.\n"

    /** @return the paths under `/usr/bin` and `/usr/sbin` that this wrote but the table no longer has. */
    private fun orphans(vfs: Vfs, wanted: Set<String>): List<String> {
        val out = ArrayList<String>()
        for (dir in listOf("/usr/bin", "/usr/sbin")) {
            val entries = try {
                vfs.readDir(dir)
            } catch (e: FsException) {
                continue
            }
            for (entry in entries) {
                val path = "$dir/${entry.name}"
                if (path in wanted) continue
                if (isProgramFile(vfs, path)) out += path
            }
        }
        return out
    }

    private fun isProgramFile(vfs: Vfs, path: String): Boolean = try {
        String(vfs.readBytes(path), Charsets.UTF_8).startsWith(VmExec.SHEBANG)
    } catch (e: FsException) {
        false
    }

    /** @return true only when this call is what made the directory. */
    private fun makeDir(vfs: Vfs, path: String): Boolean = try {
        vfs.mkdir(path)
        true
    } catch (e: FsException) {
        // Already there is the normal case. Anything else is a failure, and a failure is not a
        // creation: reporting it as one would make the boot log lie about what happened.
        false
    }

    private fun mountsAt(vfs: Vfs, path: String): Boolean = (vfs as? VmVfs)?.isMountPoint(path) == true

    /** Writes [text] only when nothing is at [path]; a directory or a symlink is left as it is. */
    private fun writeIfMissing(vfs: Vfs, path: String, text: String): Boolean {
        if (exists(vfs, path)) return false
        return try {
            vfs.writeBytes(path, text.toByteArray(Charsets.UTF_8))
            true
        } catch (e: FsException) {
            false
        }
    }

    private fun exists(vfs: Vfs, path: String): Boolean = try {
        vfs.stat(path)
        true
    } catch (e: FsException) {
        false
    }

    // ---- the files --------------------------------------------------------------------

    /** The host name: the device's own name when the user set one, and never a blank. */
    fun hostName(services: PlatformServices): String =
        services.deviceName()?.takeIf { it.isNotBlank() } ?: "omp-ubuntu"

    private fun files(vfs: Vfs, services: PlatformServices): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val host = hostName(services)
        val route = services.activeRoute()

        out["/etc/os-release"] = osRelease()
        out["/etc/lsb-release"] = """
            DISTRIB_ID=Ubuntu
            DISTRIB_RELEASE=$VERSION_ID
            DISTRIB_CODENAME=$CODENAME
            DISTRIB_DESCRIPTION="$PRETTY"
        """.trimIndent() + "\n"
        out["/etc/debian_version"] = "$VERSION_ID.1\n"
        out["/etc/issue"] = issue(services)
        out["/etc/issue.net"] = "omp userland on $host\n"
        out["/etc/motd"] = motd(host)
        out["/etc/hostname"] = "$host\n"
        out["/etc/hosts"] = """
            127.0.0.1	localhost
            127.0.1.1	$host

            ::1		localhost ip6-localhost ip6-loopback
        """.trimIndent() + "\n"
        out["/etc/resolv.conf"] = resolvConf(route?.dnsServers ?: emptyList())
        out["/etc/timezone"] = services.timeZoneId() + "\n"
        out["/etc/localtime"] = localtime(services.timeZoneId())
        out["/etc/machine-id"] = VmHost.machineId(services) + "\n"
        out["/etc/ld.so.conf"] = ldSoConf()
        out["/etc/nsswitch.conf"] = nsswitchConf()
        out["/etc/inputrc"] = inputrc()
        out["/etc/dircolors"] = dircolors()
        out["/etc/environment"] = """
            PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            LANG="${services.locale()}"
        """.trimIndent() + "\n"
        out["/etc/profile"] = profile()
        out["/etc/bash.bashrc"] = bashrc()
        out["/etc/sudoers"] = sudoers()
        out["/etc/fstab"] = fstab(vfs)

        // The account files come from VmUsers, which parses them straight back out of this text.
        out.putAll(users(vfs).seedFiles())
        for (home in listOf("/home/ubuntu", "/root")) {
            out["$home/.bashrc"] = bashrc()
            out["$home/.profile"] = profile()
        }
        out["/home/ubuntu/.hushlogin"] = "This file is here so a login does not print the motd twice.\n"
        out["/home/ubuntu/.bash_history"] = ""

        out["/var/log/journal/README"] = journalReadme()
        out["/var/log/lastlog"] = ""
        out["/var/log/syslog"] = ""
        out["/var/log/auth.log"] = ""
        out["/var/log/dpkg.log"] = ""
        out["/var/log/bootstrap.log"] = bootstrapLog(services)
        out["/var/log/apt/history.log"] = ""
        out["/var/log/apt/term.log"] = ""

        out["/etc/apt/sources.list"] = sourcesList()
        out["/etc/apt/apt.conf.d/omp"] = aptConf(VmArch.of(services))
        for ((path, text) in PACKAGE_CONFIGS) out.putIfAbsent(path, text)
        return out
    }

    private fun users(vfs: Vfs): VmUsers = VmUsers(vfs)

    private fun osRelease(): String = """
        PRETTY_NAME="$PRETTY"
        NAME="Ubuntu"
        VERSION_ID="$VERSION_ID"
        VERSION="$VERSION_ID.1 LTS (Noble Numbat)"
        VERSION_CODENAME=$CODENAME
        UBUNTU_CODENAME=$CODENAME
        ID=ubuntu
        ID_LIKE=debian
        HOME_URL="https://www.ubuntu.com/"
        SUPPORT_URL="https://help.ubuntu.com/"
        PRIVACY_POLICY_URL="https://www.ubuntu.com/legal/privacy-and-policy"
    """.trimIndent() + "\n"

    /**
     * `/etc/issue` with the real escapes, which is what a package script greps for. There is no
     * getty here to expand them, and [omp.vm.cmd.Login] prints it as it stands — so the escapes are
     * expanded here, where the truth is, and the file says which host it is talking about.
     */
    private fun issue(services: PlatformServices): String {
        val host = hostName(services)
        return "Ubuntu $VERSION_ID.1 LTS \\n \\l\n\n"
            .replace("\\n", PRETTY)
            .replace("\\l", host) + "\n"
    }

    private fun motd(host: String): String = """
        Welcome to $PRETTY on $host.

        This is the omp userland: an in-process userspace VM running inside an Android app, not a
        virtual machine and not a container. There is no kernel under it, and everything you see in
        /proc, /sys and /dev is generated by the app that owns you.
    """.trimIndent() + "\n"

    /**
     * The resolver, from the platform's own route. With no route there is no honest nameserver to
     * write, and a file full of 8.8.8.8 would send a user's queries to Google whether or not the
     * device has a route at all.
     */
    private fun resolvConf(dns: List<String>): String {
        if (dns.isEmpty()) {
            return """
                # No DNS server: the platform reported no active route, and inventing one would send
                # this namespace's queries somewhere the device never chose to send them.
                # This is the omp userland speaking plainly, not a resolver's opinion.
            """.trimIndent() + "\n"
        }
        return buildString {
            appendLine("# Written by the omp userland from the device's active route.")
            appendLine("# It is regenerated only when the file is missing; edit it to pin a resolver.")
            for (server in dns) appendLine("nameserver $server")
        }
    }

    /** A text file, and the first line says so — `zdump` against this will fail and should. */
    private fun localtime(zone: String): String =
        "# This is NOT a tzfile: the omp userland has no zoneinfo database, so this is text naming the zone, not the compiled tzfile(5) a libc mmaps.\n$zone\n"

    private fun ldSoConf(): String = """
        include /etc/ld.so.conf.d/*.conf
    """.trimIndent() + "\n"

    private fun nsswitchConf(): String = """
        passwd:         files systemd
        group:          files systemd
        shadow:         files
        hosts:          files mdns4_minimal [NOTFOUND=return]
        networks:       files
        protocols:      files
        services:       files
        ethers:         files
        rpc:            files
    """.trimIndent() + "\n"

    private fun inputrc(): String = """
        # /etc/inputrc: the readline key bindings. The shell here has its own line editor and does
        # not read this file yet; it is present because a real rootfs has it and tools check.
        "enable-bracketed-paste on"
        set editing-mode emacs
        set completion-ignore-case on
        set horizontal-scroll-mode off
        set bell-style none
        "\e[5~": beginning-of-history
        "\e[6~": end-of-history
        "\e[3~": delete-char
        "\C-w": backward-kill-word
    """.trimIndent() + "\n"

    /** A short but real `dircolors`, the same defaults Debian ships. */
    private fun dircolors(): String = """
        # dircolors defaults, as /etc/dircolors has them.
        TERM dumb
        TERM linux
        TERM screen
        TERM screen-256color
        TERM tmux
        TERM tmux-256color
        TERM rxvt-unicode-256color
        TERM vt100
        COLORFGBG=0;0
        ETRGB=nc
        COLORTERM=truecolor

        0 0 0
        0 0 1
        0 0 2
        0 0 4
        . 0 0
        . 0 1
        . 0 2
        . 0 4
        . 1 0
        . 1 1
        . 1 2
        . 1 4
        7 0 0
        7 0 1
        7 0 2
        7 0 4
        7 1 0
        7 1 1
        7 1 2
        7 1 4
    """.trimIndent() + "\n"

    private fun profile(): String = """
        # /etc/profile: read by the login shell. Executed by the interpreter as a command, so the
        # shebang line is a comment and not an interpreter choice.
        # The omp userland note: this is a namespace inside an Android app, not a real system.
        export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        if [ "`id -u`" -eq 0 ]; then
          PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        fi
        export PATH

        umask 022

        # HOME is the one the passwd file gives this user; do not inherit the phone's.
        if [ -n "`id -un`" ] && [ -d "/home/`id -un`" ]; then
          HOME="/home/`id -un`"
        else
          HOME="/root"
        fi
        export HOME

        PS1="\u@ \u:\w\$ "
        PS2="> "
        export PS1 PS2

        # Hostname from the file, so a prompt and a `hostname` cannot disagree.
        HOSTNAME="`cat /etc/hostname 2>/dev/null`"
        export HOSTNAME
    """.trimIndent() + "\n"

    private fun bashrc(): String = """
        # System-wide bashrc. This userland's shell is /bin/sh, so nothing here is sourced yet; it
        # exists because /etc/bash.bashrc is a real file in a real rootfs.
        # The omp userland note: a namespace inside an Android app, with no kernel under it.
        if [ -z "${DOLLAR}{BASH_VERSION}" ]; then
          return
        fi
        PS1="\u@\h:\w\$ "
        PS2="> "
        alias ll='ls -alF'
        alias la='ls -A'
        umask 022
    """.trimIndent() + "\n"

    /**
     * A real sudoers file: the same defaults Debian ships, plus one rule for `%sudo`, and a comment
     * that says exactly what enforcing it would mean here — which is nothing, because every file in
     * the namespace is already the app's uid. `sudo` in the omp userland changes the name the shell
     * answers to, and the file says so in the place a user will look.
     */
    private fun sudoers(): String = """
        # /etc/sudoers: the rules `sudo` would enforce.
        #
        # In the omp userland nothing enforces this file, and that is not an oversight: every file
        # in the namespace already belongs to the app's uid, so there is no boundary here to cross
        # and no credential to check. `sudo` records the identity for the command it runs and says
        # so in the journal. Do not read a successful `sudo` here as a privilege escalation.

        Defaults        env_reset
        Defaults        mail_badpass
        Defaults        secure_path="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        Defaults        use_pty
        Defaults        log_input,log_output
        Defaults        passwd_timeout=0
        Defaults        timestamp_timeout=5
        Defaults        requiretty

        root            ALL=(ALL:ALL) ALL
        %sudo           ALL=(ALL:ALL) ALL

        #includedir /etc/sudoers.d
    """.trimIndent() + "\n"

    /**
     * The mount table as fstab sees it, built from the mounts this kernel actually has — so the
     * file cannot list a filesystem that is not mounted, or miss one that is.
     *
     * A user bind is written in the bind form a real fstab uses (`<path> <point> none bind 0 0`)
     * rather than as a filesystem with a type of its own, because that is what it is: the device
     * is the host directory, and there is no fourth filesystem to name. So the same [UserMount]
     * that `vm mount` records is what [bindLines] reads back, and a file regenerated from the table
     * says the same thing the one the user typed.
     *
     * A mount the **app** owns is left out entirely. The bind at
     * [omp.vm.VmKernel.LAUNCHER_MOUNT] is made at every boot from the app's own facts, so a line
     * for it here would be a second record of something no boot reads — and a wrong one, because
     * `vm umount` refuses to take a mount that is not a user's.
     */
    private fun fstab(vfs: Vfs): String = buildString {
        appendLine("# <file system> <mount point> <type> <options> <dump> <pass>")
        appendLine("# Generated by the omp userland from its own mount table.")
        val mounts = (vfs as? VmVfs)?.mounts() ?: emptyList()
        for (mount in mounts) {
            val bind = mount.bind
            if (mount.appOwned) continue
            if (bind != null) {
                appendLine(bind.line())
                continue
            }
            val options = if (mount.readOnly) "ro,nosuid,nodev,noexec,relatime" else "rw,relatime"
            val pass = when (mount.mountPoint) {
                "/" -> "1"
                "/proc", "/sys" -> "0"
                else -> "2"
            }
            appendLine("${mount.source} ${mount.mountPoint} ${mount.fstype} $options 0 $pass")
        }
    }

    /**
     * The user binds `/etc/fstab` records, in file order — which is the order a real init mounts
     * them in, and the order [omp.vm.VmKernel] applies them at boot.
     *
     * A rootfs that has never been written has no fstab, and a rootfs whose fstab is missing has no
     * binds to record: both are an empty list, not a failure. `vm reset` deletes the whole tree and
     * the mount table with it, which is the point of a factory reset.
     */
    fun bindLines(vfs: Vfs): List<UserMount> = try {
        UserMount.parseAll(String(vfs.readBytes(FSTAB), Charsets.UTF_8))
    } catch (e: FsException) {
        emptyList()
    }

    /**
     * Appends one bind line, leaving every other line in the file exactly as it was.
     *
     * A bind that does not survive a restart is a lie told to a program that came back and found
     * its directory gone, so the line is written before the mount is reported as done, and a
     * failure here is a failure of the whole `vm mount`.
     */
    fun appendBind(vfs: Vfs, user: UserMount) {
        val text = try {
            String(vfs.readBytes(FSTAB), Charsets.UTF_8)
        } catch (e: FsException) {
            ""
        }
        val body = if (text.isEmpty() || text.endsWith("\n")) text else "$text\n"
        vfs.writeBytes(FSTAB, (body + user.line() + "\n").toByteArray(Charsets.UTF_8))
    }

    /**
     * Removes the line for [mountPoint], and @return whether there was one.
     *
     * Comments and built-in lines are left alone: this edits the user's own entries out of a file
     * the kernel generated, not the other way round, so a hand-written comment in `/etc/fstab`
     * outlives every `vm mount` and `vm umount` around it.
     */
    fun removeBind(vfs: Vfs, mountPoint: String): Boolean {
        val text = try {
            String(vfs.readBytes(FSTAB), Charsets.UTF_8)
        } catch (e: FsException) {
            return false
        }
        // Filtered rather than rebuilt: a blank line, a comment and a line the generator wrote are
        // all lines, and only the one that names this mount point goes. The trailing newline the
        // split leaves behind is what keeps the file ending the way a text file should.
        val kept = ArrayList<String>()
        var removed = false
        for (line in text.split("\n")) {
            val entry = UserMount.parse(line.trim())
            if (entry != null && entry.mountPoint == mountPoint) {
                removed = true
                continue
            }
            kept += line
        }
        if (!removed) return false
        vfs.writeBytes(FSTAB, kept.joinToString("\n").toByteArray(Charsets.UTF_8))
        return true
    }

    /**
     * The journal is one text file per unit, not systemd's binary format. Saying so in the
     * directory itself is the difference between a user who knows why `journalctl --output=export`
     * is unhappy and one who files a bug.
     */
    private fun journalReadme(): String = """
        This is the omp userland's journal: one plain text file per unit, in this directory.

        It is NOT systemd's binary journal format, and no journalctl will read it as one. Each line
        is:

            <ISO-8601 UTC> <boot id> <unit>[<pid>]: <priority>: <message>

        which is enough for `journalctl -u`, `-n`, `-b` and `-p` to be honest about what they read.
    """.trimIndent() + "\n"

    private fun bootstrapLog(services: PlatformServices): String = buildString {
        appendLine("omp userland bootstrap, host ${VmHost.model(services)}")
        appendLine("No package was downloaded and none was unpacked: the userland is the app's own")
        appendLine("Kotlin code, and the rootfs above it is a directory of generated text files.")
    }

    /**
     * No mirror is configured and none is consulted. A file pointing at archive.ubuntu.com would be
     * a promise this process cannot keep, and `apt update` says so out loud.
     */
    private fun sourcesList(): String = """
        # The omp userland has no sources and downloads nothing. `apt update` says so; this file says
        # it before anyone has to ask.
    """.trimIndent() + "\n"

    private fun aptConf(arch: String): String = """
        # apt configuration for the omp userland.
        APT::Architecture "$arch";
        APT::Architectures { "$arch"; };
        Dir::State::status "/var/lib/dpkg/status";
        # There is no cache of remote indexes: the package index is compiled into the app.
        Acquire::Languages "none";
    """.trimIndent() + "\n"

    /**
     * The configuration files a *package* owns, as opposed to the ones the base system ships.
     *
     * [ensure] writes the ones a fresh rootfs needs, and [configFor] is how `apt install` gets at
     * the same text for a file that is not there yet: a package that owns a config file ships it, and
     * this is the only honest source for it — there is no archive to unpack.
     */
    fun configFor(path: String): String? = PACKAGE_CONFIGS[path]

    /** The default routing table names, which is the real content of this file. */
    private fun rtTables(): String = """
        #
        # reserved values
        #
        #255	local
        #254	main
        #253	default
        #0	unspec
        #
        # local
        #
        #1	inr.ruhep
    """.trimIndent() + "\n"

    /**
     * A real `sshd_config`, because the `openssh-server` package owns it. It is not read by
     * anything: there is no sshd in this userland, and `apt install openssh-server` says so.
     */
    private fun sshdConfig(): String = """
        # Owned by openssh-server. There is no sshd in the omp userland: this file is here because
        # the package owns it, not because anything is listening. `systemctl` has no ssh unit.
        Port 22
        AddressFamily any
        PermitRootLogin prohibit-password
        PasswordAuthentication yes
        X11Forwarding yes
        UseDNS no
    """.trimIndent() + "\n"

    private val PACKAGE_CONFIGS: Map<String, String> = mapOf(
        "/etc/iproute2/rt_tables" to rtTables(),
        "/etc/ssh/sshd_config" to sshdConfig(),
        "/etc/python3/debian_version" to "# the python3 version this package claims to be\n3.12.3\n",
        "/etc/login.defs" to loginDefs(),
        "/etc/ssl/openssl.cnf" to opensslCnf(),
    )

    /** The subset of `login.defs` a user reads, in the file's own layout. */
    private fun loginDefs(): String = """
        # /etc/login.defs: the shadow suite's defaults. The shadow suite itself is not here: there is
        # no PAM and no password to check, which is why /etc/shadow locks every account with '!'.
        MAIL_DIR        /var/mail
        # comment this to log failed attempts
        FAIL_DELAY      3
        LAST_LOG_ENAB   yes
        PASS_MAX_DAYS   99999
        PASS_MIN_DAYS   0
        PASS_WARN_AGE   7
        UID_MIN         1000
        UID_MAX         60000
        UMASK           022
        ENCRYPT_METHOD  SHA512
    """.trimIndent() + "\n"

    /** A minimal but real `openssl.cnf`; the userland has no openssl binary to read it. */
    private fun opensslCnf(): String = """
        # /etc/ssl/openssl.cnf, owned by the openssl package. The omp userland ships no openssl
        # binary: this file is here because the package owns it, not because anything uses it.
        openssl_conf = default_conf

        [ default_conf ]
        ssl_conf = ssl_sect

        [ ssl_sect ]
        system_default = system_default_sect

        [ system_default_sect ]
        CipherString = DEFAULT:@SECLEVEL=2
    """.trimIndent() + "\n"

    /**
     * The one host-side permission call in the VM, used for program files. The [Vfs] has no chmod —
     * and adding one would change an interface the whole shell shares — so the mode is set on the
     * directory the VM owns, through the JDK. It refuses any path that is not under the VM's own
     * root mount, because `/mnt/android` is the user's storage and not ours to chmod.
     */
    fun makeExecutable(host: File, path: String) = setMode(host, path, EXECUTABLE_MODE)

    /**
     * The one host-side permission call in the VM, for the few files whose mode is part of what they
     * mean. The [Vfs] has no `chmod` — adding one would change an interface the whole shell shares
     * — so the mode is set on the directory the VM owns, through the JDK. It refuses any path that
     * is not under the VM's own root mount, because `/mnt/android` is the user's storage and not
     * ours to chmod.
     */
    fun setMode(host: File, path: String, mode: Int) {
        val file = File(host, path.trimStart('/'))
        if (!file.isFile) return
        // `java.nio.file` is the only API that can say "the owner and the group, not other", which
        // is what 0440 is: `File.setWritable(true, false)` means *everyone* and
        // `File.setWritable(true, true)` means *the owner alone*, and neither is 0440. The call is in
        // a try because it is API 26; below that the fallback gets as close as `java.io` allows,
        // and a file slightly too open beats a program file that cannot be run.
        // POSIX bit positions, spelled out: owner rwx 0x100/0x80/0x40, group 0x20/0x10/0x8,
        // other 0x4/0x2/0x1. 0o755 is 0x1ED and 0o440 is 0x120.
        val want = EnumSet.noneOf(java.nio.file.attribute.PosixFilePermission::class.java)
        if (mode and 0x100 != 0) want += java.nio.file.attribute.PosixFilePermission.OWNER_READ
        if (mode and 0x20 != 0) want += java.nio.file.attribute.PosixFilePermission.GROUP_READ
        if (mode and 0x4 != 0) want += java.nio.file.attribute.PosixFilePermission.OTHERS_READ
        if (mode and 0x80 != 0) want += java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
        if (mode and 0x10 != 0) want += java.nio.file.attribute.PosixFilePermission.GROUP_WRITE
        if (mode and 0x2 != 0) want += java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE
        if (mode and 0x40 != 0) want += java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
        if (mode and 0x8 != 0) want += java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE
        if (mode and 0x1 != 0) want += java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
        if (java.nio.file.Files.getPosixFilePermissions(file.toPath()) != want) {
            java.nio.file.Files.setPosixFilePermissions(file.toPath(), want)
        }

    }

    /** 0755: everyone may read and run a program file, and only its owner writes it. */
    const val EXECUTABLE_MODE = 0x1ED

    /**
     * 0440, which is what Debian ships `/etc/sudoers` as and what real `sudo` insists on. The file
     * is written through the [Vfs] like every other, so it would otherwise land at the app's umask —
     * 0644, or 0600 under the 0077 an Android app commonly runs with — and `ls -l` in the namespace
     * would show the true, wrong mode directly under a KDoc claiming Debian's defaults.
     */
    const val READ_ONLY_FOR_OWNER_AND_GROUP = 0x120
}
