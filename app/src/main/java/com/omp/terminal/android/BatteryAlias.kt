package com.omp.terminal.android

import omp.shell.exec.CommandTable

/**
 * `battery` is a second name for the `dumpsys battery` implementation, not a second command: one
 * `BatteryInfo` read, one set of lines, two names. The alias points at `dumpsys` because the
 * registry resolves aliases by command name.
 */
object BatteryAlias {

    fun register(table: CommandTable) {
        table.registerAlias(
            "battery",
            "dumpsys",
            "android",
            "runs `dumpsys battery`: the same BatteryManager state under the short name",
        )
    }
}
