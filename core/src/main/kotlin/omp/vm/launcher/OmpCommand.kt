package omp.vm.launcher

import omp.shell.exec.Command
import omp.shell.exec.CommandSpec
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.exec.printUsage
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.VNodeType
import omp.shell.fs.Vfs
import omp.vm.workspace.Entry
import omp.vm.workspace.MetadataState
import omp.vm.workspace.Workspace
import omp.vm.workspace.WorkspaceList

/**
 * `omp`: the conversation launcher, and the only command that both namespaces share.
 *
 * **One command, two namespaces, one code path.** There is no `omp` for the phone and another for
 * the VM. Which one you are in is decided by the session's own [omp.shell.fs.Vfs]: the namespace
 * answers for `/mnt/omp` and the phone answers for the shared-storage path, and
 * [Containers.locate] asks the session rather than being told. So a user who opens the app on the
 * phone and types `omp photos`, and the same user who types it after `vm enter`, get the same
 * answer — and the answer is the same *folder*, because the namespace is a bind and not a copy.
 *
 * **The flow is the one a cold start has.** Bare `omp` makes sure the container exists, prints the
 * table and asks: a number to continue, or `n` for a new one. That question is asked of a
 * terminal and of nothing else — a pipe, a script and `vm exec` all get the table and the two
 * options and a command that returns, because a command waiting forever for a keypress that can
 * never arrive is worse than one that explains itself. And Ctrl-C at the prompt is not a failure
 * to recover from: the answer is read before anything is created, so cancelling leaves no
 * half-created folder and no session state behind.
 *
 * **Every refusal names its real reason.** A missing all-files grant is the `grant-storage`
 * sentence, a container that cannot be made is the errno that stopped it, and a conversation whose
 * folder was deleted under the app is `No such file or directory` with the list of what is left.
 * A path is printed as the real one only when [omp.shell.fs.RealVfs.hostPathOf] said so, and no
 * line in this command is a claim the filesystem has not confirmed.
 *
 * **`omp rm` is the one destructive thing here**, so it follows the rule `vm reset` follows: it
 * refuses without `--force`, after printing the real path and what is under it, and a folder this
 * app did not make says why it is being treated as somebody else's.
 */
@CommandSpec(
    name = "omp",
    synopsis = "[NAME | new [NAME] | ls | rm NAME --force]",
    group = "system",
    notes = "conversations are plain folders in Internal storage ▸ Documents ▸ omp, one folder each; " +
        "the folder owns its name, needs the grant-storage all-files grant, and is the same folder in the VM at /mnt/omp",
)
class OmpCommand : Command {

    override fun run(ctx: ExecContext): Int {
        val args = ctx.args
        return when (val sub = args.firstOrNull()) {
            null -> opener(ctx)
            "new" -> create(ctx, args.drop(1))
            "ls" -> list(ctx, args.drop(1))
            "rm" -> remove(ctx, args.drop(1))
            // A bare name is a conversation, not a subcommand: `omp notes` is what a user types
            // after reading the table above, and inventing a namespace for it would be a worse
            // answer than "no such subcommand".
            else -> if (args.size == 1) open(ctx, sub) else usage(ctx)
        }
    }

    // ---- the flow the user described -------------------------------------------------------

    /**
     * Bare `omp`: the list of conversations, and a question.
     *
     * The container is made first, because the table is about a directory and there is no honest
     * listing of a directory that is not there. The question comes last and is the only part that
     * can block, which is why everything it decides has been decided before it is asked.
     */
    private fun opener(ctx: ExecContext): Int {
        val container = Containers.locate(ctx)
        val problem = Containers.ensure(container)
        if (problem != null) return ctx.fail("$USAGE: $problem")
        val workspace = container.workspace(clock(ctx))
        for (line in WorkspaceList.render(workspace)) ctx.outLine(line)
        // The question is printed only when one is about to be asked. A pipe, a background job and
        // a redirected stdout all end up here, and a prompt that is never answered is worse than no
        // prompt: in a file it reads as though the command stopped, and on a terminal it is a
        // question with nobody to answer it. What those three get instead is the same choice stated
        // as a fact, and the two commands that need no question.
        if (!mayPrompt(ctx)) {
            ctx.outLine("$USAGE: stdin is not the terminal this command owns, so nothing is asked")
            ctx.outLine("$USAGE: on a terminal this prompt takes a number from the list above, or n for a new one")
            ctx.outLine("$USAGE: 'omp new' and 'omp ls' work here")
            return ExecContext.EXIT_OK
        }
        ctx.outLine()
        ctx.outLine("type a number to continue, or n for a new one:")
        ctx.flush()
        val answer = ask(ctx)
        if (answer is Answer.Cancelled) return cancelled(ctx)
        if (answer is Answer.Empty) return emptyAnswer(ctx)
        val typed = (answer as Answer.Typed).text
        if (typed.equals("n", ignoreCase = true)) return create(ctx, emptyList())
        val index = typed.toIntOrNull()
            ?: return ctx.fail("$USAGE: '$typed' is not a number and not n, so nothing was opened")
        val entry = workspace.list().getOrNull(index - 1)
            ?: return ctx.fail("$USAGE: there is no conversation $index in the list above, so nothing was opened")
        return enter(ctx, entry)
    }

    /**
     * Reads the answer off the terminal, the way [omp.shell.cmd.Less] reads its `/` pattern: one
     * byte at a time from `ctx.stdin`, which in a session is the very [omp.shell.InputChannel] the
     * line editor reads, so a Ctrl-C arrives here as well as on the job it would cancel.
     *
     * **Enter on an empty prompt is its own answer and not a cancellation.** It is the most likely
     * mis-key there is — the second Enter of a double-Enter — and reporting it as `^C` would put a
     * Ctrl-C in `$?` that never happened. Nothing is created or opened in either case, which is
     * the whole point of asking before doing anything; only the sentence differs.
     */
    private fun ask(ctx: ExecContext): Answer {
        val line = StringBuilder()
        while (true) {
            if (ctx.cancelled.get()) return Answer.Cancelled
            val c = ctx.stdin.read()
            if (c < 0 || c == CTRL_C) return Answer.Cancelled
            if (c == '\n'.code || c == '\r'.code) {
                val typed = line.toString().trim()
                return if (typed.isEmpty()) Answer.Empty else Answer.Typed(typed)
            }
            if (c == 0x08 || c == 0x7F) {
                if (line.isNotEmpty()) line.setLength(line.length - 1)
            } else {
                line.append(c.toChar())
            }
        }
    }

    /** What the prompt was answered with. A sealed class because all three say something different. */
    private sealed class Answer {
        data class Typed(val text: String) : Answer()

        /** Enter on an empty prompt. */
        object Empty : Answer()

        /** A Ctrl-C, a closed channel, or a job cancelled while the question was on screen. */
        object Cancelled : Answer()
    }

    /**
     * Whether this command may take the terminal, which takes three things being true.
     *
     * **It has to be the terminal.** [omp.shell.InputChannel.owns] is the authority: a pipe, a
     * script and `vm exec` — which runs a line in a throwaway session over a channel nothing will
     * ever write to — all hand a command a stdin that is not this session's, and `isTty` alone
     * would say yes to all three.
     *
     * **It has to be the foreground.** A `&` job gets the same channel and the same `isTty`, and
     * the REPL is back at its prompt before the job has printed a line: a question asked there
     * would take keystrokes the line editor expects, on a thread nobody is waiting for, and the
     * two of them would fight over one keyboard. [omp.shell.Session.foreground] is the same fact
     * Ctrl-C uses, and its [omp.shell.exec.JobGroup.background] says which kind of job this is.
     *
     * **And stdout has to be a terminal**, or the question would be asked into a pipe and never
     * seen.
     */
    private fun mayPrompt(ctx: ExecContext): Boolean {
        val job = ctx.session.foreground ?: return false
        return ctx.isTty && !job.background && ctx.session.input?.owns(ctx.stdin) == true
    }

    /** `omp new [name]`: a folder, made and entered, with nothing asked. */
    private fun create(ctx: ExecContext, operands: List<String>): Int {
        if (operands.size > 1) {
            ctx.errLine("$USAGE new: one name at a time")
            printUsage(ctx.stderr, "$USAGE new [NAME]")
            return ExecContext.EXIT_USAGE
        }
        val container = Containers.locate(ctx)
        val problem = Containers.ensure(container)
        if (problem != null) return ctx.fail("$USAGE: $problem")
        val workspace = container.workspace(clock(ctx))
        val typed = operands.firstOrNull()?.takeIf { it.isNotBlank() }
        val entry = try {
            workspace.create(typed)
        } catch (e: FsException) {
            return ctx.fail("$USAGE: ${e.path ?: container.root}: ${e.errno.text}")
        }
        stamp(container, workspace, entry, typed)
        return enter(ctx, entry, "created and in")
    }

    /**
     * `omp ls`: the table, and nothing else. It does not make the container, because a listing that
     * created what it was listing would answer a question the user did not ask with a side effect
     * they did not want — and it enters nothing, so it can be run from a script.
     */
    private fun list(ctx: ExecContext, operands: List<String>): Int {
        if (operands.isNotEmpty()) {
            ctx.errLine("$USAGE ls: it takes no arguments")
            printUsage(ctx.stderr, "$USAGE ls")
            return ExecContext.EXIT_USAGE
        }
        val container = Containers.locate(ctx)
        if (!container.isReady) return ctx.fail("$USAGE: ${container.refusal}")
        for (line in WorkspaceList.render(container.workspace(clock(ctx)))) ctx.outLine(line)
        return ExecContext.EXIT_OK
    }

    /**
     * `omp NAME`: continue a conversation. A name that is not there is `No such file or directory`
     * and the list, once — a folder the user deleted in a file manager is the ordinary way to get
     * here, and the two together are the whole answer: which name, and what is left.
     */
    private fun open(ctx: ExecContext, name: String): Int {
        val container = Containers.locate(ctx)
        if (!container.isReady) return ctx.fail("$USAGE: ${container.refusal}")
        val workspace = container.workspace(clock(ctx))
        val entry = try {
            workspace.open(name)
        } catch (e: FsException) {
            return absent(ctx, container, workspace, name, e)
        }
        return enter(ctx, entry)
    }

    /**
     * `omp rm NAME [--force]`: takes a conversation away, and says exactly what it took.
     *
     * Without `--force` nothing happens at all, and the lines printed instead name the real path on
     * the phone, the size of what is under it, and the fact that nothing was deleted — the same
     * rule `vm reset` follows for the same reason. A folder this app did not make says so in the
     * refusal as well, because `--force` on a folder somebody made by hand is a decision about
     * their files and not about a conversation.
     */
    private fun remove(ctx: ExecContext, operands: List<String>): Int {
        val unknown = operands.firstOrNull { it.startsWith("-") && it != FORCE }
        if (unknown != null) {
            ctx.errLine("$USAGE rm: unknown option '$unknown'")
            printUsage(ctx.stderr, "$USAGE rm NAME [$FORCE]")
            return ExecContext.EXIT_USAGE
        }
        val names = operands.filter { it != FORCE }
        if (names.size != 1) {
            ctx.errLine(
                if (names.isEmpty()) "$USAGE rm: name the conversation to delete"
                else "$USAGE rm: one conversation at a time",
            )
            printUsage(ctx.stderr, "$USAGE rm NAME [$FORCE]")
            return ExecContext.EXIT_USAGE
        }
        val container = Containers.locate(ctx)
        if (!container.isReady) return ctx.fail("$USAGE rm: ${container.refusal}")
        val workspace = container.workspace(clock(ctx))
        val name = names[0]
        val entry = try {
            workspace.open(name)
        } catch (e: FsException) {
            return absent(ctx, container, workspace, name, e)
        }
        val counted = contentsOf(container.vfs, entry.path)
        val foreign = foreignReason(entry)
        val noun = plural(counted.entries, "entry", "entries")
        val size = "${counted.entries} $noun under it, ${counted.bytes} bytes" + counted.unlistable()
        if (!operands.contains(FORCE)) {
            ctx.outLine("$USAGE rm: this would delete ${entry.hostPath} and everything under it, forever")
            ctx.outLine("$USAGE rm: $size")
            if (foreign != null) ctx.outLine("$USAGE rm: ${entry.hostPath} was not made by this app — $foreign")
            ctx.outLine("$USAGE rm: nothing was deleted; pass $FORCE to do it")
            return ExecContext.EXIT_GENERAL_ERROR
        }
        val gone = deleteTree(container.vfs, entry.path)
        if (gone.failure != null) {
            val goneNoun = plural(gone.removed, "entry", "entries")
            return ctx.fail(
                "$USAGE rm: cannot delete ${gone.failure.first}: ${gone.failure.second.errno.text}; " +
                    "${gone.removed} $goneNoun under it had already been deleted",
            )
        }
        ctx.outLine("$USAGE rm: removed ${entry.hostPath}, $size")
        if (foreign != null) ctx.outLine("$USAGE rm: it was not a folder this app made — $foreign")
        // A session left standing in a folder that is gone would answer `pwd` with a path to
        // nothing, so the working directory moves to the container and says that it did.
        return if (within(ctx.session.cwd, entry.path)) stepOutOf(ctx, container) else ExecContext.EXIT_OK
    }

    // ---- entering ---------------------------------------------------------------------------

    /**
     * Puts the session in [entry] and says where that is, in both of its names.
     *
     * The environment goes in the *session's* map, not in `ctx.env`: the latter is a copy the
     * shell makes for one command, and a variable that vanishes when the next one starts is not a
     * variable. `PWD` moves with the working directory for the same reason.
     */
    private fun enter(ctx: ExecContext, entry: Entry, verb: String = "in"): Int {
        val session = ctx.session
        session.oldPwd = session.cwd
        session.cwd = entry.path
        session.env["OLDPWD"] = session.oldPwd
        session.env["PWD"] = entry.path
        session.env[ENV_WORKSPACE] = entry.path
        session.env[ENV_WORKSPACE_REAL] = entry.hostPath
        ctx.outLine("$USAGE: $verb ${entry.name}")
        ctx.outLine("  $ENV_WORKSPACE=${entry.path}")
        ctx.outLine("  $ENV_WORKSPACE_REAL=${entry.hostPath}")
        return ExecContext.EXIT_OK
    }

    /** The working directory the container itself, for a session left standing in a deleted folder. */
    private fun stepOutOf(ctx: ExecContext, container: Container): Int {
        val session = ctx.session
        session.oldPwd = session.cwd
        session.cwd = container.root
        session.env["OLDPWD"] = session.oldPwd
        session.env["PWD"] = container.root
        ctx.outLine("$USAGE: the working directory was inside it; you are now in ${container.root}")
        return ExecContext.EXIT_OK
    }

    /** What `omp rm` says about a folder this app did not make, or null when it did. */
    private fun foreignReason(entry: Entry): String? = when (entry.meta) {
        MetadataState.RECORDED -> null
        MetadataState.NONE -> "there is no ${Workspace.METADATA} in it"
        MetadataState.CORRUPT -> "${Workspace.METADATA} is not in this app's format"
        MetadataState.UNREADABLE -> "${Workspace.METADATA} could not be read"
    }

    /** The missing-conversation diagnostic: the errno with the path, and the list, once. */
    private fun absent(
        ctx: ExecContext,
        container: Container,
        workspace: Workspace,
        name: String,
        e: FsException,
    ): Int {
        ctx.errLine("$USAGE: ${e.path ?: container.child(name)}: ${Errno.messageFor(e)}")
        val lines = try {
            WorkspaceList.render(workspace)
        } catch (listing: FsException) {
            listOf("the listing is unavailable as well: ${listing.errno.text}")
        }
        for (line in lines) ctx.errLine(line)
        return ExecContext.EXIT_GENERAL_ERROR
    }

    /**
     * Records what only this command knows, in the folder's own metadata file.
     *
     * [Workspace.create] has already written the version, the name, the time and both paths, and
     * the name it wrote is the name on disk — the `-2` a collision added included. The title is
     * what the *user* asked for, which is the one case where those differ and the difference is
     * worth keeping: a second `photos` is `photos-2` because there was already a `photos`, and a
     * file that remembered only the second name would lose the fact.
     */
    private fun stamp(container: Container, workspace: Workspace, entry: Entry, typed: String?) {
        val values = LinkedHashMap<String, String>()
        if (!typed.isNullOrBlank()) values[Workspace.TITLE] = typed
        container.distro?.let { values[Workspace.DISTRO] = it }
        if (values.isNotEmpty()) workspace.writeMetadata(entry, values)
    }

    /** The clock the model is built with, read at the call so a name and its stamp agree. */
    private fun clock(ctx: ExecContext): () -> Long = { ctx.services.wallClockMillis() }

    /**
     * A prompt the user walked away from, in the shell's own words for one: a diagnostic and the
     * status `^C` gives, so `$?` says the prompt was cancelled rather than that something failed.
     */
    private fun cancelled(ctx: ExecContext): Int {
        ctx.errLine("$USAGE: the prompt was cancelled; no conversation was created and none was opened")
        return ExecContext.EXIT_INTERRUPTED
    }

    /**
     * A prompt that was answered with nothing: the most likely mis-key there is, and the one this
     * must not report as a cancellation. Nothing is opened, and the status is a usage error rather
     * than `^C`, so `$?` does not claim a Ctrl-C that never happened.
     */
    private fun emptyAnswer(ctx: ExecContext): Int {
        ctx.errLine("$USAGE: nothing was typed at the prompt, so nothing was opened")
        return ExecContext.EXIT_USAGE
    }

    private fun usage(ctx: ExecContext): Int {
        printUsage(ctx.stderr, USAGE_TEXT)
        return ExecContext.EXIT_USAGE
    }

    // ---- what a delete has to count and remove ----------------------------------------------

    /**
     * How much is under a path, and whether all of it could be looked at.
     *
     * [entries] counts **every** name a user would see in a file manager, directories included: a
     * folder holding `holiday.jpg`, `sub/` and `sub/deep.txt` is three things, and a prompt that
     * said two is a prompt about a folder nobody has. [unlisted] is the number of directories
     * whose contents could not be read, which is reported rather than folded into the total —
     * their size is unknown, and a delete prompt that hides what it cannot see is the one prompt
     * that must not be relied on.
     */
    private data class Contents(val entries: Int, val bytes: Long, val unlisted: Int) {

        /** The tail of the size line, empty when everything under it could be read. */
        fun unlistable(): String = if (unlisted == 0) "" else " ($unlisted could not be listed)"
    }

    /**
     * Every entry under [path], through the seam, so the number is about the same bytes a program
     * in either namespace would see.
     *
     * A directory counts as one entry and is then walked, because that is the number a file
     * manager shows. A link counts as the name it is and is never followed. **A directory that
     * cannot be listed counts as one entry and is not walked**, and the fact that it happened
     * travels with the count to the line that prints it: a subtree nobody can read is a subtree
     * whose size is unknown, and reporting it as zero would be a smaller number than the truth.
     */
    private fun contentsOf(vfs: Vfs, path: String): Contents {
        var entries = 0
        var bytes = 0L
        var unlisted = 0
        val stack = ArrayDeque<String>()
        stack.addLast(path)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = try {
                vfs.readDir(dir)
            } catch (e: FsException) {
                unlisted++
                continue
            }
            for (child in children) {
                entries++
                if (child.stat.type == VNodeType.DIRECTORY) {
                    stack.addLast(Workspace.child(dir, child.name))
                    continue
                }
                bytes += child.stat.size
            }
        }
        return Contents(entries, bytes, unlisted)
    }

    /**
     * Bottom-up, because a directory has to be empty before it can go, and a link is unlinked
     * rather than followed: a link points at a name inside the conversation, and following one
     * that pointed elsewhere would delete something the user never agreed to.
     *
     * **What went before the failure is part of the answer.** The walk cannot be atomic, so it
     * counts everything it removed on the way and hands it back with the failure: a user told
     * "cannot delete X" and not told that half their folder is already gone has been told a lie
     * by omission, on the one irreversible command in this slice.
     *
     * @return null when the tree is gone, or the path that would not go with its errno and the
     * number of entries already deleted. Both halves are needed: naming the path beats "cannot
     * delete" with nothing after it, and the errno is what says whether this is a permission, a
     * file that is busy, or something this app cannot see at all.
     */
    private fun deleteTree(vfs: Vfs, path: String): Deletion {
        var removed = 0
        val children = try {
            vfs.readDir(path)
        } catch (e: FsException) {
            return Deletion(path to e, removed)
        }
        for (child in children) {
            val childPath = Workspace.child(path, child.name)
            if (child.stat.type == VNodeType.DIRECTORY) {
                val stuck = deleteTree(vfs, childPath)
                if (stuck.failure != null) return Deletion(stuck.failure, removed + stuck.removed)
                removed += 1 + stuck.removed
                continue
            }
            try {
                vfs.delete(childPath)
                removed++
            } catch (e: FsException) {
                return Deletion(childPath to e, removed)
            }
        }
        return try {
            vfs.rmdir(path)
            Deletion(null, removed + 1)
        } catch (e: FsException) {
            Deletion(path to e, removed)
        }
    }

    /**
     * What [deleteTree] did: [failure] is the path that would not go and the errno that stopped it,
     * or null when the tree is gone, and [removed] is how many entries went — including the top
     * directory itself on a clean run, because that is what a user means by "removed".
     */
    private class Deletion(val failure: Pair<String, FsException>?, val removed: Int)

    /** `entry` or `entries`: the one place a count is turned into a word. */
    private fun plural(count: Int, one: String, many: String): String = if (count == 1) one else many

    /** True when [path] is [dir] or something under it, on a path boundary. */
    private fun within(path: String, dir: String): Boolean =
        path == dir || path.startsWith(if (dir.endsWith('/')) dir else "$dir/")

    companion object {
        /** The namespace path of the conversation a session is in. `$PWD` says the same thing. */
        const val ENV_WORKSPACE = "OMP_WORKSPACE"

        /**
         * The real path of that conversation — the one a file manager, a USB cable or another app
         * needs, and the one the namespace deliberately does not have.
         */
        const val ENV_WORKSPACE_REAL = "OMP_WORKSPACE_REAL"

        private const val USAGE = "omp"
        private const val FORCE = "--force"
        private const val CTRL_C = 0x03
        private const val USAGE_TEXT =
            "omp [NAME|omp new [NAME]|omp ls|omp rm NAME $FORCE]"
    }
}
