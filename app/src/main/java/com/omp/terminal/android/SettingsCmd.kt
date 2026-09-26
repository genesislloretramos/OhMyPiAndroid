package com.omp.terminal.android

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.shell.exec.printUsage

/**
 * Read-only `settings`. `settings put` is not registered: writing needs
 * `WRITE_SECURE_SETTINGS`, a signature-level permission no ordinary app can hold, so the honest
 * answer to a write is the reason, not a silent no-op.
 */
@CommandSpec(
    name = "settings",
    synopsis = "get <global|secure|system> KEY | list <global|secure|system>",
    group = "android",
    notes = "read only; settings put is not registered because it needs WRITE_SECURE_SETTINGS, " +
        "a signature-level permission no ordinary app can hold",
)
object SettingsCmd : FileCommand() {

    private val namespaces = setOf("global", "secure", "system")

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        val sub = operands.firstOrNull()
        return when (sub) {
            "get" -> get(ctx, operands.getOrNull(1), operands.getOrNull(2))
            "list" -> list(ctx, operands.getOrNull(1))
            null -> ctx.fail("settings: no sub-command; available: get, list")
            else -> ctx.fail(
                "settings: '$sub' requires WRITE_SECURE_SETTINGS, a signature-level permission " +
                    "no ordinary app can hold",
            )
        }
    }

    private fun get(ctx: ExecContext, namespace: String?, key: String?): Int {
        if (namespace == null || key == null) {
            ctx.errLine("settings: get needs a namespace and a key")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }
        if (namespace !in namespaces) return unknownNamespace(ctx, namespace)
        // Unset and unreadable are the same empty line the real tool prints for both.
        ctx.outLine(ctx.services.settingGet(namespace, key) ?: "")
        return ExecContext.EXIT_OK
    }

    private fun list(ctx: ExecContext, namespace: String?): Int {
        if (namespace == null) {
            ctx.errLine("settings: list needs a namespace")
            printUsage(ctx.stderr, usageLine(ctx))
            return ExecContext.EXIT_USAGE
        }
        if (namespace !in namespaces) return unknownNamespace(ctx, namespace)
        val table = try {
            ctx.services.settingList(namespace)
        } catch (e: SecurityException) {
            return ctx.fail("settings: $namespace: the Settings provider refused to read this table")
        }
        for (key in table.keys.sorted()) {
            ctx.outLine("$key=${table[key]}")
        }
        return ExecContext.EXIT_OK
    }

    private fun unknownNamespace(ctx: ExecContext, namespace: String): Int =
        ctx.fail("settings: unknown namespace $namespace; use global, secure or system")
}
