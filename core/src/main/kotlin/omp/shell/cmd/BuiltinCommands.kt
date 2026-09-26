package omp.shell.cmd

import omp.shell.exec.CommandTable

/**
 * The shell's own vocabulary: the things the shell itself has to know about, because no external
 * program can implement them. `help` reads the same table this registrar fills, so the two can
 * never drift.
 */
object BuiltinCommands {
    fun register(table: CommandTable) {
        table.register(
            Exit,
            Export,
            Unset,
            Env,
            Printenv,
            Set,
            Alias,
            Unalias,
            History,
            Type,
            Which,
            CommandCmd,
            Jobs,
            Wait,
            Kill,
            Sleep,
            True,
            False,
            Test,
            Clear,
            Reset,
            Help,
            GrantStorage,
        )
        table.registerAlias("man", "help", "builtins", "there are no man pages on Android; this is the same table")
        table.registerAlias("[", "test", "builtins", "the closing ] is required; ]] is not supported")
    }
}
