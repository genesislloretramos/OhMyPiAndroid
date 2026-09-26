package omp.shell.cmd

import omp.shell.exec.CommandTable

/**
 * The text group. Registration is an explicit list, so `help` and the registry read the same map
 * and cannot drift; adding a command is one line here plus one file.
 */
object TextCommands {

    fun register(table: CommandTable) {
        table.register(
            Echo,
            Printf,
            Grep,
            Sort,
            Uniq,
            Cut,
            Sed,
            Tr,
            Rev,
            Nl,
            Tee,
            Wc,
            Xargs,
            Seq,
            Sha256sum,
            Md5sum,
            Base64,
        )
    }
}
