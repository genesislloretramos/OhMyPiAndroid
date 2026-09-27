package omp.vm.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import omp.vm.VmKernel
import omp.vm.VmUser


/**
 * The one place every identity-changing command points at: the real uid is a file away, and a
 * user who has just been told they are root deserves to be told where the truth is.
 */
private const val APP_UID_HINT = "the real one is in /sys/omp/android/uid"

/**
 * Whether a passwd entry's shell is one this rootfs can actually run.
 *
 * `/bin/sh` is the namespace's own shell — the one every real account here has — and the program
 * files are the rest. Everything else, which in this rootfs means every `/usr/sbin/nologin`, is a
 * restricted shell that does not exist on the disk, and switching to it would set `SHELL` to a path
 * the user cannot run and cannot find.
 */
private fun isRunnableShell(kernel: omp.vm.VmKernel, shell: String): Boolean =
    shell == "/bin/sh" || omp.vm.VmExec.programName(kernel.vfs, shell) != null
private fun adopt(ctx: ExecContext, user: VmUser) {
    for (env in listOf(ctx.env, ctx.session.env)) {
        env["HOME"] = user.home
        env["USER"] = user.name
        env["LOGNAME"] = user.name
        env["SHELL"] = user.shell
    }
}

/**
 * `su` and `sudo`, the two commands that are about privilege — and the two that must not lie about
 * it.
 *
 * **The omp userland has no privilege boundary, and these commands never pretend otherwise.** Every
 * file in the namespace belongs to the app's own uid, so there is no `setuid` to drop, no
 * `CAP_SETUID` to hold and no second process to confuse: `su root` changes the name the session
 * answers to, and `sudo id` reports uid 0 for a userland that is uid 10123 on the device. The
 * kernel here declines to enforce a boundary it cannot enforce, and a `sudo` that implied one would
 * be the most misleading line in the whole VM.
 *
 * What they *do* do is the part that is real: they change the session's identity for the duration of
 * the command, they record it in the journal in sudo's own log-line shape, and they put the target
 * user's home into the environment — `sudo` for the duration of one command, `su` for the session,
 * and the working directory never moves, because real `su` does not move it either. A user who
 * types `sudo -u nobody id` gets `id`'s honest answer about `nobody`, which is the point.
 *
 * `su` also refuses a target whose passwd shell is not a program in this rootfs, so it can never
 * set `SHELL` to a path that is not there. `PATH` is left alone: it is the same for every namespace
 * user and must never gain the phone's `/system/bin`.
 */
@CommandSpec(
    name = "su",
    synopsis = "[-] [USER]",
    group = "vm",
    notes = "changes the session's VM user; there is no privilege boundary here to cross, and this does not claim to cross one",
)
object Su : FileCommand() {
    override val flagSpec = ""
    override val longOptions = mapOf("login" to false, "shell" to true, "command" to true)

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val kernel = requireKernel(ctx) ?: return ExecContext.EXIT_GENERAL_ERROR
        val name = operands.firstOrNull() ?: "root"
        val user = kernel.users.byName(name)
        if (user == null) {
            ctx.errLine("su: user $name does not exist or the user entry does not contain all the required fields")
            return ExecContext.EXIT_GENERAL_ERROR
        }
        // A passwd entry whose shell is not a program in this rootfs is a restricted account, and
        // real su will not run one. There is no /etc/shells here to consult, so the rootfs itself
        // is the authority: /bin/sh and the program files are the shells that exist, and a target
        // pointing at anything else is refused rather than advertised as a shell that is not there.
        if (!isRunnableShell(kernel, user.shell)) {
            ctx.errLine("su: ${user.name}'s shell ${user.shell} is not a program in this userland; refusing to switch")
            return ExecContext.EXIT_GENERAL_ERROR
        }
        val before = kernel.users.currentName()
        kernel.users.switchTo(name)
        kernel.units.log("su", "info", "Successful su for $before to $name by the session")
        ctx.outLine("switched to $name")
        // The name changed; nothing was crossed. Said here, where the user is looking, because a
        // root prompt with no such line is the one thing in this VM that could mislead.
        ctx.outLine("This changes the name the session answers to and nothing else: every file here is the app's uid ($APP_UID_HINT).")
        // The environment follows the user, or a prompt would show one home and `~` another.
        // The *session's* environment is what a later command reads: a command's own `ctx.env` is a
        // per-stage copy, so writing only there would change nothing outside this stage.
        adopt(ctx, user)
        return ExecContext.EXIT_OK
    }
}

/**
 * `sudo`. Bare, it prints the privileges the user has — which is the honest list, and the honest
 * list is short. With a command, it runs that command as the target user and logs the line sudo logs.
 */
@CommandSpec(
    name = "sudo",
    synopsis = "[-l] [-u USER] COMMAND [ARG ...]",
    group = "vm",
    notes = "records the identity for one command; the namespace is already the app's uid, so this crosses no real boundary and says so",
)
object Sudo : FileCommand() {
    override val flagSpec = "ul"
    override val valueSpec = "u:"
    override val longOptions = mapOf("user" to true, "list" to false)

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val kernel = requireKernel(ctx) ?: return ExecContext.EXIT_GENERAL_ERROR
        val target = options["u"] ?: options["user"] ?: "root"
        // `sudo -l` is the list, which is also what bare sudo is: this userland's privilege list is
        // the same short and honest list either way, so the flag has nothing to add to it.
        if (operands.isEmpty() || flags.contains('l') || options.containsKey("list")) return privileges(ctx, kernel)

        val user = kernel.users.byName(target)
        if (user == null) {
            ctx.errLine("sudo: unknown user $target")
            return ExecContext.EXIT_GENERAL_ERROR
        }
        // The child's argv is the command it runs and that command's arguments — putting the target
        // user in argv[0] would make `sudo id` run `id` with "id" as an operand.
        val argv = operands
        val line = "${kernel.users.currentName()}@omp-ubuntu : TTY=unknown ; PWD=${ctx.env["PWD"] ?: "/"} ; " +
            "USER=$target ; COMMAND=${operands.first()}"
        kernel.units.log("sudo", "info", line)

        val before = kernel.users.current()
        kernel.users.switchTo(target)
        val savedHome = ctx.env["HOME"]
        val savedUser = ctx.env["USER"]
        ctx.env["HOME"] = user.home
        ctx.env["USER"] = user.name
        return try {
            val command = ctx.session.table.lookup(operands[0])
            if (command == null) {
                ctx.errLine("sudo: ${operands[0]}: command not found")
                ExecContext.EXIT_NOT_FOUND
            } else {
                val child = ExecContext(
                    argv, ctx.stdin, ctx.stdout, ctx.stderr, ctx.env,
                    ctx.services, ctx.session, ctx.isTty, ctx.cancelled,
                )
                command.run(child)
            }
        } finally {
            // The identity lasts for exactly one command, which is the whole contract.
            before?.let { kernel.users.switchTo(it.name) }
            savedHome?.let { ctx.env["HOME"] = it }
            savedUser?.let { ctx.env["USER"] = it }
            // After the child, so the child's own output is the command's and this is the last word.
            ctx.errLine("sudo: $target was recorded for one command; no boundary was crossed ($APP_UID_HINT)")
        }
    }

    /** Bare `sudo`: the honest list, which is the one `/etc/sudoers` really grants in here. */
    private fun privileges(ctx: ExecContext, kernel: VmKernel): Int {
        val user: VmUser = kernel.users.current()
        ctx.outLine("User ${user.name} may run the following commands on ${hostName(kernel)}:")
        // The rule, or the absence of one. A user with no entry in /etc/sudoers must not be shown
        // a grant: the file grants root and %sudo and nothing else, and this list is claimed to be
        // the file's own.
        if (user.uid == 0 || groupNamesFor(kernel, user).contains("sudo")) {
            ctx.outLine("    (ALL : ALL) ALL")
        } else {
            ctx.outLine("    (no rule: ${user.name} is in no group that /etc/sudoers grants)")
        }
        ctx.outLine("The omp userland enforces none of this: every file here is already the app's uid.")
        return ExecContext.EXIT_OK
    }

    private fun hostName(kernel: VmKernel): String = try {
        String(kernel.vfs.readBytes("/etc/hostname"), Charsets.UTF_8).trim()
    } catch (e: Exception) {
        "omp-ubuntu"
    }
}

/**
 * `login`, which prints what a real login does — `/etc/issue`, then `/etc/motd` — and then makes
 * this session that user.
 *
 * It cannot hand over to a nested REPL, and the reason is structural rather than a shortcut: a
 * command's [ExecContext] has streams, while a line editor needs the [omp.term.Screen] and the
 * [omp.shell.InputChannel] that live in [omp.shell.ShellSession]. So `login` switches the identity
 * of the session it is running in and says that is what it did.
 */
@CommandSpec(
    name = "login",
    synopsis = "[USER]",
    group = "vm",
    notes = "prints /etc/issue and /etc/motd, then makes this session that user; there is no nested REPL to hand over to",
)
object Login : FileCommand() {
    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val kernel = requireKernel(ctx) ?: return ExecContext.EXIT_GENERAL_ERROR
        val name = operands.firstOrNull() ?: kernel.users.currentName()
        val user = kernel.users.byName(name)
        if (user == null) {
            ctx.errLine("login: invalid user name $name")
            return ExecContext.EXIT_GENERAL_ERROR
        }
        if (!isRunnableShell(kernel, user.shell)) {
            ctx.errLine("login: ${user.name}'s shell ${user.shell} is not a program in this userland; refusing")
            return ExecContext.EXIT_GENERAL_ERROR
        }
        for (file in listOf("/etc/issue", "/etc/motd")) {
            val text = try {
                String(kernel.vfs.readBytes(file), Charsets.UTF_8)
            } catch (e: Exception) {
                continue
            }
            ctx.out(text)
        }
        kernel.users.switchTo(name)
        kernel.units.log("login", "info", "Session user is now $name")
        adopt(ctx, user)
        ctx.outLine("You are now logged in as $name. Type 'exit' to leave this session.")
        return ExecContext.EXIT_OK
    }
}
