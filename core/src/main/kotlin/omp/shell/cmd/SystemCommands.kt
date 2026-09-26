package omp.shell.cmd

import omp.shell.exec.CommandTable

/**
 * Commands that report on the device itself. They reach the platform only through
 * `PlatformServices`; the framework-specific ones (`getprop`, `pm`, `dumpsys`, `am`, `settings`,
 * `wm`, `screencap`, `curl`, `hostname`, `ifconfig`, `ip`) live in the `:app` module because they
 * need APIs that have no place in a JVM-only interface.
 */
object SystemCommands {
    fun register(table: CommandTable) {
        table.register(Uname, Uptime, Date, Free, Id, Whoami, Ps, Top, Mount, Df)
    }
}
