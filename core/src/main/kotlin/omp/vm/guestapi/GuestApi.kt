package omp.vm.guestapi

/**
 * The API the guest serves, as data: every route, every field, every status code and the exact
 * sentence in every refusal.
 *
 * **This is the contract, and its two sides are a phone's WebView and a PHP file in a Debian that
 * has never been booted.** The page is `app/src/main/assets/web/app.js` — served as written, with
 * no bundler and no build step, and read here rather than copied, because a copy is a second thing
 * to keep true. Every shape below was derived from that file and not from the app's own Kotlin
 * server: the browser is what runs, so the browser decides what it expects, and
 * [omp.vm.guestapi.GuestApiContractTest] is what fails when this table and that file disagree in
 * either direction.
 *
 * ### What is here and what is not
 *
 * The three static pages — `/`, `/app.css`, `/app.js` — are **not** routes in this table. Debian's
 * Apache serves them out of the document root as files, which is [omp.vm.provision.WebRoot]'s whole
 * job, and listing them here would imply the guest's PHP decides what a stylesheet is. What the PHP
 * decides is what a URL under `/api/` means.
 *
 * **There is no `/login` here, and there is no `401`.** The app's own loopback server has a token
 * and a login page, because a loopback port is reachable by every app on the device. The guest's
 * Apache is not that server: it is an Apache inside the Debian, reaching the conversations folder
 * and nothing else, and the page that opens it is the app's own. A `401` here would be a response
 * the page answers by replacing itself with `/login` — a page this guest does not serve, for a
 * server that never asked for a token. The app's server keeps its gate; the guest does not pretend
 * to have one.
 *
 * ### The answers
 *
 * | method | path | answers |
 * |---|---|---|
 * | `GET` | `/api/state` | `200` JSON: where the conversations are on this phone, and which there are |
 * | `POST` | `/api/conversations` | `201` JSON: a folder made, the way `omp new` makes it |
 * | `GET` | `/api/conversations/{name}/transcript` | `200` JSON: one conversation, as it is recorded |
 * | `POST` | `/api/conversations/{name}/message` | `200 text/event-stream`: a question asked, the answer streamed |
 * | `POST` | `/api/approvals/{id}` | `204`: the answer to a write being asked about |
 * | `POST` | `/api/turns/{id}/cancel` | `204`: an answer that is running is stopped |
 *
 * ### The refusal, and why the sentences are not this file's to choose
 *
 * Every non-2xx body is `{"error":"<sentence>"}`, because that is what the page reads: it parses the
 * body, takes `error`, and shows it in the state line. **The sentences are the app's own**, from
 * `app/src/main/java/com/omp/terminal/web/ChatApi.kt`, so a refusal is one sentence in this project
 * rather than two that drift apart — and the contract test checks that every literal `error("…")` in
 * that file which this server could answer has a [Refusal] here.
 *
 * Two refusals the app's server answers with a sentence this file does not hold, and both are
 * spelled out in their [Refusal.says]:
 *
 * - **`503`, the container.** The app's is `omp.vm.HostAccess`'s, because on a phone the question is
 *   whether the *platform* granted all-files access. Inside the guest the same question is "is
 *   `/mnt/omp` a directory", and the shell's own errno wording is the honest answer to that.
 * - **`404`, a conversation that is not there.** Same reason and the same shape: `<path>: No such
 *   file or directory`, from `omp.shell.exec.Errno`.
 *
 * ### The streamed answer
 *
 * `POST /api/conversations/{name}/message` answers `200`, `Content-Type: text/event-stream`, and no
 * `Content-Length`: the body ends when the connection closes. The bytes are **Server-Sent Events** —
 * the grammar `omp.agent.http.Sse` in `:core` already implements on the client side, so the stream
 * this describes is one this project's own reader can consume and one a browser reads with `fetch`
 * and a `ReadableStream` and no library at all.
 *
 * Every event carries its name **twice**: in the `event:` line a browser dispatches on, and as
 * `"t"` in the JSON in `data:`. That is not decoration — `omp.agent.http.Sse` reads `event:` and
 * drops it, so a payload whose name lived only in the event line would be an anonymous string to
 * the one reader in this project that has a test on it.
 *
 * **The last event is `data: [DONE]`, and the connection closes after it.** The page deliberately
 * keeps reading past `[DONE]` to the end of the body, so the server has to end the body; a stream
 * that stopped at `[DONE]` would leave a reader waiting on a socket nobody closes.
 */

/** The two methods the contract uses, as HTTP spells them. */
enum class Method(val wire: String) {
    GET("GET"),
    POST("POST"),
}

/**
 * The JSON types the contract uses, as JSON spells them.
 *
 * A type is here rather than a prose description because the drift test compares types: the page
 * hands a `modified` straight to `new Date(…)` and a `configured` to an `if`, so "a number" and "a
 * string" are not interchangeable and the difference is a page that draws nothing.
 */
enum class JType(val wire: String) {
    STRING("string"),
    INTEGER("integer"),
    BOOLEAN("boolean"),
    OBJECT("object"),
    ARRAY("array"),
    OBJECT_ARRAY("array of objects"),
}

/**
 * One field of a body, and everything about it a reader has to be told.
 *
 * @param required whether the field is one the guest must send. A response field is required where
 *   the page reads it without checking; an optional response field is one the page reads only when
 *   it is there.
 * @param element the fields of one element, for [JType.OBJECT] and [JType.OBJECT_ARRAY]. Null for
 *   every other type, and the reason [Body.elementOf] is a lookup rather than a cast.
 * @param says what the field is for.
 */
data class Field(
    val name: String,
    val type: JType,
    val required: Boolean,
    val says: String,
    val element: Body? = null,
)

/**
 * A JSON object, as a list of [Field]s.
 *
 * A flat list with a shape on the two types that have one, rather than a tree of sealed classes:
 * the whole contract is a table somebody has to be able to read in a diff, and a table that is a
 * tree is a table nobody reads.
 */
data class Body(val fields: List<Field>) {

    /** The field names, in the order the page meets them. */
    val names: List<String> get() = fields.map { it.name }

    fun field(name: String): Field? = fields.firstOrNull { it.name == name }

    /** The fields of one element of [name], or an empty list when it has no shape. */
    fun elementOf(name: String): List<Field> = field(name)?.element?.fields.orEmpty()
}

/**
 * An answer that is not the one, and the sentence the browser will read out of it.
 *
 * **A [sentence] is a template**, with `{name}`, `{id}`, `{path}` and `{errno}` in it, because a
 * template is what both sides can fill and compare: the guest's PHP builds the string by the same
 * substitution, and the fake in the test builds it from this object, so a drift in a substituted
 * value is a difference in the bytes rather than an opinion about a format.
 */
data class Refusal(val status: Int, val sentence: String, val says: String) {

    /** The placeholders in [sentence], in the order they appear. */
    val placeholders: List<String> = PLACEHOLDER.findAll(sentence)
        .map { it.groupValues[1] }
        .toList()

    /** The exact `error` string for one request, filled from [values]. */
    fun fill(values: Map<String, String> = emptyMap()): String =
        PLACEHOLDER.replace(sentence) { match -> values[match.groupValues[1]] ?: match.value }

    /** The exact body, which is the one shape the page knows how to read a refusal out of. */
    fun body(values: Map<String, String> = emptyMap()): String = "{\"error\":${quote(fill(values))}}"

    private companion object {
        val PLACEHOLDER = Regex("""\{(\w+)}""")
    }
}

/** One event on the stream, with the fields its payload carries. */
data class Event(val name: String, val fields: List<Field>, val says: String) {

    /** The payload, as a body, so a reader can ask the same questions of it as of a response. */
    val body: Body get() = Body(fields)
}

/**
 * One route, whole.
 *
 * @param events the server-sent events this route can carry, in the order the page meets them. A
 *   non-empty list **is** the definition of a streamed route and [mediaType] follows from it, so
 *   the two cannot be set inconsistently.
 */
data class Route(
    val method: Method,
    /** The path shape, with `{name}` and `{id}` in it — the spelling the page builds. */
    val path: String,
    val says: String,
    /** The body the page sends, or null when it sends none. */
    val request: Body?,
    val success: Int,
    val response: Body,
    val refusals: List<Refusal>,
    val events: List<Event> = emptyList(),
) {
    /** Whether this route answers with a stream rather than with a document. */
    val streamed: Boolean get() = events.isNotEmpty()

    /** What the answer's `Content-Type` is, and there is no third case. */
    val mediaType: String get() = if (streamed) GuestApi.EVENT_STREAM else GuestApi.JSON

    /** The refusal with [status], or null when this route does not answer with it. */
    fun refusal(status: Int): Refusal? = refusals.firstOrNull { it.status == status }
}

/**
 * The contract, and the only place in this project that says what the guest serves.
 *
 * **Nothing here runs.** `:core` can check that this table and the page agree, and that the table
 * is servable over a socket, because the fake is Kotlin. **The PHP that serves it is untested** —
 * this build has no PHP and no MySQL — and every file under `app/src/main/assets/guest/` says so in
 * its own first comment, because a reader in six months will otherwise assume it was run.
 */
object GuestApi {

    const val JSON = "application/json; charset=utf-8"
    const val TEXT = "text/plain; charset=utf-8"
    const val EVENT_STREAM = "text/event-stream"

    /** The word that ends a stream, and the only one a reader of this grammar looks for. */
    const val DONE = "[DONE]"

    /** How many entries one transcript answer carries, which is the app's own limit. */
    const val SHOWN_ENTRIES = 500

    // ---- the fields ---------------------------------------------------------------------------

    private fun conversationFields() = listOf(
        Field("name", JType.STRING, true, "the folder's own name, compared exactly."),
        Field("title", JType.STRING, true, "what the conversation is called, or its name."),
        Field(
            "modified", JType.INTEGER, true,
            "milliseconds since the epoch; the page puts it straight into `new Date(…)`.",
        ),
        Field("hostPath", JType.STRING, true, "this folder as a file manager shows it."),
        Field(
            "configured", JType.BOOLEAN, true,
            "whether this conversation could be answered at all — for the guest, whether the " +
                "environment holds a key for its provider.",
        ),
        Field(
            "keyStored", JType.BOOLEAN, true,
            "whether a key is on file. **A question about a file's existence and never about its " +
                "contents**, which is the whole of what the terminal's own `omp key --show` " +
                "reports either.",
        ),
        Field("provider", JType.STRING, true, "the provider's name, or an empty string."),
        Field("model", JType.STRING, true, "the model, or an empty string."),
    )

    private val STATE_FIELDS = listOf(
        Field(
            "version", JType.STRING, true,
            "what is answering, so a page served by an older guest says so rather than failing " +
                "quietly. The app's own server puts its build version here.",
        ),
        Field(
            "hostRoot", JType.STRING, true,
            "the conversations folder as a **file manager** shows it — the phone's own path, not " +
                "the guest's bind — because the page offers to copy it and a user pastes it into a " +
                "file manager.",
        ),
        Field(
            "conversations", JType.OBJECT_ARRAY, true,
            "one entry per conversation folder, in the order the directory lists them.",
            element = Body(conversationFields()),
        ),
    )

    private val CREATED_FIELDS = listOf(
        Field("name", JType.STRING, true, "the name the folder ended up with, after sanitising."),
        Field("path", JType.STRING, true, "the folder inside the guest."),
        Field("hostPath", JType.STRING, true, "the same folder as a file manager shows it."),
    )

    private val TRANSCRIPT_FIELDS = listOf(
        Field("conversation", JType.STRING, true, "the name that was asked for."),
        Field("hostPath", JType.STRING, true, "the folder, as a file manager shows it."),
        Field(
            "transcriptPath", JType.STRING, true,
            "the file the whole conversation is in, for a page that has just shown a truncated " +
                "list and needs to say where the rest of it is.",
        ),
        Field("total", JType.INTEGER, true, "how many entries there are, however many are shown."),
        Field("shown", JType.INTEGER, true, "how many of them this answer carries."),
        Field(
            "skipped", JType.OBJECT_ARRAY, false,
            "lines left out and why. **Empty for the guest**, and empty honestly: a row in the " +
                "guest's own database is never half-written, so there is no truncated line to " +
                "report. The field is here because the page reads it.",
            element = Body(
                listOf(
                    Field("offset", JType.INTEGER, true, "the byte the line started at."),
                    Field("bytes", JType.INTEGER, true, "how many bytes it was."),
                    Field("reason", JType.STRING, true, "why it was left out."),
                ),
            ),
        ),
        Field(
            "entries", JType.OBJECT_ARRAY, true,
            "the recent entries, oldest first, which is the order a conversation happened in.",
            element = Body(
                listOf(
                    Field(
                        "role", JType.STRING, true,
                        "one of `user`, `assistant`, `tool-result`; anything else is drawn as system.",
                    ),
                    Field("content", JType.STRING, true, "the text, as it was recorded."),
                    Field(
                        "tool", JType.STRING, false,
                        "the tool's name, on a `tool-result`. The page draws it under the body.",
                    ),
                    Field(
                        "approved", JType.STRING, false,
                        "how a write was answered — `y` by a person, `auto` by `omp --yes`. **The " +
                            "guest never sends it**: its agent is asked about a write by nobody, so " +
                            "there is no approval to record, and the column that would have held it " +
                            "is not in the guest's schema. It is here because the page reads it and " +
                            "the app's own server sends it.",
                    ),
                ),
            ),
        ),
    )

    private val TEXT_FIELD = Field(
        "text", JType.STRING, true,
        "the model's own words arriving, or one line of something discrete.",
    )

    // ---- the routes ---------------------------------------------------------------------------

    /** Every route, in the order the page meets them. */
    val ROUTES: List<Route> = listOf(
        Route(
            method = Method.GET,
            path = "/api/state",
            says = "what there is, and where it is on this phone. The page draws it before " +
                "anything else and lists every conversation from it.",
            request = null,
            success = 200,
            response = Body(STATE_FIELDS),
            refusals = listOf(
                Refusal(
                    503,
                    "{path}: No such file or directory",
                    "the conversations folder is not there. The app's own server answers this with " +
                        "`omp.vm.HostAccess`'s sentence, because on a phone the question is whether " +
                        "the platform granted all-files access; inside the guest the same question " +
                        "is whether the container is a directory, and the shell's own errno wording " +
                        "is the honest answer to that.",
                ),
            ),
        ),
        Route(
            method = Method.POST,
            path = "/api/conversations",
            says = "makes one folder, the way `omp new` makes it: the same sanitising, the same " +
                "`-2` for a name already in use, and the same generated name for a name that " +
                "sanitises away to nothing.",
            request = Body(
                listOf(
                    Field(
                        "name", JType.STRING, false,
                        "what the user typed. **Optional**: a blank or absent name is a request for " +
                            "a generated one, which is what the app's own server does.",
                    ),
                ),
            ),
            success = 201,
            response = Body(CREATED_FIELDS),
            refusals = listOf(
                Refusal(
                    409,
                    "{path}: {errno}",
                    "the folder could not be made. The app's own server passes the shell's errno " +
                        "sentence through unchanged, and so does this.",
                ),
            ),
        ),
        Route(
            method = Method.GET,
            path = "/api/conversations/{name}/transcript",
            says = "one conversation as it is recorded, and no more than the last $SHOWN_ENTRIES " +
                "entries of it: a chat shows the recent conversation, and the total goes out " +
                "beside the cut so a truncated list is never a silent one. The limit is the app's " +
                "own `ChatApi.SHOWN_ENTRIES`; the guest does not get a second one.",
            request = null,
            success = 200,
            response = Body(TRANSCRIPT_FIELDS),
            refusals = listOf(
                Refusal(
                    404,
                    "{path}: No such file or directory",
                    "there is no conversation of that name. The name in the path is compared exactly " +
                        "against the container's own children, and a name that walks out of the " +
                        "container does not reach one of them at all.",
                ),
            ),
        ),
        Route(
            method = Method.POST,
            path = "/api/conversations/{name}/message",
            says = "asks one question and streams the answer back as it arrives. **One turn per " +
                "conversation**: a second message for a conversation that is already answering is a " +
                "409, because two answers appended to one conversation's history would interleave " +
                "into something that is no longer an ordered conversation.",
            request = Body(listOf(Field("text", JType.STRING, true, "what the user typed."))),
            success = 200,
            response = Body(emptyList()),
            refusals = listOf(
                Refusal(
                    400,
                    "a message needs a 'text' field",
                    "the body had no `text`, or one that was blank. **The app's own sentence, " +
                        "verbatim** — and the one refusal in this contract that is a whole string " +
                        "rather than a sentence with a path in it.",
                ),
                Refusal(
                    404,
                    "{path}: No such file or directory",
                    "there is no conversation of that name.",
                ),
                Refusal(
                    409,
                    "'{name}' is already answering a question; wait for it, or cancel it",
                    "a turn is already running for this conversation. The app's own sentence, " +
                        "verbatim.",
                ),
            ),
            events = listOf(
                Event(
                    "open",
                    listOf(
                        Field("conversation", JType.STRING, true, "the name that was asked for."),
                        Field("hostPath", JType.STRING, true, "the folder, as a file manager shows it."),
                        Field(
                            "turn", JType.STRING, true,
                            "the id of this turn, and the only thing `/api/turns/{id}/cancel` takes.",
                        ),
                    ),
                    "first, always, and before a single word of the answer: the page shows its Stop " +
                        "button on this event and on no other.",
                ),
                Event(
                    "text", listOf(TEXT_FIELD),
                    "the model's own words, arriving. The page replaces the line it is drawing with " +
                        "each one, so this is a delta and not the whole answer.",
                ),
                Event(
                    "note", listOf(TEXT_FIELD),
                    "something discrete that is not the model speaking — a tool the agent ran, a " +
                        "line it printed about itself. The page keeps these above the next one " +
                        "rather than overwriting the line below them.",
                ),
                Event(
                    "refusal", listOf(TEXT_FIELD),
                    "a line the agent wrote to **stderr**, forwarded word for word: no key, a bad " +
                        "endpoint, a state file from a newer build. **No rewriting and no second " +
                        "vocabulary**, because this project has exactly one set of words for a " +
                        "refusal and a second set in a web page is a second set to keep true.",
                ),
                Event(
                    "approval",
                    listOf(
                        Field(
                            "id", JType.STRING, true,
                            "the question's id, and the only thing `/api/approvals/{id}` takes. **One " +
                                "id per question, not per turn** — a model that asks for two writes " +
                                "in one turn asks twice.",
                        ),
                        Field("conversation", JType.STRING, true, "which conversation asked."),
                        Field(
                            "lines", JType.ARRAY, false,
                            "the lines printed immediately above the question, which is what the " +
                                "page shows in the dialog. Forwarded, not reformatted.",
                        ),
                    ),
                    "a write the agent wants to make, put to the user. **The guest never emits " +
                        "this**, and the reason is measured and written down in [AgentCommand]: the " +
                        "agent is run in print mode with its standard input at end of input, " +
                        "because that is the only input a child of a PHP process can be given here, " +
                        "and a question read from that is a decline. A page told a question is " +
                        "coming and never asked is worse than a page never told, so the event is in " +
                        "the contract because the page requires it and the guest does not use it.",
                ),
                Event(
                    "answered",
                    listOf(Field("allow", JType.BOOLEAN, true, "what the user chose.")),
                    "the dialog has closed. The page reads nothing from it, and the agent's own " +
                        "server emits it for the same reason: the state is the dialog being gone.",
                ),
                Event(
                    "turn",
                    listOf(
                        Field("conversation", JType.STRING, true, "which conversation."),
                        Field("turn", JType.STRING, true, "the turn's id, again."),
                        Field(
                            "message", JType.STRING, true,
                            "how the turn ended, in a sentence the page can show without " +
                                "interpreting it.",
                        ),
                    ),
                    "last before `[DONE]`. **How it ended is one of [TURN_MESSAGES]** and the guest " +
                        "uses the app's own four.",
                ),
                Event(
                    "error", listOf(TEXT_FIELD),
                    "the turn failed, and the page is told rather than left watching a stream that " +
                        "stopped for a reason it cannot see. **A sentence, never a PHP message** — a " +
                        "stack trace on a phone is a page of source in the middle of a conversation.",
                ),
            ),
        ),
        Route(
            method = Method.POST,
            path = "/api/approvals/{id}",
            says = "the answer to a write being asked about, and only the answer. **`y` goes ahead " +
                "and anything else declines**, which is all the vocabulary the agent's own question " +
                "has.",
            request = Body(
                listOf(
                    Field(
                        "allow", JType.BOOLEAN, true,
                        "the button that was pressed. Anything that is not `true` declines, so a " +
                            "body that is not a boolean at all is a decline rather than an error.",
                    ),
                ),
            ),
            success = 204,
            response = Body(emptyList()),
            refusals = listOf(
                Refusal(
                    404,
                    "no write is waiting for an answer under '{id}'",
                    "no question of that id is outstanding. A 404 and not a 200, because a page " +
                        "that has just been reopened will click approve on a prompt whose answer has " +
                        "already been given, and telling it \"fine\" would be a lie about a decision " +
                        "it did not make. **The guest takes this branch for every id** — see this " +
                        "route's `approval` event for why — and the sentence is still the app's " +
                        "own, because a refusal that says something else on this server is two " +
                        "sentences for one event.",
                ),
            ),
        ),
        Route(
            method = Method.POST,
            path = "/api/turns/{id}/cancel",
            says = "stops an answer that is running. **No body and no content**: the page sends " +
                "neither, and an answer that is already over is not a failure.",
            request = null,
            success = 204,
            response = Body(emptyList()),
            refusals = listOf(
                Refusal(
                    404,
                    "no turn '{id}' is running",
                    "no turn of that id is in flight. The app's own sentence, verbatim.",
                ),
            ),
        ),
    )

    // ---- the stream ---------------------------------------------------------------------------

    /**
     * Every event name, in the order they can arrive.
     *
     * A name the page does not handle is an event the guest can emit into a page that ignores it,
     * so the drift test compares this list with the `name === "…"` arms of the page's own
     * `onEvent`, in both directions.
     */
    val EVENTS: List<Event> = ROUTES.flatMap { it.events }

    /**
     * The four sentences a turn can end as.
     *
     * The app's own set, verbatim, and not a fifth one: the page shows this string as it is, so a
     * guest that invented a fifth would be a sentence only a guest ever says.
     */
    val TURN_MESSAGES = listOf(
        "the model finished, and the answer is in the transcript",
        "stopped; what had arrived is in the transcript",
        "the endpoint or the key was refused; the transcript is unchanged",
        "this conversation has no provider, model and endpoint set",
    )

    /** Every route's path shape, for a report and for the drift test. */
    val PATHS: List<String> = ROUTES.map { it.path }

    /** The route for one method and one **shape** — `/api/conversations/{name}/transcript`. */
    fun route(method: Method, path: String): Route? =
        ROUTES.firstOrNull { it.method == method && it.path == path }

    /**
     * The shape of a concrete path, or null when it names no route.
     *
     * `{name}` in a contract path matches **one** path segment and nothing else: a segment holding a
     * `/` is not a name, and a route that matched across a separator would be a route whose
     * parameter is a path.
     */
    fun shapeOf(method: Method, path: String): String? {
        val wanted = path.trim('/').split('/')
        for (candidate in ROUTES.filter { it.method == method }) {
            val parts = candidate.path.trim('/').split('/')
            if (parts.size != wanted.size) continue
            if (parts.indices.all { parts[it].isPlaceholder() || parts[it] == wanted[it] }) {
                return candidate.path
            }
        }
        return null
    }

    /** The placeholder's name at [index] of this shape, or null when it names no route. */
    fun parameter(path: String, index: Int): String? {
        val parts = path.trim('/').split('/')
        val name = parts.getOrNull(index)?.takeIf { it.isPlaceholder() } ?: return null
        return name.trim('{', '}')
    }

    private fun String.isPlaceholder(): Boolean =
        length > 2 && startsWith("{") && endsWith("}") && isNotBlank()

}

/**
 * One JSON string literal, escaped.
 *
 * A refusal sentence is a person's English with a path in it, and a path on a phone can hold a
 * quote, a backslash and a control character. Emitting one unescaped would produce a body the
 * browser's `JSON.parse` refuses, and the page would show `500 from /api/…` where the reason was
 * one line away.
 */
private fun quote(text: String): String {
    val out = StringBuilder(text.length + 2)
    out.append('"')
    for (c in text) {
        when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
        }
    }
    out.append('"')
    return out.toString()
}
