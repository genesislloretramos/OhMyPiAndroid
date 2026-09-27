package omp.shell.exec

import omp.shell.Session
import omp.shell.fs.PathException
import omp.shell.fs.PathResolver
import omp.shell.parser.CommandNode
import omp.shell.parser.Expander
import omp.shell.parser.Parser
import omp.shell.parser.PipelineNode
import omp.shell.parser.Program
import omp.shell.parser.Redirect
import omp.shell.parser.ShellParseException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import omp.shell.fs.FsException
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * Runs a [Program]. Every pipeline stage is one [Command] invocation on its own daemon thread inside
 * a [JobGroup]; the REPL thread joins the last stage and takes its status.
 *
 * [table] is the session's, so a name a second namespace registered is dispatched here and one it
 * did not is not, even though both tables hold the same stateless command objects.
 */
class Shell(private val session: Session, val table: CommandTable = CommandTable.global) {

    val expander = Expander(session, ::capture)

    /** Where diagnostics from a command substitution go, so they are not captured into its value. */
    var topStderr: OutputStream = NullOutput

    /**
     * The job this thread is running inside, for the pipelines a stage starts for itself. Stages
     * each have a thread of their own, so this is per-stage and needs nothing but the thread; it
     * is set around the stage and cleared in the same breath, so no thread outlives its value.
     */
    private val currentGroup = ThreadLocal<JobGroup?>()

    /**
     * Whether this thread is inside a `$( )` substitution, which is the child scope: a
     * substitution is not the program the user typed, and what it does to `$?` and to the exit
     * request stays inside it.
     */
    private val substituting = ThreadLocal<Boolean>()

    /**
     * Keeps [FINISHED_JOBS_KEPT] finished background jobs, dropping the oldest first. A pid is
     * handed out in start order, so the lowest pid among the finished ones is the longest over.
     * The bound is a person starting jobs faster than they wait for them, not a number of jobs a
     * session runs: without it the table is every background job this session ever ran.
     */
    private fun retainFinishedJobs() {
        val done = session.jobs().filterNot { it.isRunning() }
        if (done.size <= FINISHED_JOBS_KEPT) return
        for (job in done.sortedBy { it.pid }.take(done.size - FINISHED_JOBS_KEPT)) session.removeJob(job.pid)
    }

    /** Runs one input line. A parse error is reported on stderr, not thrown at the caller. */
    fun executeLine(line: String, stdin: InputStream, stdout: OutputStream, stderr: OutputStream, tty: Boolean): Int {
        val program = try {
            Parser.parse(line)
        } catch (e: ShellParseException) {
            stderr.write(("sh: " + e.message + "\n").toByteArray(Charsets.UTF_8))
            return ExecContext.EXIT_USAGE
        }
        return executeProgram(program, stdin, stdout, stderr, tty)
    }

    fun executeProgram(program: Program, stdin: InputStream, stdout: OutputStream, stderr: OutputStream, tty: Boolean): Int {
        var status = session.lastStatus
        for (statement in program.statements) {
            status = runAndOr(statement.first, statement.rest, stdin, stdout, stderr, tty)
            recordStatus(status)
            if (session.exitRequested) break
            if (session.errexit && status != 0) break
        }
        return status
    }

    private fun runAndOr(
        first: PipelineNode,
        rest: List<Pair<String, PipelineNode>>,
        stdin: InputStream,
        stdout: OutputStream,
        stderr: OutputStream,
        tty: Boolean,
    ): Int {
        var status = runPipeline(first, stdin, stdout, stderr, tty)
        // After every statement, not only at the end of the line: `$?` in the next statement of
        // the same line is this one's answer.
        recordStatus(status)
        for ((op, pipeline) in rest) {
            if (op == "AND_IF" && status != 0) continue
            if (op == "OR_IF" && status == 0) continue
            status = runPipeline(pipeline, stdin, stdout, stderr, tty)
            recordStatus(status)
            if (session.exitRequested) break
        }
        return status
    }

    /**
     * Records `$?`, which belongs to the program the user typed. A substitution is that program's
     * child: it runs on a stage thread, and its own status is the value it hands back, not the one
     * the user goes on to read.
     */
    private fun recordStatus(status: Int) {
        if (substituting.get() != true) session.lastStatus = status
    }

    private fun runPipeline(node: PipelineNode, stdin: InputStream, stdout: OutputStream, stderr: OutputStream, tty: Boolean): Int {
        val stages = node.commands
        if (stages.isEmpty()) return ExecContext.EXIT_OK

        val pid = if (node.background) session.allocatePid() else 0
        // A stage starts pipelines of its own -- a `$( )` substitution is one -- and those belong
        // to the job that is running, which is the job `currentGroup` names on this thread.
        val group = JobGroup(pid, listOf(stages.first().words.firstOrNull()?.raw ?: ""), node.background, currentGroup.get())
        if (node.background) session.lastBackgroundPid = pid

        val n = stages.size
        val pipeIns = ArrayList<PipedInputStream>(n - 1)
        val pipeOuts = ArrayList<PipedOutputStream>(n - 1)
        val links = ArrayList<Closeable>()
        for (i in 0 until n - 1) {
            val inS = PipedInputStream(PIPE_BUFFER)
            // A PipedInputStream accepts exactly one writer, so the pair is built once and shared.
            pipeIns += inS
            pipeOuts += PipedOutputStream(inS).also { links += it }
            links += inS
        }

        val threads = ArrayList<Thread>(n)
        for (i in 0 until n) {
            val last = i == n - 1
            val stageIn: InputStream = if (i == 0) stdin else pipeIns[i - 1]
            val stageOut: OutputStream = if (last) stdout else pipeOuts[i]
            val t = Thread({
                // Whatever this stage starts for itself -- a `$( )` substitution is a whole
                // pipeline of its own -- belongs to this job, so the stage says which job it is
                // part of for as long as it is running.
                currentGroup.set(group)
                try {
                    // `last` is a position, not a destination: a command whose own output is
                    // redirected is not writing to the terminal whatever its place in the pipeline is.
                    val status = runStage(
                        stages[i], stageIn, stageOut, stderr, tty && last && !redirectsStdout(stages[i]), group,
                    )
                    // Only the last stage decides the pipeline's status. An earlier stage that
                    // returns later -- it is still being drained when the watcher counts down, or it
                    // comes back after that -- must not overwrite the status already reported, or
                    // `true | false` would answer with whatever the first stage happened to say.
                    if (last) group.status = status
                } finally {
                    currentGroup.remove()
                    // Without this the next stage blocks: a PipedInputStream only reports EOF once
                    // the writing end is closed, and it throws "Write end dead" once that thread is gone.
                    if (!last) closeQuietly(stageOut)
                    if (i != 0) closeQuietly(stageIn)
                }
            }, "omp-stage-$i")
            t.isDaemon = true
            threads += t
            group.addThread(t)
        }

        val watcher = Thread({
            try {
                // The last stage's status is the pipeline's, but the pipes must outlive every
                // stage: tearing them down when the last one returns throws away whatever an
                // earlier stage had already written.
                threads.last().join()
                for (i in 0 until threads.size - 1) {
                    // A stage still running at the boundary is not awaited and not left to find
                    // out: it is told, and the shell says it in a shape that cannot be read as a
                    // command's own error. `sh: name: ...` is what a failure looks like, and this
                    // is not one -- the command may well be perfectly happy, still reading, when
                    // the stage it belongs to stopped waiting for it.
                    threads[i].join(STAGE_DRAIN_MS)
                    if (threads[i].isAlive) {
                        group.cancel()
                        val name = stages[i].words.firstOrNull()?.raw ?: "stage $i"
                        stderr.write(("sh: abandoned: $name was still running when the last stage finished\n").toByteArray(Charsets.UTF_8))
                        threads[i].join(ABANDON_GRACE_MS)
                    }
                }
            } finally {
                group.finished.countDown()
                if (node.background) {
                    retainFinishedJobs()
                    closeQuietly(links)
                }
            }
        }, "omp-watch")
        watcher.isDaemon = true
        group.addThread(watcher)

        if (node.background) {
            session.addJob(group)
            group.start()
            stderr.write("[1] $pid\n".toByteArray(Charsets.UTF_8))
            return ExecContext.EXIT_OK
        }

        // Ctrl-C means the job the user is looking at, and that is the outermost one. A pipeline a
        // stage started for itself neither takes the slot nor leaves it empty on the way out: a
        // null slot is an interrupt that silently does nothing, which on a phone is the keyboard.
        val ownsSlot = session.foreground == null
        if (ownsSlot) session.foreground = group
        try {
            group.start()
            return group.join()
        } finally {
            if (ownsSlot && session.foreground === group) session.foreground = null
            closeQuietly(links)
        }
    }

    /**
     * Whether a command sends its own output somewhere other than the pipeline's stdout.
     *
     * [omp.shell.parser.Redirect.changesStdout] is the whole decision; this only says it for one
     * command node, because that is the granularity at which a redirect was typed.
     */
    private fun redirectsStdout(node: CommandNode): Boolean =
        node.redirects.any { it.changesStdout }

    private fun runStage(
        node: CommandNode,
        stdin: InputStream,
        stdout: OutputStream,
        stderr: OutputStream,
        tty: Boolean,
        group: JobGroup,
    ): Int {
        val argv = try {
            expandArgv(node)
        } catch (e: ShellParseException) {
            stderr.write(("sh: " + e.message + "\n").toByteArray(Charsets.UTF_8))
            return ExecContext.EXIT_USAGE
        } catch (e: PathException) {
            stderr.write(("sh: ${e.message}\n").toByteArray(Charsets.UTF_8))
            return ExecContext.EXIT_GENERAL_ERROR
        }
        if (argv.isEmpty()) {
            // `VAR=value` with no command word is a plain assignment and stays in the session.
            for ((name, word) in node.assignments) {
                try {
                    session.env[name] = expander.expandAssignment(word)
                } catch (e: ShellParseException) {
                    stderr.write(("sh: ${e.message}\n").toByteArray(Charsets.UTF_8))
                    return ExecContext.EXIT_USAGE
                }
            }
            return ExecContext.EXIT_OK
        }

        val env = LinkedHashMap(session.env)
        for ((name, word) in node.assignments) {
            try {
                env[name] = expander.expandAssignment(word)
            } catch (e: ShellParseException) {
                stderr.write(("sh: ${e.message}\n").toByteArray(Charsets.UTF_8))
                return ExecContext.EXIT_USAGE
            }
        }

        if (session.xtrace) {
            stderr.write((argv.joinToString(" ", postfix = " ") + "\n").toByteArray(Charsets.UTF_8))
        }


        val fds = applyRedirects(node.redirects, stdin, stdout, stderr)
        // A special built-in sees its assignment prefix as operands too, so `export FOO=bar` both
        // reports the name and keeps it, instead of treating the prefix as "no arguments".
        val registered = table.lookup(argv[0])
        val effective = if (registered is SessionAssignmentCommand && node.assignments.isNotEmpty()) {
            node.assignments.map { "${it.first}=${env[it.first]}" } + argv
        } else {
            argv
        }
        val ctx = ExecContext(effective, fds.first, fds.second, fds.third, env, session.services, session, tty, group.cancelled)
        val status = try {
            dispatch(ctx)
        } catch (e: IOException) {
            // The reader went away (`yes | head -1`); that is the pipe closing, not a failure.
            if (isBrokenPipe(e)) {
                ExecContext.EXIT_INTERRUPTED
            } else {
                ctx.errLine("sh: ${argv[0]}: ${Errno.messageFor(e)}")
                ExecContext.EXIT_GENERAL_ERROR
            }
        } catch (e: Throwable) {
            // Throwable, not Exception: a command that hits a missing class would otherwise take
            // the whole app down from a background stage thread.
            ctx.errLine("sh: ${argv[0]}: ${e.message ?: e::class.java.simpleName}")
            ExecContext.EXIT_GENERAL_ERROR
        }
        if (node.assignments.isNotEmpty()) {
            val cmd = registered
            if (cmd is SessionAssignmentCommand) {
                val applied = node.assignments.map { it.first to env.getValue(it.first) }
                cmd.applyPrefix(ctx, applied)
            }
        }
        ctx.flush()
        closeQuietly(fds.fourth)
        if (fds.second !== stdout) closeQuietly(fds.second)
        if (fds.third !== stderr) closeQuietly(fds.third)
        return status
    }

    private fun isBrokenPipe(e: IOException): Boolean {
        val m = e.message ?: return false
        return m.contains("Pipe closed") || m.contains("Write end dead") || m.contains("Broken pipe")
    }

    private fun expandArgv(node: CommandNode): List<String> {
        var words = expander.expandWords(node.words)
        if (words.isEmpty()) return words
        val alias = session.aliases[words[0]]
        if (alias != null) words = alias.trim().split(' ').filter { it.isNotEmpty() } + words.drop(1)
        return words
    }

    private fun dispatch(ctx: ExecContext): Int {
        val name = ctx.argv[0]
        table.lookup(name)?.let { return it.run(ctx) }
        if (name.contains('/')) {
            val path = try {
                PathResolver.resolve(session, name)
            } catch (e: PathException) {
                ctx.errLine("sh: $name: ${e.message}")
                return ExecContext.EXIT_NOT_FOUND
            }
            val stat = try {
                session.vfs.stat(path)
            } catch (e: FsException) {
                ctx.errLine("sh: $name: No such file or directory")
                return ExecContext.EXIT_NOT_FOUND
            }
            if (!stat.executable) {
                ctx.errLine("sh: $name: Permission denied")
                return ExecContext.EXIT_NOT_EXECUTABLE
            }
            // There is no exec path on purpose: a child would inherit this app's SELinux domain and
            // hit the same /proc closure, so it would buy nothing and print OEM-dependent text.
            ctx.errLine("sh: $name: cannot execute binary file: Exec format error")
            return ExecContext.EXIT_NOT_EXECUTABLE
        }
        ctx.errLine("sh: $name: command not found")
        return ExecContext.EXIT_NOT_FOUND
    }

    private data class Fds(
        val first: InputStream,
        val second: OutputStream,
        val third: OutputStream,
        val fourth: List<Closeable>,
    )

    private fun applyRedirects(
        redirects: List<Redirect>,
        stdin: InputStream,
        stdout: OutputStream,
        stderr: OutputStream,
    ): Fds {
        var inS = stdin
        var outS = stdout
        var errS = stderr
        val opened = ArrayList<Closeable>()
        for (r in redirects) {
            val target = try {
                expander.expandWords(listOf(r.target))
            } catch (e: ShellParseException) {
                stderr.write(("sh: ${e.message}\n").toByteArray(Charsets.UTF_8))
                continue
            }
            val name = target.firstOrNull() ?: ""
            val dupTo = when (name) {
                "&1" -> 1
                "&2" -> 2
                "&-" -> -1
                else -> 0
            }
            if (dupTo != 0) {
                when {
                    r.fd == 1 && dupTo == 2 -> outS = errS
                    r.fd == 2 && dupTo == 1 -> errS = outS
                    r.fd == 2 && dupTo == -1 -> errS = NullOutput
                    r.fd == 1 && dupTo == -1 -> outS = NullOutput
                }
                continue
            }
            if (name.isEmpty()) continue
            val path = try {
                PathResolver.resolve(session, name)
            } catch (e: PathException) {
                stderr.write(("sh: ${r.op} $name: ${e.message}\n").toByteArray(Charsets.UTF_8))
                continue
            }
            try {
                if (r.fd == 0) {
                    inS = session.vfs.openRead(path).also { opened += it }
                } else {
                    val f = session.vfs.openWrite(path, r.append)
                    opened += f
                    if (r.stderrToo) {
                        outS = f
                        errS = f
                    } else if (r.fd == 1) {
                        outS = f
                    } else {
                        errS = f
                    }
                }
            } catch (e: Exception) {
                stderr.write(("sh: ${r.op} $name: ${Errno.messageFor(e)}\n").toByteArray(Charsets.UTF_8))
            }
        }
        return Fds(inS, outS, errS, opened)
    }

    /** Runs a program list with stdout captured; trailing newlines are stripped, as `$( )` does. */
    fun capture(body: String): String {
        val buffer = ByteArrayOutputStream()
        try {
            val program = Parser.parse(body)
            runSubstitution(program, buffer)
        } catch (e: ShellParseException) {
            topStderr.write(("sh: ${e.message}\n").toByteArray(Charsets.UTF_8))
        }
        return String(buffer.toByteArray(), Charsets.UTF_8).trimEnd('\n')
    }

    /**
     * Runs a substitution as a child of whatever is running it, which is what it is. `$(exit)`
     * ends the substitution and not the program typed around it -- `executeProgram` stops on the
     * request, and it is put back as it was found on the way out -- and while the substitution
     * runs, `$?` is the outer program's to move and this one does not touch it.
     */
    private fun runSubstitution(program: Program, buffer: ByteArrayOutputStream) {
        val outerExit = session.exitRequested
        substituting.set(true)
        try {
            executeProgram(program, ByteArrayInputStream(EMPTY), buffer, topStderr, false)
        } finally {
            substituting.remove()
            session.exitRequested = outerExit
        }
    }

    companion object {
        const val PIPE_BUFFER = 64 * 1024

        /** How long an earlier stage may keep writing after the last stage has returned. */
        const val STAGE_DRAIN_MS = 2000L

        /** How long an abandoned stage gets to notice it was abandoned, before the shell moves on. */
        const val ABANDON_GRACE_MS = 250L

        /** How many finished background jobs stay answerable to `wait <pid>`. */
        const val FINISHED_JOBS_KEPT = 32
        private val EMPTY = ByteArray(0)

        val NullOutput: OutputStream = object : OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) {}
        }

        fun closeQuietly(c: Closeable?) {
            try {
                c?.close()
            } catch (e: Exception) {
            }
        }

        fun closeQuietly(cs: List<Closeable>) {
            for (c in cs) closeQuietly(c)
        }
    }
}
