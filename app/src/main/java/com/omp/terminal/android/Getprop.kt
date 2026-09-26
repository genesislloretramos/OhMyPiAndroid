package com.omp.terminal.android

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext

/**
 * The real `getprop` reads `android.os.SystemProperties`, which has no SDK entry at all: the class
 * is `@hide` and reflecting it has been blocked since API 28. This parses the four property files
 * the framework itself ships and overlays the readable `Settings` tables, so a key that exists on
 * the device is reported and a key that cannot be read is simply absent — never invented.
 */
@CommandSpec(
    name = "getprop",
    synopsis = "[NAME]",
    group = "android",
    notes = "build.prop files plus the readable Settings tables (prefixed global:, secure:, system:); " +
        "there is no SystemProperties API for an app, so keys the shell may not read are absent rather than guessed",
)
object Getprop : Command {

    override fun run(ctx: ExecContext): Int {
        val properties = LinkedHashMap(ctx.services.buildProperties())
        properties.putAll(ctx.services.systemPropertyOverrides())
        val name = ctx.args.firstOrNull()
        if (name == null) {
            for (key in properties.keys.sorted()) {
                ctx.outLine("$key=${properties[key]}")
            }
        } else {
            // An unknown key is an empty line, exactly as the real tool prints it.
            ctx.outLine(properties[name] ?: "")
        }
        return ExecContext.EXIT_OK
    }
}
