package omp.vm

import omp.shell.fs.FsException
import omp.shell.fs.FsErrno
import omp.shell.fs.Vfs

/** One `/etc/passwd` line: the five fields, plus the GECOS string tools print as "real name". */
data class VmUser(
    val name: String,
    val uid: Int,
    val gid: Int,
    val gecos: String,
    val home: String,
    val shell: String,
)

/** One `/etc/group` line. */
data class VmGroup(val name: String, val gid: Int, val members: List<String>)

/**
 * The VM's users, read out of its own `/etc/passwd` and `/etc/group` — through the [Vfs], never
 * through `java.io.File`, because the file may not be on this device at all: it may be a few
 * hundred bytes of a rootfs inside the app's own storage, and the moment a command can read it by
 * another route the namespace stops meaning anything.
 *
 * The honest limit, stated once and plainly: **the VM's `root` is not a privilege escalation on the
 * phone.** It is the app's own uid, and every file in the namespace is that uid's file. The kernel
 * here declines to enforce a boundary it cannot enforce — there is no `setuid` to drop, no
 * `CAP_SETUID` to hold and no second process to confuse — so `su` in this namespace changes the
 * name the shell answers to and nothing else. The one fact it does not pretend about is in
 * `/sys/omp/android/uid`, which is the app's real one.
 *
 * [current] is who the shell is, and it is what `/proc/self/status` reports as `Uid:` and `Gid:`.
 */
class VmUsers(private val vfs: Vfs, defaultName: String = DEFAULT_USER) {

    @Volatile
    private var active: VmUser = VmUser(defaultName, DEFAULT_UID, DEFAULT_UID, "omp user", "/home/$defaultName", SHELL)

    init {
        // The passwd file is written by the kernel at boot; if it is not there yet the defaults
        // above stand, because a shell with no user is worse than a shell with a named one.
        val known = passwd()
        active = known.firstOrNull { it.name == defaultName } ?: active
    }

    /**
     * Every user in the file, in file order. The `x` in `ubuntu:x:1000:1000:...` is the password
     * placeholder, and counting it as the uid is how every user in a namespace ends up as 0.
     */
    fun passwd(): List<VmUser> = parse(vfs, PASSWD).map { fields ->
        VmUser(
            name = fields.getOrElse(0) { "" },
            uid = fields.getOrElse(2) { "0" }.toIntOrNull() ?: 0,
            gid = fields.getOrElse(3) { "0" }.toIntOrNull() ?: 0,
            gecos = fields.getOrElse(4) { "" },
            home = fields.getOrElse(5) { "/root" },
            shell = fields.getOrElse(6) { "/usr/bin/sh" },
        )
    }.filter { it.name.isNotEmpty() }

    /** Every group in the file, in file order. */
    fun group(): List<VmGroup> = parse(vfs, GROUP).map { fields ->
        VmGroup(
            name = fields.getOrElse(0) { "" },
            // name:x:GID:members — the `x` again is not a field this VM reads.
            gid = fields.getOrElse(2) { "0" }.toIntOrNull() ?: 0,
            members = fields.getOrElse(3) { "" }.split(',').filter { it.isNotEmpty() },
        )
    }.filter { it.name.isNotEmpty() }

    fun current(): VmUser = active

    fun currentName(): String = active.name

    fun uid(): Int = active.uid

    fun gid(): Int = active.gid

    fun home(): String = active.home

    /** @return the user with [uid], or null. `ps` prints names, so this is how a pid gets one. */
    fun nameOf(uid: Int): String? = passwd().firstOrNull { it.uid == uid }?.name

    fun byName(name: String): VmUser? = passwd().firstOrNull { it.name == name }

    /**
     * Switches to [name], refusing anything the passwd file does not list. An unknown user is
     * [FsErrno.NO_SUCH_FILE] rather than a silent success, because `su nobody` that quietly stays
     * who it was is the worst possible answer.
     */
    fun switchTo(name: String): VmUser {
        val user = byName(name) ?: throw FsException(FsErrno.NO_SUCH_FILE, name)
        active = user
        return user
    }

    fun backToDefault(): VmUser = switchTo(DEFAULT_USER)

    /**
     * The three account files the kernel writes. [passwd] and [group] are the real Ubuntu 24.04
     * shape — the system accounts a package script expects to find, `ubuntu` in `ubuntu` and `sudo`,
     * and `nobody` at 65534 — and [passwd] is parsed straight back out of this text by [passwd], so
     * the file and the code cannot disagree.
     *
     * Every shell here is `/bin/sh`, not `/bin/bash`, and the reason is the honest one: `sh` is the
     * shell this userland has. The shadow file locks both accounts with `!` because **there is no
     * password to authenticate against** — the namespace has no PAM, no crypt and no login record,
     * which is exactly the reason `su` and `sudo` can be honest here instead of pretending to check
     * a credential.
     */
    fun seedFiles(): Map<String, String> = mapOf(
        PASSWD to PASSWD_TEXT,
        GROUP to GROUP_TEXT,
        SHADOW to shadow(),
    )

    /** Locked for both real accounts, `*` for the system ones, which is what Debian ships. */
    private fun shadow(): String = buildString {
        appendLine("root:!:19000:0:99999:7:::")
        appendLine("$DEFAULT_USER:!:19000:0:99999:7:::")
        for (name in listOf("daemon", "bin", "sys", "sync", "games", "man", "lp", "mail", "news", "uucp", "proxy", "www-data", "backup", "list", "nobody")) {
            appendLine("$name:*:19000:0:99999:7:::")
        }
    }

    /** `awk -F: '{print $1}'` over a small file: no reason to build a stream for a dozen lines. */
    private fun parse(vfs: Vfs, path: String): List<List<String>> {
        val text = try {
            String(vfs.readBytes(path), Charsets.UTF_8)
        } catch (e: FsException) {
            return emptyList()
        }
        return text.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line -> line.split(':') }
    }

    companion object {
        const val PASSWD = "/etc/passwd"
        const val GROUP = "/etc/group"
        const val SHADOW = "/etc/shadow"

        /** Ubuntu 24.04's account file, minus the accounts that own nothing here. */
        private val PASSWD_TEXT = """
            root:x:0:0:root:/root:$SHELL
            daemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin
            bin:x:2:2:bin:/bin:/usr/sbin/nologin
            sys:x:3:3:sys:/dev:/usr/sbin/nologin
            sync:x:4:65534:sync:/bin:/bin/sync
            games:x:5:60:games:/usr/games:/usr/sbin/nologin
            man:x:6:12:man:/var/cache/man:/usr/sbin/nologin
            lp:x:7:7:lp:/var/spool/lpd:/usr/sbin/nologin
            mail:x:8:8:mail:/var/mail:/usr/sbin/nologin
            news:x:9:9:news:/var/spool/news:/usr/sbin/nologin
            uucp:x:10:10:uucp:/var/spool/uucp:/usr/sbin/nologin
            proxy:x:13:13:proxy:/bin:/usr/sbin/nologin
            www-data:x:33:33:www-data:/var/www:/usr/sbin/nologin
            backup:x:34:34:backup:/var/backups:/usr/sbin/nologin
            list:x:38:38:Mailing List Manager:/var/list:/usr/sbin/nologin
            nobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin
            $DEFAULT_USER:x:$DEFAULT_UID:$DEFAULT_UID:Ubuntu:/home/$DEFAULT_USER:$SHELL
        """.trimIndent() + "\n"

        private val GROUP_TEXT = """
            root:x:0:
            daemon:x:1:
            bin:x:2:
            sys:x:3:
            adm:x:4:syslog
            tty:x:5:
            disk:x:6:
            lp:x:7:
            mail:x:8:
            news:x:9:
            uucp:x:10:
            man:x:12:
            proxy:x:13:
            kmem:x:15:
            dialout:x:20:
            cdrom:x:24:$DEFAULT_USER
            floppy:x:25:
            tape:x:26:
            sudo:x:27:$DEFAULT_USER
            audio:x:29:
            dip:x:30:$DEFAULT_USER
            www-data:x:33:
            backup:x:34:
            operator:x:37:
            list:x:38:
            irc:x:39:
            src:x:40:
            shadow:x:42:
            utmp:x:43:
            video:x:44:
            sasl:x:45:
            plugdev:x:46:
            staff:x:50:
            games:x:60:
            users:x:100:
            nogroup:x:65534:
            $DEFAULT_USER:x:$DEFAULT_UID:
        """.trimIndent() + "\n"

        /** The shell this userland has, which is why no account claims bash. */
        const val SHELL = "/bin/sh"
        const val DEFAULT_USER = "ubuntu"
        const val DEFAULT_UID = 1000
    }
}
