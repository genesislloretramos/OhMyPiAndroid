package com.omp.terminal.web

import omp.agent.Agent
import omp.agent.Session
import omp.agent.Turn as AgentTurn
import omp.agent.json.Json
import omp.agent.store.AgentState
import omp.agent.store.Conversation
import omp.agent.store.KeyStore
import omp.agent.store.Kind
import omp.agent.store.StateStatus
import omp.shell.PlatformServices
import omp.shell.Session as ShellSession
import omp.shell.exec.Errno
import omp.shell.exec.ExecContext
import omp.shell.fs.FsException
import omp.term.Screen
import omp.vm.launcher.Container
import omp.vm.launcher.Containers
import omp.vm.provision.ProvisionPaths
import omp.vm.provision.ProvisionStatusHolder
import omp.vm.workspace.Entry
import omp.vm.workspace.Workspace
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The pages under `assets/web/`.
 *
 * An interface and not an `android.content.res.AssetManager`, so that everything below it — the
 * routes, the token check, the agent — is JVM code with no `android.*` in it, and so that
 * [LocalServerTest] can hand a server three strings instead of an APK.
 */
fun interface AssetSource {
    /** The bytes of `web/<name>`, or null when this build has no such file. */
    fun read(name: String): ByteArray?
}

/**
 * The chat, over the agent this app already ships.
 *
 * ### The routes
 *
 * | method | path | what it answers |
 * |---|---|---|
 * | `GET` | `/login` | the login page — the only path that answers without a token |
 * | `POST` | `/login` | checks a token and sets the cookie the rest of the page rides on |
 * | `GET` | `/` | the app: the conversation list, one transcript, the composer |
 * | `GET` | `/app.css`, `/app.js` | the two static files, behind the token like everything else |
 * | `GET` | `/api/state` | where the conversations are on this phone, which there are, and what `omp provision` is doing |
 * | `POST` | `/api/conversations` | makes one: `{"name":"photos"}`, or a generated name |
 * | `GET` | `/api/conversations/{name}/transcript` | one conversation, as it is recorded |
 * | `POST` | `/api/conversations/{name}/message` | asks a question; the answer streams back |
 * | `POST` | `/api/approvals/{id}` | answers the write the agent is asking about |
 * | `POST` | `/api/turns/{id}/cancel` | stops an answer that is running |
 *
 * **No route returns a key, in any form.** The only things read out of [KeyStore] are
 * [KeyStore.providers] — the provider *names*, which is what `omp key --list` prints — and
 * [KeyStore.has], which asks whether a file exists and never opens it. The secret is read by the
 * agent's own loop and handed straight to the transport, exactly as it is on a terminal, and no
 * path in this app leads from an HTTP response to it.
 *
 * ### The streamed answer, and the exact wire format
 *
 * `POST /api/conversations/{name}/message` answers `200`, `Content-Type: text/event-stream`, and
 * **no `Content-Length`**: the body ends when the connection closes. The bytes are **Server-Sent
 * Events** — `data:` lines, a blank line ending an event, a final `data: [DONE]` — which is the
 * grammar `omp.agent.http.Sse` in `:core` already implements on the client side. That is the whole
 * reason the format was chosen: the stream this server writes is one that the app's own SSE reader
 * can consume unchanged, and one a browser understands with nothing on the page.
 *
 * Every event carries its name **twice**: once in the `event:` line a browser dispatches on, and
 * once as `"t"` in the JSON object in `data:`. The duplication is not decoration — a browser's
 * `EventSource` dispatches on `event:`, and `omp.agent.http.Sse` reads `event:` and drops it, so a
 * payload that only had its name in the event line would be an anonymous string to the one reader
 * in this project that matters. The names are `open`, `text`, `note`, `approval`, `answered`,
 * `refusal`, `turn` and `error`.
 *
 * The page reads it with `fetch` and a `ReadableStream`, not `EventSource`, because `EventSource`
 * is `GET`-only and the question belongs in a request body. That is about twenty lines of framing
 * in `app.js` and no library, which is the requirement: **the page has no dependency of any kind.**
 *
 * ### The approval, and why it needs no change to `:core`
 *
 * Every write the agent makes is put to the user first — that is `omp.agent.tools.FileTool`, and
 * this app does not weaken it. The question reaches the user through [Session]'s own prompt, which
 * reads **exactly one byte** from `ctx.stdin` and nothing else. So this class supplies a stdin of
 * its own: the byte arrives when the browser answers the prompt the page is showing.
 *
 * **That is the whole seam, and there is no string in it.** [Gate.read] is called once per
 * question; it hands the page the lines the tool had just printed, then blocks until
 * `/api/approvals/{id}` answers, then returns `y` or anything else. Nothing here recognises
 * `approve?`, parses the agent's output to guess at it, or reads a private field. There is also no
 * route and no setting that turns the question off: `omp --yes` is the only thing in this app
 * that skips it, and it is a flag on a terminal invocation, not a thing HTTP can reach.
 */
class ChatApi(
    private val services: PlatformServices,
    private val assets: AssetSource,
    private val token: String,
    private val version: String,
) {

    private val gate = TokenGate(token)

    /**
     * The shell session every command here runs against.
     *
     * One, shared. It is here for its `vfs` — a device-rooted `RealVfs`, which is what makes
     * [Containers.locate] answer with the shared-storage path instead of refusing for want of a
     * bind map — and for the `env` the two context builders copy. Nothing in a question or a
     * listing moves a working directory, so a field on it that nothing writes cannot go stale.
     */
    private val shell = ShellSession(services, Screen(1, 1, 0) {})

    private val store: KeyStore get() = Agent.store(listing())

    private val jobs = ConcurrentHashMap<String, Job>()
    private val waiting = ConcurrentHashMap<String, Job>()
    private val speaking = ConcurrentHashMap.newKeySet<String>()
    private val ids = AtomicLong(0)

    /** Every route this server has. Fixed, and the 404 says so when one is missed. */
    fun routes(): List<Route> = listOf(
        route(TokenGate.LOGIN_PATH, "GET") { _, r -> page(r, "index.html") },
        route(TokenGate.LOGIN_PATH, "POST") { q, r -> login(q, r) },
        route("/", "GET") { _, r -> page(r, "index.html") },
        route("/app.css", "GET") { _, r -> page(r, "app.css") },
        route("/app.js", "GET") { _, r -> page(r, "app.js") },
        route("/api/state", "GET") { _, r -> state(r) },
        route("/api/conversations", "POST") { q, r -> create(q, r) },
        route("/api/conversations/{name}/transcript", "GET") { q, r -> transcript(q, r) },
        route("/api/conversations/{name}/message", "POST") { q, r -> message(q, r) },
        route("/api/approvals/{id}", "POST") { q, r -> approve(q, r) },
        route("/api/turns/{id}/cancel", "POST") { q, r -> cancel(q, r) },
    ).map(::guarded)

    /**
     * The token check in front of every route but the login page.
     *
     * Wrapping the whole list rather than sitting inside each handler, because a handler that
     * forgot to check would then be a hole and not a bug a reviewer can see. The one open path is
     * named once, in [TokenGate.openPath].
     */
    private fun guarded(route: Route): Route {
        val inner = route.handler
        return route(route.pattern, route.method) { request, response ->
            val refused = gate.check(request)
            if (refused == null) {
                inner.handle(request, response)
            } else {
                response.send(
                    401,
                    JSON,
                    error("this server wants this install's own token: $refused")
                        .toByteArray(UTF8),
                )
            }
        }
    }

    /** Releases every turn in flight, so a stopped service is not leaving a model waiting. */
    fun close() {
        for (job in jobs.values) {
            job.cancelled.set(true)
            job.stdin.cancel()
        }
    }

    // ---- the static pages ---------------------------------------------------------------------

    private fun page(response: Response, name: String) {
        val bytes = assets.read(name)
        if (bytes == null) {
            // The route exists and the file does not: two different bugs, and this says which.
            response.send(
                500,
                TEXT,
                "500: assets/web/$name is not in this build\n".toByteArray(UTF8),
            )
            return
        }
        response.send(200, contentTypeFor(name), bytes)
    }

    /**
     * `POST /login`: check a token, and hand back the cookie every later request needs.
     *
     * A cookie rather than a header, because the two things a browser fetches on its own — a
     * stylesheet and a stream — cannot carry a header, and a page made to put the credential in
     * every URL it loaded would be putting it in the history of every tab. `HttpOnly` so the
     * page's own script cannot read it back; `SameSite=Strict` so a page on another site cannot
     * make the browser attach it to a request that page did not mean to make.
     */
    private fun login(request: Request, response: Response) {
        val offered = request.header(TokenGate.HEADER) ?: field(request, "token")
        if (offered == null || offered != token) {
            response.send(401, JSON, error("that is not this install's token").toByteArray(UTF8))
            return
        }
        response.send(
            204,
            TEXT,
            ByteArray(0),
            listOf("Set-Cookie" to "${TokenGate.COOKIE}=$offered; Path=/; HttpOnly; SameSite=Strict"),
        )
    }

    // ---- what the page asks --------------------------------------------------------------------

    /**
     * `GET /api/state`: what there is, and where it is on this phone.
     *
     * **The container's real path is in here**, because being able to see where the conversations
     * really are is the thing a user of a headless host asks first, and the answer is a path they
     * can paste into a file manager — the same one this app already prints for `omp ls`.
     *
     * When the all-files grant has not been made, [Containers.ensure] answers with
     * `omp.vm.HostAccess`'s own sentence and it is passed through unchanged. A web page that
     * invented a second wording for it would be the second place this app says that, and the two
     * would drift apart.
     */
    private fun state(response: Response) {
        val container = located(response) ?: return
        val workspace = container.workspace { services.wallClockMillis() }
        val entries = workspace.list()
        val fields = LinkedHashMap<String, Json>()
        fields["version"] = Json.Str(version)
        fields["agent"] = Json.Str(Agent.VERSION)
        fields["tools"] = Json.Str(Agent.NAMES)
        fields["container"] = Json.Str(container.root)
        fields["hostRoot"] = Json.Str(container.hostRoot)
        fields["storageGranted"] = Json.Bool(services.isExternalStorageManager())
        fields["providers"] = Json.Arr(store.providers().map { Json.Str(it) })
        fields["conversations"] = Json.Arr(entries.map { describe(workspace, it) })
        fields["speaking"] = Json.Arr(speaking.sorted().map { Json.Str(it) })
        for ((key, value) in provisioning()) fields[key] = value
        send(response, 200, fields)
    }

    /**
     * What `omp provision` is doing on this phone, as the fields the page polls for.
     *
     * **A run started in a terminal is minutes long and the person who started it may lock the
     * phone**, which is the whole reason these are here: the notification is one door and this
     * route is the other, and both read the same [omp.vm.provision.ProvisionStatusHolder] the
     * command writes. Nothing about the download is decided here — the page can see a phase and two
     * byte counts and nothing else, and the cost is not in here either, because
     * [omp.vm.provision.Provisioner] already refuses to start one without an answer and a second
     * copy of the numbers on a page would be a third spelling of them.
     *
     * **The two facts it reports are different on purpose.** [PROVISIONED] is read off the disk
     * through [omp.vm.provision.ProvisionState] and answers "is the real Debian on this phone",
     * which is true whatever is running; the rest is [omp.vm.provision.ProvisionStatusHolder]'s
     * current slot and answers "what is happening right now", which is null's business between runs.
     * A page that showed only the second would report nothing on a provisioned device, and one that
     * showed only the first would never show a download.
     */
    private fun provisioning(): Map<String, Json> {
        val status = ProvisionStatusHolder.current
        val state = ProvisionPaths.inAppStorage(services).state(shell.vfs)
        val fields = LinkedHashMap<String, Json>()
        fields[PROVISIONED] = Json.Bool(state.realAgentInstalled)
        fields[PROVISION_ROOTFS] = Json.Bool(state.rootfsInstalled)
        fields[PROVISION_AGENT] = Json.Bool(state.agentInstalled)
        fields[PROVISION_RUNNING] = Json.Bool(status.running)
        fields[PROVISION_ABI] = Json.Str(status.abi.orEmpty())
        fields[PROVISION_PHASE] = Json.Str(status.phase?.name.orEmpty())
        fields[PROVISION_ARTIFACT] = Json.Str(status.artifact.orEmpty())
        fields[PROVISION_RECEIVED] = Json.Num(status.receivedBytes.toDouble(), status.receivedBytes.toString())
        fields[PROVISION_TOTAL] = Json.Num(status.totalBytes.toDouble(), status.totalBytes.toString())
        fields[PROVISION_OUTCOME] = Json.Str(status.outcome?.name.orEmpty())
        fields[PROVISION_LINE] = Json.Str(status.line().orEmpty())
        return fields
    }

    private fun describe(workspace: Workspace, entry: Entry): Json {
        val found = AgentState(shell.vfs, entry.path).read()
        val provider = found.config.provider
        val fields = LinkedHashMap<String, Json>()
        fields["name"] = Json.Str(entry.name)
        fields["path"] = Json.Str(entry.path)
        fields["hostPath"] = Json.Str(entry.hostPath)
        fields["created"] = Json.Num(entry.createdMillis.toDouble(), entry.createdMillis.toString())
        fields["modified"] = Json.Num(entry.modifiedMillis.toDouble(), entry.modifiedMillis.toString())
        fields["title"] = Json.Str(workspace.metadata(entry)[Workspace.TITLE].orEmpty())
        fields["configured"] = Json.Bool(found.status == StateStatus.LOADED)
        fields["provider"] = Json.Str(provider.orEmpty())
        fields["model"] = Json.Str(found.config.model.orEmpty())
        fields["baseUrl"] = Json.Str(found.config.baseUrl.orEmpty())
        // Whether a key is in the file for this conversation's provider: a question about a file's
        // existence, which is all the terminal's own `omp key --show` reports too. Never its
        // contents, and not even its length.
        fields["keyStored"] = Json.Bool(provider != null && store.has(provider))
        fields["state"] = Json.Str(found.message)
        return Json.Obj(fields)
    }

    /**
     * `POST /api/conversations`: a folder, made the way `omp new` makes it.
     *
     * [Workspace.create] is the same call, so the name is sanitised by the same rules, a name
     * already in use gets the same `-2`, and a name that sanitises away to nothing becomes the
     * same generated one. Nothing here decides what a conversation may be called.
     */
    private fun create(request: Request, response: Response) {
        val container = located(response) ?: return
        val workspace = container.workspace { services.wallClockMillis() }
        val name = field(request, "name")?.takeIf { it.isNotBlank() }
        val entry = try {
            workspace.create(name)
        } catch (e: FsException) {
            response.send(
                409,
                JSON,
                error("${e.path ?: container.root}: ${Errno.messageFor(e)}").toByteArray(UTF8),
            )
            return
        }
        val fields = LinkedHashMap<String, Json>()
        fields["name"] = Json.Str(entry.name)
        fields["path"] = Json.Str(entry.path)
        fields["hostPath"] = Json.Str(entry.hostPath)
        send(response, 201, fields)
    }

    /**
     * `GET /api/conversations/{name}/transcript`: the conversation as it is recorded.
     *
     * **Straight from [Conversation.transcript]** — the same `.omp/transcript.jsonl` the terminal
     * shows, read by the same reader that skips a line a crash left half-written and says where
     * it was. Roles come from the file's own `role` field, so a tool result and an answer are
     * different things here exactly as they are on disk.
     *
     * **The most recent [SHOWN_ENTRIES] entries and no more**, with the real total beside them. A
     * transcript may legally be eight megabytes and a phone rendering a chat is not the place to
     * ask for all of it; a truncated list that does not say it was truncated is the failure this
     * project keeps refusing to ship, so both numbers are in the answer.
     */
    private fun transcript(request: Request, response: Response) {
        val name = request.param("name") ?: return
        val entry = open(response, name) ?: return
        val conversation = Conversation(shell.vfs, entry.path)
        val found = conversation.transcript()
        val entries = found.entries
        val from = maxOf(0, entries.size - SHOWN_ENTRIES)
        val fields = LinkedHashMap<String, Json>()
        fields["conversation"] = Json.Str(entry.name)
        fields["hostPath"] = Json.Str(entry.hostPath)
        fields["transcriptPath"] = Json.Str(conversation.path)
        fields["total"] = Json.Num(entries.size.toDouble(), entries.size.toString())
        fields["shown"] = Json.Num((entries.size - from).toDouble(), (entries.size - from).toString())
        fields["skipped"] = Json.Arr(found.skipped.map { skip(it.offset, it.byteLength, it.reason) })
        fields["entries"] = Json.Arr(
            entries.subList(from, entries.size).map { line(it.kind, it.content, it.extra) },
        )
        send(response, 200, fields)
    }

    private fun skip(offset: Long, bytes: Long, why: String): Json = Json.Obj(
        linkedMapOf(
            "offset" to Json.Num(offset.toDouble(), offset.toString()),
            "bytes" to Json.Num(bytes.toDouble(), bytes.toString()),
            "reason" to Json.Str(why),
        ),
    )

    private fun line(kind: Kind, content: String, extra: Map<String, String>): Json {
        val fields = LinkedHashMap<String, Json>()
        fields["role"] = Json.Str(kind.wire)
        fields["content"] = Json.Str(content)
        for ((key, value) in extra) fields[key] = Json.Str(value)
        return Json.Obj(fields)
    }

    // ---- one turn ------------------------------------------------------------------------------

    /**
     * `POST /api/conversations/{name}/message`: ask, and stream the answer.
     *
     * **One turn per conversation.** A second message for a conversation that is already answering
     * is a `409` and not a queue: two [Session]s appending to one `.omp/transcript.jsonl` would
     * interleave their lines, and a transcript is what a model is later shown as an ordered
     * conversation.
     *
     * **The question is not written to the transcript here.** [Session.ask] appends it itself, and
     * only once the endpoint has answered `2xx`, so a question that never reached a model is not
     * part of the history the next one is built from. Writing it here as well would put the user's
     * half of the exchange in twice.
     */
    private fun message(request: Request, response: Response) {
        val name = request.param("name") ?: return
        val entry = open(response, name) ?: return
        val question = field(request, "text")?.trim()
        if (question.isNullOrEmpty()) {
            response.send(400, JSON, error("a message needs a 'text' field").toByteArray(UTF8))
            return
        }
        if (!speaking.add(name)) {
            response.send(
                409,
                JSON,
                error("'$name' is already answering a question; wait for it, or cancel it")
                    .toByteArray(UTF8),
            )
            return
        }
        val sse = Sse(response)
        response.beginStream("text/event-stream")
        val id = "turn-${ids.incrementAndGet()}"
        val job = Job(
            id = id,
            name = name,
            cancelled = AtomicBoolean(false),
            stdin = Gate(),
            out = Answer(),
        )
        jobs[id] = job
        job.out.emit = { event, data -> sse.event(event, data) }
        job.stdin.onAsk = { ask(job) }
        try {
            sse.event(
                "open",
                objectOf("conversation" to entry.name, "hostPath" to entry.hostPath, "turn" to id),
            )
            val diagnostics = Diagnostics { event, data -> sse.event(event, data) }
            val ctx = turn(entry, job, diagnostics)
            val outcome = Session(ctx, entry.path, entry.hostPath, false).ask(question)
            sse.event(
                "turn",
                objectOf(
                    "conversation" to entry.name,
                    "turn" to outcome.name,
                    "message" to say(outcome),
                ),
            )
        } catch (e: Exception) {
            // A turn that failed is still a turn that ended, and the page has to be told so rather
            // than left watching a stream that stopped for a reason it cannot see.
            sse.event("error", objectOf("text" to (e.message ?: e.javaClass.simpleName)))
        } finally {
            job.out.emit = null
            jobs.remove(id)
            waiting.values.remove(job)
            speaking.remove(name)
            sse.end()
        }
    }

    /**
     * The agent has asked its one question. The page is told, and this thread blocks until it
     * answers.
     *
     * The lines handed over are the ones the tool printed immediately before the question, which
     * are exactly the lines a terminal shows above its `approve? [y/N]`: what the model wants to
     * do, the path inside the conversation, **and that same path as a file manager shows it**.
     * Nothing here reads or reformats them; they are the agent's own words, forwarded.
     */
    private fun ask(job: Job) {
        // One id per *question*, not one per turn. A model that asks for two writes in one turn
        // asks twice, and an id derived from the turn alone would be the same string both times —
        // so a click on a prompt the user had already answered would answer the next write
        // instead. The counter is what makes them different questions.
        val id = "${job.id}-approval-${ids.incrementAndGet()}"
        job.approval = id
        waiting[id] = job
        val fields = LinkedHashMap<String, Json>()
        fields["t"] = Json.Str("approval")
        fields["id"] = Json.Str(id)
        fields["conversation"] = Json.Str(job.name)
        fields["lines"] = Json.Arr(job.out.recentNotes().map { Json.Str(it) })
        job.out.emit?.invoke("approval", Json.Obj(fields).toCompactString())
    }

    /**
     * `POST /api/approvals/{id}`: the answer, and only the answer.
     *
     * **`y` goes ahead and anything else declines**, which is what [Session]'s own question does
     * with the byte it is given, so there is no richer vocabulary here than there is on a
     * terminal. A decline is a real outcome and not a failure: the model is told the user declined
     * and asked to do something else, which is the loop continuing.
     *
     * An id that is not outstanding is a `404` and not a `200`, because a page that has just been
     * reopened will click approve on a prompt whose answer has already been given, and telling it
     * "fine" would be a lie about a decision it did not make.
     */
    private fun approve(request: Request, response: Response) {
        val id = request.param("id") ?: return
        val job = waiting.remove(id)
        if (job == null) {
            response.send(
                404,
                JSON,
                error("no write is waiting for an answer under '$id'").toByteArray(UTF8),
            )
            return
        }
        val allow = field(request, "allow") == "true"
        job.approval = null
        job.out.emit?.invoke("answered", Json.Obj(objectOf("allow" to allow.toString())).toCompactString())
        job.stdin.answer(allow)
        response.send(204, TEXT, ByteArray(0))
    }

    /**
     * `POST /api/turns/{id}/cancel`: stop.
     *
     * The flag is the shell's own [ExecContext.cancelled], which the agent's watchdog asks once a
     * second and acts on within a quarter of a second of that. **An outstanding approval is
     * answered with end-of-input rather than left hanging**, because the agent is parked inside
     * the one `read()` this server owns and a flag cannot reach it — and because a write declined
     * for a reason the user never gave would be reported to the model as a decision they made.
     */
    private fun cancel(request: Request, response: Response) {
        val id = request.param("id") ?: return
        val job = jobs[id]
        if (job == null) {
            response.send(404, JSON, error("no turn '$id' is running").toByteArray(UTF8))
            return
        }
        job.cancelled.set(true)
        job.stdin.cancel()
        response.send(204, TEXT, ByteArray(0))
    }

    // ---- the two contexts -----------------------------------------------------------------------

    /**
     * A context for a question that asks nothing: a listing, a create, a state.
     *
     * Its streams go nowhere. The only thing a caller does with it is hand it to
     * [Containers.locate], which reads the session's filesystem and the platform's shared-storage
     * directory, and to [KeyStore] through [Agent.store], which reads the app's own files
     * directory. Neither of those is a question to a person.
     */
    private fun listing(): ExecContext = context(
        stdin = NoInput,
        stdout = NoOutput,
        stderr = NoOutput,
        cancelled = AtomicBoolean(false),
    )

    /**
     * The context one turn runs in.
     *
     * `isTty` is true because the agent's approval is a question to a person and a pipe is not
     * one — and because [Session.ask] never consults [Agent.mayPrompt] anyway, which is the whole
     * reason a turn is drivable from a socket at all.
     */
    private fun context(
        stdin: InputStream,
        stdout: OutputStream,
        stderr: OutputStream,
        cancelled: AtomicBoolean,
    ): ExecContext = ExecContext(
        argv = listOf("omp"),
        stdin = stdin,
        stdout = stdout,
        stderr = stderr,
        env = LinkedHashMap(shell.env),
        services = services,
        session = shell,
        isTty = true,
        cancelled = cancelled,
    )

    private fun turn(entry: Entry, job: Job, diagnostics: OutputStream): ExecContext = context(
        stdin = job.stdin,
        stdout = job.out,
        stderr = diagnostics,
        cancelled = job.cancelled,
    )

    /**
     * The container, or a refusal already sent.
     *
     * [Containers.ensure] is asked first, as bare `omp` asks it, because a table of conversations
     * is a table of a directory and there is no honest listing of a directory that is not there.
     */
    private fun located(response: Response): Container? {
        val container = Containers.locate(listing())
        val refusal = Containers.ensure(container)
        if (refusal != null) {
            response.send(503, JSON, error(refusal).toByteArray(UTF8))
            return null
        }
        return container
    }

    /** The conversation called [name], or a `404` in the shell's own errno wording. */
    private fun open(response: Response, name: String): Entry? {
        val container = located(response) ?: return null
        val workspace = container.workspace { services.wallClockMillis() }
        return try {
            workspace.open(name)
        } catch (e: FsException) {
            response.send(
                404,
                JSON,
                error("${e.path ?: container.child(name)}: ${Errno.messageFor(e)}")
                    .toByteArray(UTF8),
            )
            null
        }
    }

    private fun send(response: Response, status: Int, fields: Map<String, Json>) {
        response.send(status, JSON, Json.Obj(fields).toCompactString().toByteArray(UTF8))
    }

    private fun error(text: String): String =
        Json.Obj(mapOf("error" to Json.Str(text))).toCompactString()

    private fun objectOf(vararg pairs: Pair<String, String>): Map<String, Json> {
        val out = LinkedHashMap<String, Json>()
        for ((key, value) in pairs) out[key] = Json.Str(value)
        return out
    }

    /** One string out of a JSON body, or null when the body is not an object with that field. */
    private fun field(request: Request, name: String): String? = try {
        (Json.parse(request.text()) as? Json.Obj)?.str(name)
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun contentTypeFor(name: String): String = when {
        name.endsWith(".html") -> "text/html; charset=utf-8"
        name.endsWith(".css") -> "text/css; charset=utf-8"
        name.endsWith(".js") -> "text/javascript; charset=utf-8"
        else -> "application/octet-stream"
    }

    /** A turn in flight, and the three things that can end one. */
    private class Job(
        val id: String,
        val name: String,
        val cancelled: AtomicBoolean,
        val stdin: Gate,
        val out: Answer,
    ) {
        /** The approval id outstanding right now, or null. */
        @Volatile
        var approval: String? = null
    }

    /** What a turn ended as, in the terminal's own words, for a page that is not a terminal. */
    private fun say(turn: AgentTurn): String = when (turn) {
        AgentTurn.ANSWERED -> "the model finished, and the answer is in the transcript"
        AgentTurn.INTERRUPTED -> "stopped; what had arrived is in the transcript"
        AgentTurn.REFUSED -> "the endpoint or the key was refused; the transcript is unchanged"
        AgentTurn.NOT_CONFIGURED -> "this conversation has no provider, model and endpoint set"
    }

    companion object {
        /**
         * The most transcript entries one request carries.
         *
         * A chat shows the recent conversation; a transcript may be eight megabytes, and a phone
         * rendering one is not the place to ask for all of it. The total goes out beside the cut.
         */
        const val SHOWN_ENTRIES = 500

        const val JSON = "application/json; charset=utf-8"
        const val TEXT = "text/plain; charset=utf-8"

        /**
         * The names the provisioning fields go out under in `GET /api/state`.
         *
         * Constants and not strings at the call, because a page and a test are two readers of
         * these names and a renamed one that still compiled is a field that silently stopped
         * arriving. They are public for the same reason: a page's script is allowed to know what
         * this route publishes.
         */
        const val PROVISIONED = "provisioned"
        const val PROVISION_ROOTFS = "provisionRootfs"
        const val PROVISION_AGENT = "provisionAgent"
        const val PROVISION_RUNNING = "provisionRunning"
        const val PROVISION_ABI = "provisionAbi"
        const val PROVISION_PHASE = "provisionPhase"
        const val PROVISION_ARTIFACT = "provisionArtifact"
        const val PROVISION_RECEIVED = "provisionReceived"
        const val PROVISION_TOTAL = "provisionTotal"
        const val PROVISION_OUTCOME = "provisionOutcome"
        const val PROVISION_LINE = "provisionLine"
        private val UTF8 = StandardCharsets.UTF_8
    }
}

/**
 * The stdin one turn runs against: one byte per question, and nothing else.
 *
 * [Session] reads exactly one byte for its approval prompt and for no other reason during a turn,
 * so [read] is called once per question. It asks [onAsk] first — which is how the page is told
 * there is something to answer — and then parks in [LinkedBlockingQueue.take] until the browser
 * answers. That parking is the same blocking the terminal's own read does, with the same
 * consequence: a turn that is waiting for a person is waiting for a person, and the thread it
 * occupies is one of [LocalServer]'s four.
 */
private class Gate : InputStream() {

    /** Called once per question, before this thread blocks. Set by the owner of the turn. */
    @Volatile
    var onAsk: (() -> Unit)? = null

    private val answers = LinkedBlockingQueue<Int>()

    override fun read(): Int {
        onAsk?.invoke()
        return try {
            answers.take()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            -1
        }
    }

    /** One byte and no more: the inherited version would block for a whole buffer's worth. */
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val one = read()
        if (one < 0) return -1
        buffer[offset] = one.toByte()
        return 1
    }

    override fun available(): Int = 0

    /** `y` proceeds; anything else declines, exactly as the byte on a terminal would. */
    fun answer(allow: Boolean) {
        answers.offer(if (allow) 'y'.code else 'n'.code)
    }

    /** End of input, which the agent reads as a cancellation at the prompt. */
    fun cancel() {
        answers.offer(-1)
    }
}

/**
 * The agent's output on stdout, turned into events.
 *
 * **A line this app printed and a line the model said are told apart by this app's own
 * convention.** Every line the agent writes about itself carries its own tag — `omp: ` — or is a
 * two-space-indented continuation of one, which is the shape its tool output takes. Anything else
 * on a line is the model speaking. It is the same rule the terminal's layout is read with, and it
 * is a **presentation** rule only: a model that began a line with `omp: ` would have that line
 * styled as a note, and what it actually said is in the transcript, which is what the page's
 * message bubbles are built from.
 *
 * **A partial line is held only long enough to decide.** Five characters is the length of
 * `omp: `, so a line that is going to be a note is not shown until it ends, and a line that is the
 * model's is streamed from its sixth character on. Holding a model's line to its newline would be
 * holding the answer to the end of the answer.
 *
 * **A character can straddle two writes**, so bytes are decoded at a character boundary rather
 * than by `String(bytes)`, which would turn one emoji at the edge of a token into three
 * replacement characters, once per token.
 */
private class Answer : OutputStream() {

    private val lock = Any()
    private val line = StringBuilder()
    private val notes = ArrayDeque<String>()
    private val tail = ByteArray(4)
    private var held = 0
    private var decided = false

    /** Where every event goes. Null once the turn is over, so a late write is dropped. */
    @Volatile
    var emit: ((String, String) -> Unit)? = null

    /** The lines this app printed since the model last spoke: the approval prompt, verbatim. */
    fun recentNotes(): List<String> = synchronized(lock) { notes.toList() }

    override fun write(one: Int) {
        write(byteArrayOf(one.toByte()), 0, 1)
    }

    override fun write(source: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val all = ByteArray(held + length)
        System.arraycopy(tail, 0, all, 0, held)
        System.arraycopy(source, offset, all, held, length)
        val end = boundary(all, all.size)
        held = all.size - end
        System.arraycopy(all, end, tail, 0, held)
        if (end > 0) synchronized(lock) { consume(String(all, 0, end, StandardCharsets.UTF_8)) }
    }

    /**
     * The index just past the last whole character in `all[0, size)`.
     *
     * A UTF-8 sequence is at most four bytes, and one that runs off the end of what has arrived is
     * one whose remaining bytes are in the next write. Walking back over continuation bytes finds
     * where that sequence starts, and the length its lead byte announces says whether it is whole.
     */
    private fun boundary(all: ByteArray, size: Int): Int {
        var i = size
        var back = 0
        while (i > 0 && back < 4) {
            val b = all[i - 1].toInt() and 0xFF
            if (b and 0xC0 == 0x80) {
                i--
                back++
                continue
            }
            val announced = when {
                b and 0x80 == 0x00 -> 1
                b and 0xE0 == 0xC0 -> 2
                b and 0xF0 == 0xE0 -> 3
                b and 0xF8 == 0xF0 -> 4
                else -> 1
            }
            val start = i - 1
            return if (start + announced <= size) size else start
        }
        return size
    }

    private fun consume(text: String) {
        for (c in text) {
            // A carriage return ends a line here as well. It is how the agent's watchdog writes a
            // "still waiting" footnote on a terminal's cursor line, and a page has no cursor line
            // to put a footnote on, so the two halves of that trick become two lines.
            if (c == '\n' || c == '\r') {
                endLine()
                continue
            }
            line.append(c)
            if (!decided && line.length == DECISION) decide()
            if (decided) flushLine()
        }
    }

    private fun decide() {
        decided = !note(line.toString())
        if (decided) flushLine()
    }

    private fun endLine() {
        val text = line.toString()
        line.setLength(0)
        if (!decided) decided = !note(text)
        if (decided) {
            flushLine()
        } else {
            if (text.isNotEmpty()) {
                notes.addLast(text)
                while (notes.size > NOTES_KEPT) notes.removeFirst()
                send("note", text)
            }
        }
        decided = false
    }

    private fun flushLine() {
        if (line.isEmpty()) return
        val text = line.toString()
        line.setLength(0)
        // The model's own words reset the note buffer: the lines above an approval prompt are the
        // ones printed since it last spoke, and nothing before that is part of the question.
        notes.clear()
        send("text", text)
    }

    /** Whether a line so far is one this app printed rather than one the model said. */
    private fun note(start: String): Boolean =
        start.startsWith("omp:") || start.startsWith("  ") || start.startsWith("omp[")

    private fun send(kind: String, text: String) {
        val out = emit ?: return
        val payload = Json.Obj(
            linkedMapOf("t" to Json.Str(kind), "text" to Json.Str(text)),
        ).toCompactString()
        out(kind, payload)
    }

    private companion object {
        /** `omp: ` is five characters, and that is all a line's identity is decided on. */
        const val DECISION = 5

        /** How many of the app's own lines are kept to be the approval prompt. */
        const val NOTES_KEPT = 24
    }
}

/**
 * The agent's stderr, forwarded line by line.
 *
 * **No classification and no rewriting.** Every refusal the agent produces — no key, a bad
 * endpoint, a state file from a newer build — reaches the page in the words the terminal shows,
 * because this app has exactly one set of words for those and a second set in a web page is a
 * second set to keep true.
 */
private class Diagnostics(private val emit: (String, String) -> Unit) : OutputStream() {

    private val line = StringBuilder()

    private fun consume(letter: Char) {
        consume(letter.toString())
    }

    override fun write(one: Int) {
        consume(one.toChar())
    }


    override fun write(source: ByteArray, offset: Int, length: Int) {
        consume(String(source, offset, length, StandardCharsets.UTF_8))
    }

    private fun consume(text: String) {
        for (c in text) {
            if (c == '\n' || c == '\r') {
                if (line.isNotEmpty()) {
                    val payload = Json.Obj(
                        linkedMapOf("t" to Json.Str("refusal"), "text" to Json.Str(line.toString())),
                    ).toCompactString()
                    emit("refusal", payload)
                    line.setLength(0)
                }
            } else {
                line.append(c)
            }
        }
    }
}

/**
 * Server-sent events, written by hand.
 *
 * **The grammar is `omp.agent.http.Sse`'s**: `data:` lines, a blank line ending an event, and
 * `data: [DONE]` to finish. That is a format the agent's own HTTP client already parses, so the
 * stream this server writes is one that code can read, and one a browser's `EventSource`
 * understands with nothing on the page at all.
 *
 * Each event carries its name twice — in the `event:` line, and as `"t"` in the JSON — because the
 * two readers in this project want opposite halves of that. [ChatApi]'s KDoc says why.
 */
private class Sse(private val response: Response) {

    fun event(name: String, data: String) {
        response.chunk("event: $name\ndata: $data\n\n")
    }

    /** The same event, from fields that have not been serialised yet. */
    fun event(name: String, data: Map<String, Json>) {
        event(name, Json.Obj(data).toCompactString())
    }

    /** The end of the stream, in the one word a reader of this grammar looks for. */
    fun end() {
        response.chunk("data: [DONE]\n\n")
    }
}

/** Where the output of a context that asks nothing goes. */
private object NoOutput : OutputStream() {
    override fun write(one: Int) = Unit
    override fun write(source: ByteArray, offset: Int, length: Int) = Unit
}

/** Where the input of a context that asks nothing comes from: immediately at end of input. */
private object NoInput : InputStream() {
    override fun read(): Int = -1
}
