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
 */
object CommandTable {

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

    init {
        FileCommands.register(this)
        TextCommands.register(this)
        BuiltinCommands.register(this)
        SystemCommands.register(this)
    }
}
