package com.omp.terminal.android

import omp.shell.exec.CommandTable

/**
 * The commands that need a framework API.
 *
 * They live in `:app` because `:core` must stay a plain JVM library, but every value they print
 * still arrives through `PlatformServices`; the only `android.*` references in a command body are
 * the framework constants `dumpsys` and `pm` echo back (`BatteryManager`, `Build.VERSION`).
 *
 * Registration is this one explicit list, called once from the Activity, so `help` and the
 * registry cannot drift and nothing is found by reflection.
 */
object AndroidCommands {

    fun register(table: CommandTable) {
        table.register(
            Getprop,
            Pm,
            Am,
            Dumpsys,
            Curl,
            Screencap,
            SettingsCmd,
            Wm,
            Hostname,
            Ip,
            Ifconfig,
        )
        // The alias resolves against the table, so `dumpsys` has to be in it already.
        BatteryAlias.register(table)
    }
}
