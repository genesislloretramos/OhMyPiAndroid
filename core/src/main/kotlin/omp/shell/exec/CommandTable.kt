package omp.shell.exec

import omp.shell.cmd.BuiltinCommands
import omp.shell.cmd.FileCommands
import omp.shell.cmd.SystemCommands
import omp.shell.cmd.TextCommands

/**
 * Name to command, plus the metadata `help` prints. Registration is one explicit list per group, so
 * `help` and the registry cannot drift: both read this map.
 *
 * `:app` adds the platform commands at start-up through [register]; nothing is discovered by
 * reflection, so an obfuscated release behaves exactly like a debug build.
 *
 * A fresh table starts empty: [registerDefaults] fills one with this shell's own vocabulary, and
 * [copy] hands back a table with the same commands in it, so a second namespace can answer a name
 * differently without either set being able to see the other. Every [Command] here is stateless, so
 * a copy shares the instances and only the names differ.
 */
class CommandTable {

    private val commands = LinkedHashMap<String, Command>()
    private val specs = LinkedHashMap<String, CommandSpec>()

    val all: Map<String, Command> get() = LinkedHashMap(commands)

    fun register(cmd: Command): CommandTable {
        val spec = cmd::class.java.getAnnotation(CommandSpec::class.java)
            ?: error("${cmd::class.java.simpleName} is missing @CommandSpec")
        commands[spec.name] = cmd
        specs[spec.name] = spec
        return this
    }

    fun register(vararg list: Command): CommandTable {
        for (c in list) register(c)
        return this
    }

    /** An alternate name for an already-registered command, with its own help line. */
    fun registerAlias(alias: String, target: String, group: String, notes: String = ""): CommandTable {
        val cmd = commands[target] ?: error("registerAlias: unknown target '$target'")
        commands[alias] = cmd
        specs[alias] = CommandSpec(alias, "alias of $target", group, notes)
        return this
    }

    fun lookup(name: String): Command? = commands[name]

    fun specOf(name: String): CommandSpec? = specs[name]

    fun names(): List<String> = commands.keys.toList()

    /** Names grouped for `help`, in registration order within a group. */
    fun byGroup(): List<Pair<String, List<String>>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for ((name, spec) in specs) out.getOrPut(spec.group) { ArrayList() } += name
        return out.toList()
    }

    /** The four builtin groups, in the order `help` lists them. */
    fun registerDefaults(): CommandTable {
        FileCommands.register(this)
        TextCommands.register(this)
        BuiltinCommands.register(this)
        SystemCommands.register(this)
        return this
    }

    /** A table with the same names, so a namespace can add or replace one without touching this. */
    fun copy(): CommandTable {
        val out = CommandTable()
        out.commands.putAll(commands)
        out.specs.putAll(specs)
        return out
    }

    companion object {
        /**
         * The phone's table: the builtins plus whatever `:app` registered into it at start-up. Every
         * static entry point below is this instance, so a caller that has not been threaded a
         * session still dispatches, and still dispatches the same way.
         */
        val global: CommandTable = CommandTable().registerDefaults()

        fun register(cmd: Command): CommandTable = global.register(cmd)

        fun register(vararg list: Command): CommandTable = global.register(*list)

        fun registerAlias(alias: String, target: String, group: String, notes: String = ""): CommandTable =
            global.registerAlias(alias, target, group, notes)

        fun lookup(name: String): Command? = global.lookup(name)

        fun specOf(name: String): CommandSpec? = global.specOf(name)

        fun names(): List<String> = global.names()

        fun byGroup(): List<Pair<String, List<String>>> = global.byGroup()
    }
}
