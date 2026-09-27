package omp.vm.service

import omp.shell.PlatformServices
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.Vfs
import omp.vm.VmProcessTable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * systemd-lite for a namespace that has no init: unit files, a real journal, an enable symlink and
 * a state file per unit. Nothing here forks anything.
 *
 * That is the honest limit and it is stated once: **there is no `systemd` to ask and no process to
 * start.** The VM's own code is the userland, so `start` records a unit as active, gives it the pid
 * its `ExecStart` names when that pid is one the VM's process table owns, and writes a journal line
 * saying exactly that. `status` prints the state, since when, the pid when there is one, and the
 * unit's own journal. A unit whose `ExecStart` names no pid is `active (exited)`, which is what
 * systemd calls a `Type=oneshot` unit that finished.
 *
 * **Every timestamp here is wall-clock, and there is deliberately no `now` parameter.** A `Since:`
 * and a journal line describe the same moment, so they have to come from the same clock, and the
 * journal takes its stamp from [PlatformServices.wallClockMillis]. The VM's other clock is
 * monotonic — milliseconds since boot — and using it for a date prints `Thu Jan 01 1970` next to a
 * journal line from 2023, which is two clocks for one event. A duration is the only thing the
 * monotonic clock is good for, and nothing in this class measures one, so there is nothing to pass
 * it for.
 */
class ServiceManager(
    private val vfs: Vfs,
    private val services: PlatformServices,
    private val processes: VmProcessTable,
    private val hostName: () -> String,
) {
    /** One unit as its own file describes it. */
    data class Unit(
        val name: String,
        val description: String,
        val execStart: String,
        val unitFilePath: String,
        val wantedBy: String,
        val type: String,
    ) {
        /** `/proc/12/exe` names a pid this namespace owns; nothing else does. */
        val namedPid: Int?
            get() {
                val m = Regex("^/proc/(\\d+)/exe$").find(execStart.trim()) ?: return null
                return m.groupValues[1].toIntOrNull()
            }
    }

    /** What `systemctl status` prints, derived from the state file and the process table. */
    data class UnitState(
        val unit: Unit,
        val state: String,
        val sinceMillis: Long,
        val pid: Int,
        val reason: String?,
    ) {
        /** The word systemd uses, with the shape `active (exited)`. */
        fun describe(): String = when (state) {
            "active" -> if (pid > 0) "active (running)" else "active (exited)"
            "failed" -> "failed"
            "inactive" -> "inactive (dead)"
            else -> state
        }
    }

    // ---- units -------------------------------------------------------------------------

    /**
     * Writes the units this kernel really has, and nothing else.
     *
     * There is no `ssh.service`: there is no sshd, nothing is listening, and a unit that claims
     * otherwise is a lie a user would spend an afternoon on. `openssh-server` in the package index
     * has no programs for the same reason.
     */
    fun ensureUnits(): List<String> {
        val created = ArrayList<String>()
        for ((name, text) in UNITS) {
            if (writeIfMissing(unitPath(name), text)) created += unitPath(name)
        }
        enableDefaults()
        // The units this kernel has are the units it wants running, so they are enabled here rather
        // than left for a user to enable: `is-enabled` then tells the truth about the base system.
        return created
    }

    /**
     * The units this kernel has are the units it wants running, so they are enabled here rather than
     * left for a user to discover. [boot] then starts them, which is why a fresh VM has a running
     * `omp-vmd` and not three disabled files.
     */
    private fun enableDefaults() {
        for ((name, _) in UNITS) {
            val unitName = name.substringBefore('.')
            if (unit(unitName) != null && !isEnabled(unitName)) enable(unitName)
        }
    }

    /** Every unit file present, in name order. */
    fun units(): List<Unit> {
        val out = ArrayList<Unit>()
        for (entry in listDir(UNIT_DIR)) {
            if (!entry.endsWith(".service")) continue
            val name = entry.removeSuffix(".service")
            parse("$UNIT_DIR/$entry")?.let { out += it }
        }
        return out.sortedBy { it.name }
    }

    fun unit(name: String): Unit? {
        val file = nameOf(name)
        return parse("$UNIT_DIR/$file")
    }

    private fun nameOf(name: String): String = if (name.endsWith(".service")) name else "$name.service"

    private fun unitPath(name: String): String = "$UNIT_DIR/${nameOf(name)}"

    /** A unit file, parsed. The Description and ExecStart are the only two that mean anything here. */
    private fun parse(path: String): Unit? {
        val text = try {
            String(vfs.readBytes(path), Charsets.UTF_8)
        } catch (e: FsException) {
            return null
        }
        val name = path.substringAfterLast('/').removeSuffix(".service")
        var description = ""
        var exec = ""
        var type = "simple"
        var wantedBy = ""
        for (line in text.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Description=") -> description = trimmed.substringAfter('=')
                trimmed.startsWith("ExecStart=") -> exec = trimmed.substringAfter('=')
                trimmed.startsWith("Type=") -> type = trimmed.substringAfter('=')
                trimmed.startsWith("WantedBy=") -> wantedBy = trimmed.substringAfter('=')
            }
        }
        return Unit(name, description, exec, path, wantedBy, type)
    }

    // ---- state -------------------------------------------------------------------------

    fun stateOf(name: String): UnitState? {
        val unit = unit(name) ?: return null
        val recorded = readState(unit.name)
        if (recorded == null) {
            return UnitState(unit, "inactive", 0L, 0, null)
        }
        val parts = recorded.lines().map { it.substringBefore('=') to it.substringAfter('=') }.toMap()
        val state = parts["State"] ?: "inactive"
        val pid = parts["MainPID"]?.toIntOrNull() ?: 0
        val since = parts["Since"]?.toLongOrNull() ?: 0L
        val reason = parts["Reason"]?.takeIf { it.isNotEmpty() }
        // A recorded pid that the process table no longer has is failed, wherever the file says.
        if (state == "active" && pid > 0 && processes.of(pid) == null) {
            return UnitState(unit, "failed", since, pid, "Main process exited, status=gone/MainPID-gone")
        }
        return UnitState(unit, state, since, pid, reason)
    }

    /** @return a line per unit that was started, stopped or failed, in unit order. */
    fun boot(): List<String> {
        val lines = ArrayList<String>()
        for (unit in units()) {
            val recorded = readState(unit.name)
            if (recorded == null) {
                if (isEnabled(unit.name)) {
                    val started = start(unit.name, "boot")
                    lines += "start $started"
                }
                continue
            }
            val parts = recorded.lines().map { it.substringBefore('=') to it.substringAfter('=') }.toMap()
            val pid = parts["MainPID"]?.toIntOrNull() ?: 0
            if (parts["State"] != "active") continue
            if (pid > 0 && processes.of(pid) == null) {
                fail(unit.name, "Main process exited, status=gone/MainPID-gone")
                lines += "fail ${unit.name}.service: Main process exited, status=gone/MainPID-gone"
            } else {
                lines += "keep ${unit.name}.service (${if (pid > 0) "pid $pid" else "exited"})"
            }
        }
        return lines
    }

    /** @return one line describing what happened, which is what the caller prints. */
    fun start(name: String, caller: String = "systemctl"): String {
        val unit = unit(name) ?: return "$name: unit not found"
        val pid = unit.namedPid ?: 0
        writeState(unit.name, "active", pid, services.wallClockMillis(), "")
        log(unit.name, "info", "Started $caller: ${unit.description}.")
        return "${unit.name}.service"
    }

    fun stop(name: String, caller: String = "systemctl"): String {
        val unit = unit(name) ?: return "$name: unit not found"
        writeState(unit.name, "inactive", 0, 0L, "")
        log(unit.name, "info", "Stopped $caller: ${unit.description}.")
        return "${unit.name}.service"
    }

    fun restart(name: String): String {
        stop(name, "systemctl restart")
        return start(name, "systemctl restart")
    }

    fun fail(name: String, reason: String) {
        val unit = unit(name) ?: return
        writeState(unit.name, "failed", 0, services.wallClockMillis(), reason)
        log(unit.name, "err", reason)
    }

    // ---- enable / disable --------------------------------------------------------------

    fun isEnabled(name: String): Boolean {
        val unit = unit(name) ?: return false
        return exists(wantsPath(unit))
    }

    fun enable(name: String): String {
        val unit = unit(name) ?: return "$name: unit not found"
        val path = wantsPath(unit)
        try {
            val parent = path.substringBeforeLast('/')
            if (!exists(parent)) {
                try {
                    vfs.mkdir(parent)
                } catch (e: FsException) {
                    if (e.errno != FsErrno.FILE_EXISTS) return "${unit.name}.service: ${e.errno.text}"
                }
            }
            if (exists(path)) vfs.delete(path)
            // Relative, the way systemd writes it: an absolute target would be a path into the VM's
            // own namespace, which is dangling the moment anyone looks at it with java.io.File.
            vfs.symlink("../${unit.name}.service", path)
        } catch (e: FsException) {
            return "${unit.name}.service: ${e.errno.text}"
        }
        log(unit.name, "info", "Created symlink $path -> ../${unit.name}.service.")
        return "${unit.name}.service"
    }

    fun disable(name: String): String {
        val unit = unit(name) ?: return "$name: unit not found"
        val path = wantsPath(unit)
        try {
            if (exists(path)) vfs.delete(path)
        } catch (e: FsException) {
            return "${unit.name}.service: ${e.errno.text}"
        }
        log(unit.name, "info", "Removed $path.")
        return "${unit.name}.service"
    }

    /**
     * `/etc/systemd/system/multi-user.target.wants/<unit>.service` — the real layout. The target name
     * comes from the unit's own `WantedBy=`, and defaults to multi-user.target the way systemd does.
     */
    private fun wantsPath(unit: Unit): String {
        val target = unit.wantedBy.ifEmpty { DEFAULT_TARGET }
        return "$UNIT_DIR/$target.wants/${unit.name}.service"
    }

    // ---- the journal --------------------------------------------------------------------

    fun journalFile(unit: String): String = "$JOURNAL/${nameOf(unit)}.log"

    /** Appends one line in the format `/var/log/journal/README` documents. */
    fun log(unit: String, priority: String, message: String, pid: Int = processes.current()?.pid ?: 1) {
        val name = nameOf(unit)
        // A journal line answers "when", so it takes the wall clock; the monotonic clock is for
        // durations like uptime, and a journal full of 1970 timestamps helps nobody.
        val line = "${timestamp(services.wallClockMillis())} $bootId $name[$pid]: $priority: $message"
        try {
            if (!exists(JOURNAL)) vfs.mkdir(JOURNAL)
            vfs.writeBytes(journalFile(unit), (readTextOrEmpty(journalFile(unit)) + line + "\n").toByteArray(Charsets.UTF_8))
        } catch (e: FsException) {
            // A journal that cannot be written is a boot-log line, not a failed command.
        }
    }

    /** Every journal line, newest last, in unit order. */
    fun journal(unit: String? = null): List<Line> {
        val out = ArrayList<Line>()
        for (entry in listDir(JOURNAL)) {
            if (!entry.endsWith(".log")) continue
            val unitName = entry.removeSuffix(".log")
            if (unit != null && nameOf(unit) != unitName) continue
            for (line in readTextOrEmpty("$JOURNAL/$entry").lines()) {
                // A line that does not parse is not a line this journalctl can filter, and printing
                // it as if it parsed would be worse than leaving it out of a filtered view.
                if (line.isNotBlank()) parseLine(line)?.let { out += Line(unitName, it, line) }
            }
        }
        return out
    }

    /** One journal line, parsed. [raw] is kept for the lines that do not parse. */
    data class Line(val unit: String, val stamp: Stamp, val raw: String) {
        val time: String get() = stamp.time
        val boot: String get() = stamp.boot
        val pid: Int get() = stamp.pid
        val priority: String get() = stamp.priority
        val message: String get() = stamp.message

        /** What `journalctl` prints with no options: the host's own syslog layout. */
        fun format(host: String): String = "$time $host $unit[$pid]: $message"
    }

    data class Stamp(val time: String, val boot: String, val unit: String, val pid: Int, val priority: String, val message: String)

    private fun parseLine(line: String): Stamp? {
        val m = Regex("^(\\S+) (\\S+) (\\S+?)\\[(\\d+)\\]: (\\S+): (.*)$").find(line) ?: return null
        return Stamp(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4].toInt(), m.groupValues[5], m.groupValues[6])
    }

    /** The syslog priority order `journalctl -p` filters with. */
    fun priorityRank(priority: String): Int = when (priority.lowercase(Locale.US)) {
        "emerg", "panic" -> 0
        "alert" -> 1
        "crit" -> 2
        "err", "error" -> 3
        "warning", "warn" -> 4
        "notice" -> 5
        "info" -> 6
        "debug" -> 7
        else -> 6
    }

    // ---- the file helpers ---------------------------------------------------------------

    private fun readState(name: String): String? {
        val path = "$STATE/${nameOf(name)}.state"
        return if (exists(path)) readTextOrEmpty(path) else null
    }

    private fun writeState(name: String, state: String, pid: Int, since: Long, reason: String) {
        val path = "$STATE/${nameOf(name)}.state"
        val text = buildString {
            appendLine("State=$state")
            appendLine("MainPID=$pid")
            appendLine("Since=$since")
            appendLine("Reason=$reason")
        }
        try {
            if (!exists(path.substringBeforeLast('/'))) vfs.mkdir(path.substringBeforeLast('/'))
            vfs.writeBytes(path, text.toByteArray(Charsets.UTF_8))
        } catch (e: FsException) {
            // A read-only rootfs cannot hold state; the unit is then simply never active.
        }
    }

    private fun listDir(path: String): List<String> = try {
        vfs.readDir(path).map { it.name }
    } catch (e: FsException) {
        emptyList()
    }

    private fun exists(path: String): Boolean = try {
        vfs.stat(path)
        true
    } catch (e: FsException) {
        false
    }

    private fun readTextOrEmpty(path: String): String = try {
        String(vfs.readBytes(path), Charsets.UTF_8)
    } catch (e: FsException) {
        ""
    }

    private fun writeIfMissing(path: String, text: String): Boolean {
        if (exists(path)) return false
        return try {
            vfs.writeBytes(path, text.toByteArray(Charsets.UTF_8))
            true
        } catch (e: FsException) {
            false
        }
    }

    private fun timestamp(millis: Long): String = ISO.get()!!.format(Date(millis))

    companion object {
        const val UNIT_DIR = "/etc/systemd/system"
        const val WANTS = "/etc/systemd/system/multi-user.target.wants"

        /** The target a unit is enabled into when its own `WantedBy=` says nothing. */
        const val DEFAULT_TARGET = "multi-user.target"
        const val JOURNAL = "/var/log/journal"
        const val STATE = "/var/lib/omp/units"

        /** One boot of the app is one boot of the VM; `journalctl -b` filters on this. */
        @Volatile
        var bootId: String = "boot-0"
            private set

        fun newBoot(millis: Long) {
            bootId = "boot-" + java.lang.Long.toHexString(millis)
        }

        private val ISO = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }

        /**
         * The three units this kernel really has.
         *
         * No `ssh.service`: there is no sshd in this userland and nothing listens, so a unit that
         * claimed otherwise would be a lie with a `systemctl status` attached to it. The `openssh-server`
         * package has no programs for exactly the same reason.
         */
        private val UNITS = listOf(
            "omp-vmd.service" to """
                [Unit]
                Description=omp VM kernel (this process is the kernel)
                After=local-fs.target

                [Service]
                Type=simple
                # There is no separate omp-vmd process: the VM kernel is the shell's own process,
                # pid 1 in the VM's pid namespace. The ExecStart says so, and `systemctl` reads the
                # pid out of it rather than inventing one.
                ExecStart=/proc/1/exe
                Restart=on-failure

                [Install]
                WantedBy=multi-user.target
            """.trimIndent() + "\n",
            "systemd-journald.service" to """
                [Unit]
                Description=Journal Service
                After=syslog.target

                [Service]
                Type=oneshot
                RemainAfterExit=yes
                # No process either: every line in /var/log/journal is written by the VM kernel as
                # the thing it happens. `journalctl` reads those text files, not a binary journal.
                ExecStart=/usr/bin/sh -c true

                [Install]
                WantedBy=multi-user.target
            """.trimIndent() + "\n",
            "vm-hostbridge.service" to """
                [Unit]
                Description=omp host bridge (/dev/omp-host)
                After=local-fs.target

                [Service]
                Type=oneshot
                RemainAfterExit=yes
                # The bridge is a device node, not a daemon: /dev/omp-host answers with the platform
                # facts at the moment it is read. Starting it records the node as the active bridge.
                ExecStart=/usr/bin/sh -c true

                [Install]
                WantedBy=multi-user.target
            """.trimIndent() + "\n",
        )
    }
}
