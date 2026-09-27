package omp.agent

import omp.agent.http.HttpRefusal
import omp.agent.json.Json
import omp.agent.json.JsonException
import omp.agent.store.AgentState
import omp.agent.store.Conversation
import omp.agent.store.Entry
import omp.agent.store.Kind
import omp.agent.store.StateStatus
import omp.agent.store.TranscriptFullException
import omp.agent.tools.Outcome
import omp.agent.tools.Sandbox
import omp.agent.tools.ToolEnv
import omp.agent.tools.Tools
import omp.agent.tools.Verdict
import omp.shell.HttpStream
import omp.shell.exec.ExecContext
import omp.vm.launcher.Containers
import java.io.IOException
import java.net.HttpRetryException

/**
 * What one turn of a conversation ended as.
 *
 * Its own type and not a number because the four answers call for four different next lines: a
 * user whose answer was interrupted is owed a prompt, a user whose endpoint was refused is owed a
 * line about the endpoint, and a session that is not configured is owed a line about the state
 * file. Collapsing them into an exit status would make every caller print the union of them.
 */
enum class Turn {
    /** The model finished and the answer is on the screen and in the transcript. */
    ANSWERED,

    /** Ctrl-C between two events: what had arrived is kept, marked, and the terminal given back. */
    INTERRUPTED,

    /** No usable endpoint, no key, or a request that was refused. The transcript is unchanged. */
    REFUSED,

    /** Nothing in the state file to talk to a model with, and the line that says how to set it. */
    NOT_CONFIGURED,
}

/**
 * The agent, driven a line at a time.
 *
 * **It is a [Session] and not a [omp.shell.exec.Command] because one question is one turn, and a
 * turn is a thing with a beginning, a stream and an end.** The command is the loop around it — a
 * prompt, a read, an [ask], and a repeat — because everything interesting here is what happens
 * *during* a turn: a stream that must be printed as it arrives, a Ctrl-C that must land between
 * two events, a tool call the model split across three chunks, and a transcript that must have
 * every half of the exchange before the next question is asked. A command that blocked on one
 * prompt would put all four in a method nobody could drive twice.
 *
 * ### What it may see and what it may not
 *
 * The model is sent a system paragraph naming what this is, the conversation's **real** path, the
 * five tools it has, the one tool it asked for and does not have, and the fact that every path
 * they reach is inside this folder. It is sent the transcript. It is sent nothing else: not the
 * environment, not the key. The key is read by the app through [Agent.store] and handed to
 * [omp.shell.PlatformServices.httpStream] as one header value, and it appears in no request body,
 * no transcript line, no screen and no exception message — the one place it exists outside the
 * transport is the `List<Pair<String, String>>` in [exchange], and that list is never printed.
 *
 * ### The loop, and the two numbers that bound it
 *
 * A model that asks for a tool does not get an answer: it gets a a tool result and
 * another request, up to [Tools.MAX_ROUNDS] times for one question. **Every refusal is a tool
 * result** — an unknown name, arguments that are not JSON, a path outside the conversation, a
 * file too large, a user who pressed something other than `y` — because a tool loop that throws
 * ends a conversation over a call a model got slightly wrong, and a model that gets a sentence it
 * can read will try something else. **Reaching the cap is said out loud and written to the
 * transcript**, because a loop that stopped for a reason nobody could see is the same failure as
 * one that stopped for no reason at all.
 *
 * ### Why a Ctrl-C ends the command
 *
 * The shell cancels a job by setting one flag on it, and nothing ever clears it. A session that
 * swallowed that flag and asked for another question would find the flag still set and stop dead
 * on the first event of the next answer, which looks like a broken model rather than a cancelled
 * job. So an interrupted answer closes the stream, keeps what arrived, says so, and hands the
 * terminal back with the status `^C` gives. The user is at their own prompt in one keystroke, and
 * the next `omp` continues from the same folder with the same history.
 */
class Session(
    private val ctx: ExecContext,
    /** The conversation's namespace path, e.g. `/mnt/omp/fotos`. */
    private val dir: String,
    /** The same folder as a file manager shows it, e.g. `/storage/emulated/0/Documents/omp/fotos`. */
    private val realPath: String,
    /** `omp --yes`: every tool call in this turn is approved without asking, and recorded as such. */
    private val yes: Boolean = false,
) {

    private val vfs = ctx.session.vfs
    private val conversation = Conversation(vfs, dir)
    private val state = AgentState(vfs, dir)
    private val store = Agent.store(ctx)
    private val out = Writer(ctx)

    /**
     * The boundary every path a tool touches is checked against, and nothing else.
     *
     * The container is the launcher's own answer to "where are the conversations", asked of the
     * same session — so the two agree by construction, and there is no second place in this app
     * that could decide what the agent may write. [Sandbox][omp.agent.tools.Sandbox] is built
     * lazily because it refuses a project that is not inside the container, and that refusal has
     * to arrive as a tool result the model can read rather than as a constructor throwing on the
     * way to the prompt.
     */
    private val sandbox: Sandbox by lazy { Sandbox(vfs, Containers.locate(ctx).root, dir) }

    /**
     * The prompt, named for the folder: the directory owns its own name, so it is read off the
     * path rather than out of a title the user typed once and may have since renamed.
     */
    private val prompt = "omp[${dir.trimEnd('/').substringAfterLast('/')}] > "

    /** Set once the plaintext warning has been printed, so it is said once and not once per turn. */
    private var warned = false

    /**
     * The prompt loop, and the whole of what bare `omp` does inside a conversation.
     *
     * @return [ExecContext.EXIT_INTERRUPTED] for a Ctrl-C on an empty line, in the middle of an
     *   answer, or at an approval prompt, and [ExecContext.EXIT_USAGE] for a terminal that is not
     *   this session's own — a pipe, a script and `vm exec` all get a line saying so and a command
     *   that returns, because a command waiting forever for a keypress that can never arrive is
     *   worse than one that explains itself.
     */
    fun run(): Int {
        if (!Agent.mayPrompt(ctx)) {
            ctx.outLine("$TAG: stdin is not the terminal this command owns, so nothing is asked")
            ctx.outLine("$TAG: on a terminal this asks a question and waits for the answer")
            ctx.outLine("$TAG: 'omp update', 'omp key --show' and 'omp key --list' work here")
            ctx.flush()
            return ExecContext.EXIT_USAGE
        }
        greeting()
        // The Ctrl-C that stopped a previous answer is still a byte in the channel, because the
        // shell has already recorded it. This session did not start because of it, so it is taken
        // off the front here rather than being read as a second one.
        dropPendingInterrupt()
        while (true) {
            val question = prompt() ?: return ExecContext.EXIT_INTERRUPTED
            if (question.isEmpty()) continue
            when (ask(question)) {
                Turn.ANSWERED, Turn.REFUSED, Turn.NOT_CONFIGURED -> continue
                // The job is cancelled, and a cancelled job cannot ask another question: handing
                // the terminal back is what gets the user to a prompt they can use.
                Turn.INTERRUPTED -> return ExecContext.EXIT_INTERRUPTED
            }
        }
    }

    /**
     * One question, and as many answers as its tool calls take.
     *
     * The order here is the order the decisions are made in, and each of them refuses before it
     * touches anything: the state file, then the endpoint, then the key, then the socket. **The
     * user's own line is appended only once the endpoint has answered 2xx**, so a refused request
     * leaves the conversation exactly as it was — a question that never reached a model is not part
     * of the conversation with that model, and a transcript that said otherwise would put the
     * user's half of an exchange into the history of the next one.
     */
    fun ask(question: String): Turn {
        val found = state.read()
        if (found.status != StateStatus.LOADED) return unconfigured(found.message)
        val config = found.config
        val provider = config.provider
        val baseUrl = config.baseUrl
        val model = config.model
        if (provider.isNullOrBlank() || baseUrl.isNullOrBlank() || model.isNullOrBlank()) {
            return unconfigured(found.message)
        }

        val endpoint = Endpoint.of(baseUrl)
        if (endpoint is Endpoint.Refused) {
            ctx.errLine("$TAG: ${endpoint.reason}")
            ctx.errLine("$TAG: the base URL is '${endpoint.baseUrl}' in ${state.path}")
            ctx.flush()
            return Turn.REFUSED
        }
        val ready = endpoint as Endpoint.Ready

        val key = store.get(provider)
        if (key == null) {
            ctx.errLine(
                "$TAG: no key for '$provider'; 'omp key' stores one in ${store.pathOf(provider)}",
            )
            ctx.flush()
            return Turn.REFUSED
        }
        val warning = ready.warning
        if (warning != null && !warned) {
            warned = true
            out.line(warning)
        }
        return rounds(ready, provider, model, key, question)
    }

    /**
     * One question, and every round of tools it took to answer it.
     *
     * **The user's line is written once, on the first request only.** Every later request is
     * built from the transcript, which by then holds the model's tool request and its results —
     * re-adding the question would put it in the history twice and every endpoint that cares
     * about alternating roles would answer about something nobody asked.
     *
     * [Tools.MAX_ROUNDS] counts the requests made *because* a model asked for tools, not the
     * user's own question, so a model gets [Tools.MAX_ROUNDS] chances to act and not
     * [Tools.MAX_ROUNDS] chances to answer.
     */
    private fun rounds(
        endpoint: Endpoint.Ready,
        provider: String,
        model: String,
        key: String,
        question: String,
    ): Turn {
        var asked: String? = question
        var used = 0
        while (true) {
            val url = endpoint.url + Endpoint.CHAT
            when (val event = exchange(url, endpoint, provider, model, key, asked, used)) {
                is Event.Done -> return Turn.ANSWERED
                is Event.Interrupted -> return Turn.INTERRUPTED
                is Event.Calls -> {
                    if (used >= Tools.MAX_ROUNDS) return capped(model, provider, used)
                    used++
                    if (runCalls(event.calls, model, provider, used)) return Turn.INTERRUPTED
                    asked = null
                }
            }
        }
    }

    /**
     * The request and the read, and nothing else.
     *
     * [key] is put in one header and nowhere else; [body] is built with [Json] and
     * [omp.agent.json.Json.Obj.toCompactString] so the bytes on the wire are the bytes this code
     * describes. The header list holds the secret for as long as the call takes and is never
     * printed, logged, or put in an exception — a refusal is [HttpRefusal]'s sentence and nothing
     * else.
     *
     * [asked] is null on every request after the first, and that is the only thing that tells the
     * two apart: the user's line goes into the transcript here, once, after a 2xx.
     */
    private fun exchange(
        url: String,
        endpoint: Endpoint.Ready,
        provider: String,
        model: String,
        key: String,
        asked: String?,
        round: Int,
    ): Event {
        if (ctx.cancelled.get()) return Event.Interrupted
        val body = request(model, asked).toCompactString().toByteArray(Charsets.UTF_8)
        val headers = listOf(
            "Accept" to "text/event-stream",
            "Content-Type" to "application/json",
            "Authorization" to "Bearer $key",
        )
        val stream = try {
            ctx.services.httpStream(url, "POST", headers, body)
        } catch (e: HttpRetryException) {
            // **A 401 on a request with a body never comes back as a 401.** The JDK refuses to
            // answer for one: having been put into streaming mode by the body it cannot re-send,
            // it throws `cannot retry due to server authentication, in streaming mode` instead of
            // returning the status — which is a sentence about the JDK's own plumbing and the
            // least useful thing that could be shown to a user whose key was rejected. The status
            // is dropped and the status it stands for is put through [HttpRefusal] instead: a
            // wrong key is the single most common failure this loop has, and it is the one the
            // user can fix. 401 is the only status that reaches here — the other, 407, is a proxy
            // asking for credentials, and this app configures no proxy.
            ctx.errLine("$TAG: ${HttpRefusal.message(url, 401, "")}")
            ctx.flush()
            return Event.Done("")
        } catch (e: IOException) {
            // The transport raises the refusal itself, with the endpoint named in it; the message
            // is already a sentence for a user and the key is not in it.
            ctx.errLine("$TAG: ${e.message ?: "${endpoint.host} would not answer"}")
            ctx.flush()
            return Event.Done("")
        } catch (e: IllegalArgumentException) {
            ctx.errLine("$TAG: ${e.message ?: "the endpoint URL was refused"}")
            ctx.flush()
            return Event.Done("")
        }

        if (stream.status !in 200..299) {
            stream.close()
            ctx.errLine("$TAG: ${HttpRefusal.message(url, stream.status, "")}")
            ctx.flush()
            return Event.Done("")
        }

        // Past a 2xx the exchange happened, and the user's half of it belongs in the transcript
        // even if every event after this one goes wrong.
        if (asked != null) keep(Kind.USER, asked, mapOf(MODEL to model, PROVIDER to provider))
        return read(stream, model, provider, endpoint, round)
    }

    /**
     * The stream, event by event, until it ends, is interrupted, or it has asked for tools.
     *
     * **Every delta is printed as it arrives and flushed**, because a model that takes forty
     * seconds to answer looks exactly like one that has crashed, and the user watching tokens land
     * is the only evidence either way that anything is happening. The watchdog is for the other
     * case: the gap *between* one token and the next, where the socket is open and nothing is being
     * said and the reply may run for minutes.
     *
     * **A tool call does not end the read.** It comes split — the name in one chunk, the arguments
     * in the next, sometimes a character of a JSON string at a time — so the reader keeps going
     * until the stream ends and assembles what arrived. Stopping on the first `tool_calls` delta
     * would send half a call to the model as if it were the whole of one.
     *
     * **The flag alone is not a Ctrl-C, and the transport is what turns it into one.** A thread
     * parked inside [HttpStream.next] cannot look at a flag, so [Waiting] polls the shell's every
     * second and calls [HttpStream.close] when it sees one. How fast that lands is *not* this
     * loop's business, and it is now a number the transport can back:
     * [omp.shell.SseStream] sets a [omp.shell.SseStream.STREAM_POLL_MS] read timeout as a
     * checkpoint rather than a limit, and a read already parked in a socket comes back at the next
     * one — measured, because `disconnect()` gives the socket back to the far end but does not
     * interrupt a read that is already inside it. **So a Ctrl-C reaches a stalled answer within
     * about a quarter of a second, plus this loop's own one-second poll**, and neither of those
     * is a guess: the first is the transport's read timeout and the second is [POLL_MS] below.
     *
     * The other end of the same clock is [omp.shell.SseStream.STREAM_SILENCE_LIMIT_MS]: five
     * minutes, checked by the transport at each checkpoint, at which point the user is told which
     * host went quiet and for roughly how long. The watchdog's line at ten seconds is nothing to
     * do with that number — it is about a model that is thinking, and [Waiting]'s KDoc says so.
     *
     * The flag is asked of the event that came back too, after the read and not before it, because
     * a token the model produced while the user was already stopping it is one the user was never
     * shown.
     */
    private fun read(
        stream: HttpStream,
        model: String,
        provider: String,
        endpoint: Endpoint.Ready,
        round: Int,
    ): Event {
        val answer = StringBuilder()
        val pending = Pending()
        var cancelled = false
        var dropped = false
        var unreadable = 0
        val watchdog = Waiting(out, endpoint.host, { ctx.cancelled.get() }, { stream.close() })
        watchdog.start()
        try {
            stream.use { open ->
                while (true) {
                    val event = try {
                        open.next()
                    } catch (e: IOException) {
                        dropped = true
                        null
                    }
                    // Asked before the null test, because a close from the watchdog reaches this
                    // read as an end-of-stream or as an exception and both mean the same thing
                    // here: the user stopped it. Testing `event == null` first would report a
                    // cancelled answer as a dropped connection, and lose the fact that whatever
                    // had already arrived was kept.
                    if (ctx.cancelled.get()) {
                        cancelled = true
                        break
                    }
                    if (event == null) break
                    watchdog.arrived()
                    val chunk = try {
                        chunkOf(event)
                    } catch (e: JsonException) {
                        unreadable++
                        null
                    } ?: continue
                    if (chunk.calls != null) pending.add(chunk.calls)
                    if (chunk.text.isEmpty()) continue
                    answer.append(chunk.text)
                    out.put(chunk.text)
                }
            }
        } finally {
            watchdog.stop()
        }

        if (cancelled) {
            interrupted(answer, model, provider)
            return Event.Interrupted
        }
        if (unreadable > 0) {
            out.line("$TAG: $unreadable event(s) of the stream were not JSON and were left out")
        }
        if (answer.isNotEmpty() && !answer.endsWith("\n")) out.put("\n")
        if (pending.empty) {
            if (answer.isEmpty()) {
                out.line("$TAG: ${endpoint.host} finished without sending any text")
                return Event.Done("")
            }
            if (dropped) {
                out.line("$TAG: ${endpoint.host} dropped the connection after ${answer.length} characters")
            }
            keep(Kind.ASSISTANT, answer.toString(), mapOf(MODEL to model, PROVIDER to provider))
            return Event.Done(answer.toString())
        }
        val calls = pending.complete(round)
        out.line(
            "$TAG: the model is asking for ${calls.size} tool call(s): " +
                calls.joinToString(", ") { it.name.ifEmpty { "(no name)" } },
        )
        keep(
            Kind.ASSISTANT,
            answer.toString(),
            mapOf(
                MODEL to model,
                PROVIDER to provider,
                TOOL_CALLS to callsJson(calls).toCompactString(),
            ),
        )
        return Event.Calls(calls, answer.toString())
    }

    /**
     * One `data:` payload, as the two things this loop can do with it.
     *
     * A delta that is only a role, or only a `finish_reason`, has neither [Chunk.text] nor
     * [Chunk.calls] and prints nothing at all: the first chunk of every OpenAI-compatible stream is
     * `{"role":"assistant","content":""}` and a reader that treated an absent field as an empty
     * string would put a stray brace on the screen for every answer.
     */
    private fun chunkOf(payload: String): Chunk? {
        if (payload.isBlank()) return null
        val obj = Json.parse(payload) as? Json.Obj ?: return null
        val choice = obj.arr("choices")?.firstOrNull() as? Json.Obj ?: return null
        val delta = choice.field("delta") as? Json.Obj ?: return null
        val calls = delta.arr("tool_calls")
        val content = delta.str("content") ?: ""
        if (calls == null && content.isEmpty()) return null
        return Chunk(content, calls)
    }

    /**
     * The request body: the model, the whole conversation, the tools, and `stream` on.
     *
     * The transcript is read here rather than kept in memory, so a session that was interrupted,
     * and a session started a week later in the same folder, send the same history. System lines
     * this app wrote for the user are not sent: they are something the app said, not something a
     * model was told, and passing one along would put this app's own words in the model's mouth.
     *
     * **A tool request and its results are replayed in full**, as an assistant message carrying
     * the calls and one `tool` message per call carrying the answer. The ids are the ones that
     * were used when the calls ran, taken out of the transcript, so a call and its result are the
     * same pair on the next request as they were on the one that produced them. The system lines
     * this app writes — a tool-round cap, a cancellation at an approval — are [Kind.SYSTEM] and
     * are not sent, because they are a fact about this app's loop rather than something a model
     * was told.
     */
    private fun request(model: String, asked: String?): Json.Obj {
        val messages = ArrayList<Json>()
        messages += message("system", systemPrompt())
        for (entry in conversation.read()) {
            when (entry.kind) {
                Kind.USER -> messages += message("user", entry.content)
                Kind.ASSISTANT -> messages += message("assistant", entry.content, callsOf(entry))
                Kind.TOOL_RESULT -> messages += toolMessage(entry, fallback = MINTED)
                Kind.SYSTEM -> continue
            }
        }
        if (asked != null) messages += message("user", asked)
        return Json.Obj(
            linkedMapOf(
                MODEL to Json.Str(model),
                MESSAGES to Json.Arr(messages),
                TOOLS to Tools.declaration(),
                STREAM to Json.Bool(true),
            ),
        )
    }

    /**
     * One message, and for an assistant turn that asked for tools, the calls it asked with.
     *
     * The shape is the one every OpenAI-compatible endpoint documents: a list of calls, each with
     * an id, a type and the function's name and arguments. The whole list comes out of the
     * transcript verbatim, so nothing here can disagree with the results that follow it.
     */
    private fun message(role: String, content: String, calls: Json.Arr? = null): Json.Obj {
        val fields = linkedMapOf<String, Json>(ROLE to Json.Str(role), CONTENT to Json.Str(content))
        if (calls != null && calls.items.isNotEmpty()) fields[TOOL_CALLS] = calls
        return Json.Obj(fields)
    }

    /**
     * The `tool` message that answers one call: the id the call was made with, and the text this
     * app produced — a result, or a refusal the model can read and recover from.
     *
     * [fallback] is for a transcript whose result line has lost its id, which a text editor can do
     * and [omp.agent.store.Conversation] deliberately does not treat as a reason to lose the rest
     * of the conversation. The pair is then only self-consistent, and a request that pairs them
     * wrongly is better than one that pairs them not at all.
     */
    private fun toolMessage(entry: Entry, fallback: String): Json.Obj = Json.Obj(
        linkedMapOf(
            ROLE to Json.Str("tool"),
            TOOL_CALL_ID to Json.Str(entry.extra[TOOL_CALL_ID] ?: fallback),
            CONTENT to Json.Str(entry.content),
        ),
    )

    /**
     * The calls an assistant line was recorded with, or null when it is not a tool request.
     *
     * A transcript is a file in the user's `Documents`: a sync tool or a text editor can leave a
     * field that does not parse, and an assistant message replayed without its calls is a
     * recoverable history where a request built from a broken array is not.
     */
    private fun callsOf(entry: Entry): Json.Arr? {
        val raw = entry.extra[TOOL_CALLS] ?: return null
        return try {
            Json.parse(raw) as? Json.Arr
        } catch (e: JsonException) {
            null
        }
    }

    /**
     * The calls this turn is about, in the shape the endpoint documents and the transcript stores.
     *
     * The arguments go out as the **string** the model assembled, not as a parsed and re-emitted
     * object: re-serialising them would make this app the author of a call it only received, and
     * a model comparing its own arguments with the history would see a difference it did not make.
     */
    private fun callsJson(calls: List<Call>): Json.Arr = Json.Arr(
        calls.map {
            Json.Obj(
                linkedMapOf(
                    "id" to Json.Str(it.id),
                    "type" to Json.Str("function"),
                    "function" to Json.Obj(
                        linkedMapOf(
                            "name" to Json.Str(it.name),
                            "arguments" to Json.Str(it.arguments),
                        ),
                    ),
                ),
            )
        },
    )

    // ---- the tools -------------------------------------------------------------------------

    /**
     * Runs every call the model asked for, in the order it asked, and writes each answer down.
     *
     * **Nothing here ends a session but a Ctrl-C.** An unknown name, arguments that are not JSON,
     * a path the boundary refuses, a result the cap will not carry, a user who pressed a key that
     * is not `y` — all of them come back as a [omp.agent.store.Kind.TOOL_RESULT] the model reads
     * and answers. A Ctrl-C at the approval is different: the user has said stop, so nothing more
     * is done for this question and the terminal goes back.
     *
     * @return true when the user cancelled, and the turn is over.
     */
    private fun runCalls(calls: List<Call>, model: String, provider: String, round: Int): Boolean {
        val env = ToolEnv(
            sandbox = sandbox,
            vfs = vfs,
            real = realPath,
            say = { out.line(it) },
            approve = ::askUser,
        )
        for (call in calls) {
            val outcome = dispatch(env, call)
            if (outcome is Outcome.Cancelled) {
                out.line("$TAG: cancelled at the question; nothing more is done for this one")
                keep(
                    Kind.SYSTEM,
                    "the user pressed Ctrl-C at an approval prompt; nothing further was done",
                    mapOf(MODEL to model, PROVIDER to provider, STOPPED to CANCELLED),
                )
                return true
            }
            val done = outcome as Outcome.Result
            reported(call, done.text)
            val extra = linkedMapOf(
                MODEL to model,
                PROVIDER to provider,
                TOOL to call.name,
                TOOL_CALL_ID to call.id,
            )
            // `--yes` is a fact about this invocation and not about the tool, so the record is
            // corrected here where the invocation is known: a write nobody was asked about and a
            // write somebody said yes to must not read the same in the transcript.
            val approval = if (yes && done.approval != null) Tools.APPROVED_AUTO else done.approval
            if (approval != null) extra[APPROVED] = approval
            keep(Kind.TOOL_RESULT, done.text, extra)
        }
        return false
    }

    /**
     * One call, run, with every way it can be wrong turned into a sentence.
     *
     * The name is checked against [Tools.ALL] first because a name this build does not have is the
     * one failure a model cannot recover from on its own: it will ask again, and again, and the
     * only way out is to be told what there is. `run` is that case, and [Tools.NO_RUN] is why.
     */
    private fun dispatch(env: ToolEnv, call: Call): Outcome {
        if (call.name.isEmpty()) {
            return Outcome.Result(
                "refused: the call carried no tool name, so there is nothing to run. Send the " +
                    "call again with the name of one of: ${Tools.ALL.joinToString(", ") { it.name }}.",
            )
        }
        val tool = Tools.byName(call.name)
        if (tool == null) return Outcome.Result(Tools.unknown(call.name))
        val arguments = argumentsOf(call) ?: return Outcome.Result(argumentsRefusal(call))
        return try {
            tool.run(env, arguments)
        } catch (e: Exception) {
            // The last line of defence, and the reason the session survives a tool at all. A
            // refusal the model can read costs it one round; an exception that reached the loop
            // would end a conversation over something the user cannot see.
            Outcome.Result(
                "refused: ${call.name} could not be run: ${e.message ?: e.javaClass.simpleName}. " +
                    "Nothing was changed.",
            )
        }
    }

    /** The arguments of one call as an object, or null when they are not one. */
    private fun argumentsOf(call: Call): Json.Obj? {
        val text = call.arguments.ifBlank { "{}" }
        return try {
            Json.parse(text) as? Json.Obj
        } catch (e: JsonException) {
            null
        }
    }

    /** What to say about arguments that are not a JSON object, with enough of them to act on. */
    private fun argumentsRefusal(call: Call): String {
        val shown = call.arguments.ifBlank { "(nothing)" }
        val cut = if (shown.length > ARGS_SHOWN) shown.take(ARGS_SHOWN) + "…" else shown
        return "refused: the arguments for ${call.name} are not a JSON object, and this build " +
            "will not guess at them. They were: $cut. Send the call again with an object of " +
            "named arguments, for example {\"path\": \"notes.txt\"}."
    }

    /**
     * What the user is shown of one result.
     *
     * Three lines and a count, because the whole result is on the terminal's scrollback and in the
     * transcript, and a file read printed in full would push the conversation out of sight on a
     * phone. The count of what is not shown is on the same line as the cut, for the same reason
     * the edit preview counts what it leaves out: a truncated thing that does not say it was
     * truncated is a lie told to the one person who can check it.
     */
    private fun reported(call: Call, text: String) {
        out.line("$TAG: ${call.name} returned:")
        val lines = text.split('\n')
        val shown = lines.size.coerceAtMost(SHOWN_LINES)
        for (i in 0 until shown) out.line("  ${lines[i]}")
        val more = lines.size - shown
        if (more > 0) {
            out.line("  … $more more line(s); all of it is in ${conversation.path}")
        }
    }

    /**
     * The one question this agent asks the user, and the only one it ever asks outside its own
     * prompt.
     *
     * **One character.** `y` proceeds; anything else — Enter, `n`, a stray letter, the start of
     * another word — cancels that one tool call and the model is told the user declined. Reading a
     * whole word would make "yes please, go ahead" a refusal, and a refusal the user did not mean
     * is the worse of the two mistakes on a one-key prompt.
     *
     * **`omp --yes` answers here too, and says so.** It is the invocation that carries the
     * decision, so the line is printed and the transcript records `auto` rather than `y`: a silent
     * write and an approved one have to be tellable apart by whoever reads the folder afterwards.
     */
    private fun askUser(lines: List<String>): Verdict {
        if (yes) {
            out.line("$TAG: 'omp --yes' was given, so this is approved without asking")
            return Verdict.YES
        }
        out.put("  approve? [y/N] ")
        val c = ctx.stdin.read()
        if (c in PRINTABLE) out.put(c.toChar().toString())
        out.put("\n")
        if (c < 0 || c == CTRL_C) {
            out.line("$TAG: cancelled at the question; nothing was written")
            return Verdict.CANCELLED
        }
        return if (c == 'y'.code || c == 'Y'.code) Verdict.YES else Verdict.NO
    }

    /**
     * The loop stopped because a model used every round it had, and both the user and the
     * transcript are told so.
     *
     * A [omp.agent.store.Kind.SYSTEM] line and not a tool result, because no tool produced it:
     * this app did, and the next request must not carry this app's own words into a model's mouth.
     */
    private fun capped(model: String, provider: String, used: Int): Turn {
        val line = "the model used all $used tool rounds for this question; the loop stops here " +
            "and the next question starts again from the same folder"
        out.line("$TAG: $line")
        keep(Kind.SYSTEM, line, mapOf(MODEL to model, PROVIDER to provider, STOPPED to TOOL_CAP))
        return Turn.ANSWERED
    }

    // ---- the prompt, and the two reads it shares with `omp key` ------------------------------

    /**
     * One line, echoed as it is typed, and the question in it.
     *
     * Echoed because this is the one prompt in the app a user types prose into: [OmpCommand]'s own
     * is a digit, and a number that does not come back is merely terse, while a question that does
     * not come back is an argument for typing somewhere else. The control keys are the terminal's
     * own — backspace deletes, Ctrl-C leaves — and nothing else is interpreted.
     *
     * @return the line, or null for a Ctrl-C or a closed channel.
     */
    private fun prompt(): String? {
        out.put(prompt)
        val line = StringBuilder()
        while (true) {
            val c = ctx.stdin.read()
            if (c < 0 || c == CTRL_C) {
                out.put("\n")
                return null
            }
            if (c == '\n'.code || c == '\r'.code) {
                out.put("\n")
                return line.toString().trim()
            }
            if (c == 0x08 || c == 0x7F) {
                if (line.isNotEmpty()) {
                    line.setLength(line.length - 1)
                    out.put("\b \b")
                }
                continue
            }
            line.append(c.toChar())
            out.put(c.toChar().toString())
        }
    }

    /**
     * Takes any Ctrl-C already on its way off the channel, and puts back anything that is not one.
     *
     * **The poll waits a millisecond, and the byte it did not want is un-read.** An
     * [omp.shell.InputChannel] keeps a byte that has arrived in its queue and one that has been
     * moved into its buffer as two different facts, and a zero-timeout poll only ever sees the
     * second — so a drain that could not reach the queue would leave the Ctrl-C that stopped the
     * last answer in the channel, where the first [prompt] reads it and a new session is over
     * before it has said anything. And a drain that *can* reach it must not eat the user's first
     * keystroke of the new session on the way past, which is what taking whatever came first and
     * dropping it would do. A millisecond is enough to pull a queued chunk across, and costs
     * nothing when there is nothing to pull.
     */
    private fun dropPendingInterrupt() {
        val channel = ctx.session.input ?: return
        while (true) {
            val b = channel.pollByte(DRAIN_MS) ?: return
            if (b != CTRL_C) {
                // Someone is already typing: that byte is theirs, and it goes back where it was.
                channel.unreadByte(b)
                return
            }
            // Already counted against the job the shell cancelled; nothing here to record.
        }
    }

    /**
     * The lines a session opens with: what this is, where it is, and what it can reach.
     *
     * The second is the folder as a file manager shows it, because that is the path the answer
     * will be filed under. The rest are what the agent can do and where, and the one thing it was
     * deliberately not given — in that order, so a user reading this knows the limit from the
     * same screen that told them the capability.
     */
    private fun greeting() {
        out.line("omp agent ${Agent.VERSION}, in this conversation's folder:")
        out.line("  $realPath")
        out.line("$TAG: ${Tools.ALL.size} tools, and they reach that folder and nothing else:")
        out.line("  ${Tools.ALL.joinToString(", ") { it.name }}")
        out.line("$TAG: every $WRITE_AND_EDIT is put to you first, with the path, the real")
        out.line("  path and the size; 'omp --yes' approves them all without asking, and says so")
        out.line("$TAG: ${Tools.NO_RUN}")
        out.line("$TAG: everything said is kept in")
        out.line("  ${conversation.path}")
    }

    /** The state file is not usable, and the two lines that make it so. */
    private fun unconfigured(why: String): Turn {
        ctx.errLine("$TAG: ${state.path} does not say which provider, model and endpoint to use")
        ctx.errLine("$TAG: $why")
        ctx.errLine(
            "$TAG: set 'provider', '$BASE_URL' and '$MODEL' in that file, or hand the folder to a " +
                "build that does; nothing was sent anywhere",
        )
        ctx.flush()
        return Turn.NOT_CONFIGURED
    }

    /**
     * The paragraph the model is given about itself and what it may touch.
     *
     * It names the working directory as the **real** path, the one a file manager shows, because
     * `/mnt/omp/fotos` is a name for this namespace and the folder the user will open is the
     * other one. It lists the tools and the boundary they are inside, because a model that does
     * not know the boundary will spend a round finding out, and it repeats [Tools.NO_RUN] because
     * a model told it is a coding agent will ask for a shell and deserves an answer rather than
     * silence.
     */
    private fun systemPrompt(): String =
        "You are a coding agent running inside the omp terminal app on an Android phone. " +
            "The working directory of this conversation is $realPath: a plain folder the user " +
            "owns and can open in a file manager, and the project you are asked about lives in " +
            "it.\n\n" +
            "You have ${Tools.ALL.size} tools, and every path they touch is inside that folder. " +
            "A path outside it is refused, and so is the folder above it that holds the user's " +
            "other conversations. Paths are relative to that folder or absolute inside it. The " +
            "tools are: ${Tools.ALL.joinToString(", ") { "${it.name} — ${it.description}" }}.\n\n" +
            "write_file and edit_file change what is on the user's phone, so each one is put to " +
            "the user first with the path, the size and the change; if they decline nothing is " +
            "written and you are told. Every result you are sent carries the real output up to " +
            "${Tools.MAX_RESULT_BYTES} bytes, and something larger is refused with its size " +
            "rather than clipped. A tool call with a name this build does not have comes back " +
            "with the names it does have.\n\n" +
            Tools.NO_RUN

    /**
     * One line of transcript, or a refusal that says which line.
     *
     * [TranscriptFullException] is caught rather than allowed out: a full transcript is a
     * conversation the user has to finish or clear, and letting the exception reach the shell
     * would print "No space left on device" and send them looking at storage that is nearly empty.
     */
    private fun keep(kind: Kind, content: String, extra: Map<String, String>) {
        try {
            conversation.append(kind, content, extra)
        } catch (e: TranscriptFullException) {
            out.line("$TAG: ${e.message}")
        } catch (e: Exception) {
            out.line("$TAG: ${conversation.path} could not be written: ${e.message}")
        }
    }

    /**
     * A Ctrl-C between two events. What had arrived is kept and marked, because it is what the
     * model actually said before the user stopped listening, and an unmarked half-answer in the
     * history is a sentence the next turn will take at face value.
     */
    private fun interrupted(answer: StringBuilder, model: String, provider: String) {
        if (!answer.endsWith("\n")) out.put("\n")
        out.line("$TAG: interrupted; the answer so far is in ${conversation.path}")
        keep(
            Kind.ASSISTANT,
            answer.toString(),
            mapOf(MODEL to model, PROVIDER to provider, STOPPED to INTERRUPTED),
        )
    }

    // ---- what one stream turned out to be ----------------------------------------------------

    /** How one request ended: an answer, a Ctrl-C, or a list of tool calls to run. */
    private sealed class Event {
        /** The round is over: an answer, or a request that was refused before it began. */
        data class Done(val text: String) : Event()

        object Interrupted : Event()

        /** The model asked for tools; [text] is anything it said before it did. */
        data class Calls(val calls: List<Call>, val text: String) : Event()
    }

    /** One tool call the model asked for, once every chunk of it has arrived. */
    private class Call(
        /** The id the endpoint gave, or one minted here. It pairs this call with its result. */
        val id: String,
        val name: String,
        /** The arguments as the model wrote them, still a string. */
        val arguments: String,
    )

    /**
     * The tool calls of one stream, assembled out of chunks that each carried part of one.
     *
     * **A call is keyed by its `index`, and the pieces are appended in the order they arrive.**
     * The name and the arguments arrive in different chunks — sometimes the name alone, sometimes
     * a few characters of a JSON string at a time — and there is no framing that says a call has
     * ended except the end of the stream, so the only honest place to decide a call is complete
     * is here.
     *
     * **The order the model asked in is the order kept**, not the order of the indexes, because
     * a model that asks for three things means the first one first and a result that came back
     * reordered would have its ids attached to the wrong calls.
     */
    private class Pending {
        private val order = ArrayList<Int>()
        private val ids = HashMap<Int, String>()
        private val names = HashMap<Int, StringBuilder>()
        private val arguments = HashMap<Int, StringBuilder>()

        /** Whether anything at all has arrived; a stream with no call in it is an answer. */
        val empty: Boolean get() = order.isEmpty()

        fun add(calls: List<Json>) {
            for (item in calls) {
                val call = item as? Json.Obj ?: continue
                val at = (call.long("index") ?: 0L).toInt()
                if (at !in order) order.add(at)
                call.str("id")?.let { ids[at] = it }
                val function = call.field("function") as? Json.Obj ?: continue
                function.str("name")?.let { append(names, at, it) }
                function.str("arguments")?.let { append(arguments, at, it) }
            }
        }

        /**
         * The calls, in the order they were asked for, with an id for each.
         *
         * A call whose name never arrived keeps an empty name, and [dispatch] turns that into a
         * sentence rather than a lookup that happens to fail: a truncated stream is a fact about
         * the endpoint, and the model is the one who can do something about it.
         *
         * @param round which request of this turn this was, so a minted id cannot collide with one
         *   minted for a different round in the same history.
         */
        fun complete(round: Int): List<Call> = order.mapIndexed { at, index ->
            val name = names[index]?.toString().orEmpty()
            val raw = arguments[index]?.toString().orEmpty()
            Call(
                id = ids[index] ?: "call_omp_${round}_$at",
                name = name,
                arguments = if (raw.isBlank()) "{}" else raw,
            )
        }

        private fun append(into: HashMap<Int, StringBuilder>, at: Int, more: String) {
            val builder = into.getOrPut(at) { StringBuilder() }
            builder.append(more)
        }
    }

    /** What one `data:` payload turned out to be: text to show, tool calls, or both. */
    private class Chunk(val text: String, val calls: List<Json>?)

    // ---- writing, and the one other thread that wants to write --------------------------------

    /**
     * The screen, under one lock.
     *
     * The read loop and the watchdog are two threads writing to the same
     * [omp.shell.exec.ExecContext] stdout, and [omp.shell.ScreenOutput] holds a partial UTF-8
     * character between calls — so two writers would interleave halves of code points. One monitor
     * around each write-then-flush pair is the whole fix, and it is uncontended for every delta
     * that arrives on time, which is all but the ones that are not.
     */
    private class Writer(private val ctx: ExecContext) {
        private val lock = Any()

        /** Text with no newline, flushed, so a token is on the screen before the next one exists. */
        fun put(text: String) {
            synchronized(lock) {
                ctx.out(text)
                ctx.flush()
            }
        }

        fun line(text: String) {
            synchronized(lock) {
                ctx.outLine(text)
                ctx.flush()
            }
        }
    }

    /**
     * The second job of a parked read: the "still waiting" line, and the Ctrl-C that reaches the
     * transport.
     *
     * **The line is printed at ten seconds, and it is about a model that is thinking, not about a
     * socket that is stuck.** It says how long the endpoint has been quiet, because a model
     * deliberating for ten seconds and a connection that has died look identical to a user with no
     * other feedback. Ten seconds is short enough that the silence is always visibly *ours* and long
     * enough that the pause between two tokens of an ordinary answer never scrolls the answer away.
     * It is written with a carriage return and padded, so it is a footnote on the cursor's own line
     * and not a line of the answer.
     *
     * It is deliberately **not** a warning about the connection. The transport has its own clock
     * and its own end for it: [omp.shell.SseStream.STREAM_SILENCE_LIMIT_MS], five minutes of
     * silence, at which point the user is told which host went quiet and for how long. Ten seconds
     * is far inside that, so nothing this line says can be true of a dead socket — a dead one
     * produces a message at the five-minute mark naming the host, not this.
     *
     * **How fast the Ctrl-C lands is the transport's, and the transport says so.** This class polls
     * the shell's flag once a second — [POLL_MS] — and hands the stream to its own `close()`. That
     * is the whole of what it does and it cannot do better than that, because a thread parked
     * inside [omp.shell.HttpStream.next] is inside a socket read, which nothing here can reach.
     * What ends that read is the transport's checkpoint, and [omp.shell.SseStream.STREAM_POLL_MS]
     * is what a parked read comes back at — measured, because `disconnect()` gives the socket back
     * to the far end but does not interrupt a read already inside it. So the close is issued within
     * this loop's one-second poll and acted on within a quarter of a second of that. **A second
     * and a quarter, and no more** — and both numbers are constants this project can point at,
     * rather than this class guessing at what another one does, which is the thing that had to be
     * true before any of it could be written down here.
     *
     * The thread is the one callback this loop has, and it is deliberately smaller than the one
     * [omp.shell.HttpStream]'s KDoc argues against: it moves no events, queues nothing, and lives
     * exactly as long as one read. It stops in a `finally`, so a refused request, a Ctrl-C and an
     * ordinary end all stop it.
     *
     * @param isCancelled the shell's cancel flag, read once a second.
     * @param onCancel what to do when it is set: the stream's own `close()`, which is idempotent
     *   and which the `use` block will call again without harm. It runs on this thread and may
     *   block, which is why it is issued once and the thread then returns.
     */
    private class Waiting(
        private val writer: Writer,
        private val host: String,
        private val isCancelled: () -> Boolean,
        private val onCancel: () -> Unit,
    ) {

        @Volatile
        private var running = false

        @Volatile
        private var quietSince = System.currentTimeMillis()

        private var lastNoticed = 0L

        fun start() {
            running = true
            quietSince = System.currentTimeMillis()
            val thread = Thread({ watch() }, "omp-agent-waiting")
            thread.isDaemon = true
            thread.start()
        }

        fun arrived() {
            quietSince = System.currentTimeMillis()
        }

        fun stop() {
            running = false
        }

        private fun watch() {
            while (running) {
                try {
                    Thread.sleep(POLL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                if (!running) return
                if (isCancelled()) {
                    // The close is what the read is waiting for; the loop on the other thread
                    // takes it from here, and records the answer as interrupted rather than lost.
                    onCancel()
                    return
                }
                val quiet = System.currentTimeMillis() - quietSince
                if (!running || quiet < EVERY_MS || quiet - lastNoticed < EVERY_MS) continue
                lastNoticed = quiet
                val note = "$TAG: still waiting on $host, ${quiet / 1000}s without an event"
                writer.put("\r" + note.padEnd(NOTE_WIDTH) + "\r")
            }
        }
    }

    companion object {
        /** [Agent.TAG], aliased so every line of this loop reads `omp: …`. */
        const val TAG = Agent.TAG

        private const val INTERRUPTED = "interrupted"
        private const val CANCELLED = "cancelled"
        private const val TOOL_CAP = "tool-cap"
        private const val CTRL_C = 0x03

        /** The two tools that change the phone, named together wherever the greeting counts them. */
        private const val WRITE_AND_EDIT = "write_file and edit_file"

        /**
         * How often the watchdog looks, which is not how often it speaks, and not the transport's
         * poll either — [omp.shell.SseStream.STREAM_POLL_MS] is that, and the two together are what
         * bound how long a Ctrl-C takes to end a stalled answer.
         */
        private const val POLL_MS = 1_000L

        /** Long enough for the channel to hand over a chunk it already has, short enough to skip. */
        private const val DRAIN_MS = 1L

        /**
         * How long the endpoint may be quiet before the "still waiting" line appears, and between
         * two notices. This is about a model that is thinking, and is nowhere near the transport's
         * own limit — [omp.shell.SseStream.STREAM_SILENCE_LIMIT_MS] is that, and it is what a dead
         * connection eventually produces, naming the host.
         */
        private const val EVERY_MS = 10_000L

        /** Wide enough for the longest note, so none of it is left behind on the line. */
        private const val NOTE_WIDTH = 100

        /** How many lines of one result are echoed to the user, before the count takes over. */
        private const val SHOWN_LINES = 3

        /** How much of a call's own arguments is quoted back when they will not parse. */
        private const val ARGS_SHOWN = 120

        /** The id a result whose own has been lost in the transcript is paired with. */
        private const val MINTED = "call_omp_0"

        /** The printable ASCII range, the only bytes echoed at the one-character prompt. */
        private val PRINTABLE = 0x20..0x7E

        private const val ROLE = "role"
        private const val CONTENT = "content"
        private const val MODEL = "model"
        private const val MESSAGES = "messages"
        private const val TOOLS = "tools"
        private const val STREAM = "stream"
        private const val PROVIDER = "provider"
        private const val STOPPED = "stopped"
        private const val TOOL = "tool"
        private const val TOOL_CALLS = "tool_calls"
        private const val TOOL_CALL_ID = "tool_call_id"
        private const val APPROVED = "approved"
        private const val BASE_URL = "base_url"
    }
}
