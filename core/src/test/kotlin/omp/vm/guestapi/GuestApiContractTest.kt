package omp.vm.guestapi

import omp.agent.http.Sse
import omp.agent.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * The two halves of the thing this slice exists for: **the front end and the guest cannot disagree
 * about a route, a field, a sentence or a flag, and the contract is servable.**
 *
 * ### The first half: drift, in both directions
 *
 * `app/src/main/assets/web/app.js` is read off this disk — the same file the APK ships and the
 * guest's Apache serves — and the contract in [GuestApi] is compared with it:
 *
 * 1. every URL the page fetches is a route in the contract, with the same method;
 * 2. every route in the contract is a URL the page fetches — **so neither side can grow a route the
 *    other does not know**, which is the half a one-way check never catches;
 * 3. every event the contract names is an arm of the page's own `onEvent`, and every arm is an
 *    event the contract names;
 * 4. every field the page reads off a response is a field that route declares, checked per
 *    variable, so a page that starts reading `transcript.total` fails on the transcript route
 *    rather than on a union that would let any field satisfy any route;
 * 5. every refusal sentence the app's own server holds is a sentence the contract holds, with the
 *    one documented exclusion of the token gate, which is not a refusal this server can make;
 * 6. every flag the guest runs is a flag whose own help text is quoted in [AgentCommand];
 * 7. the guest's PHP passes exactly the vector [AgentCommand] claims — **a flag invented in one
 *    place and not the other is the failure that does nothing on a phone.**
 *
 * ### The second half: it is servable
 *
 * [GuestApiFake] answers the whole contract over a real loopback socket, built from the contract
 * rather than from a copy of it, and this file drives it: every route, every refusal, and a
 * **scripted answer read back with `omp.agent.http.Sse`** — the project's own grammar — while the
 * timestamps prove the events arrived one at a time rather than in a lump at the end.
 *
 * **What is not verified here, and cannot be.** None of the PHP has run: this build has no PHP and
 * no MySQL, so the guest's implementation is a description of what a program will do, and the
 * first thing that can show whether it works is a phone with a booted Debian in it.
 */
class GuestApiContractTest {

    // ---- the drift ----------------------------------------------------------------------------

    @Test
    fun everyUrlThePageFetchesIsARouteInTheContract() {
        val calls = pageCalls()
        assertTrue("no /api/ call was found in the page; the parser has stopped working", calls.isNotEmpty())
        for (call in calls) {
            // The page spells a parameter `{}` and the contract spells it `{name}`; the comparison is
            // on the flattened shape, so a route whose *name* drifted is not a failure and a route
            // whose *position* is, is.
            val route = GuestApi.ROUTES.firstOrNull {
                it.method == call.method && shape(it.path) == call.shape
            }
            assertNotNull(
                "${call.method.wire} ${call.shape} is fetched by ${call.where} and the contract has " +
                    "no route for it",
                route,
            )
        }
    }

    @Test
    fun everyRouteInTheContractIsAUrlThePageFetches() {
        val used = pageCalls().map { it.method to it.shape }.toSet()
        for (route in GuestApi.ROUTES) {
            assertTrue(
                "${route.method.wire} ${route.path} is in the contract and the page never fetches " +
                    "it, so the guest would answer a URL nothing asks for",
                (route.method to shape(route.path)) in used,
            )
        }
    }

    @Test
    fun everyEventTheContractNamesIsHandledByThePage() {
        val handled = pageEvents()
        for (event in GuestApi.EVENTS) {
            assertTrue(
                "the contract emits a '${event.name}' event and the page's onEvent has no arm for " +
                    "it, so a turn that sent one would show nothing",
                event.name in handled,
            )
        }
    }

    @Test
    fun everyEventThePageHandlesIsInTheContract() {
        val named = GuestApi.EVENTS.map { it.name }.toSet()
        for (name in pageEvents()) {
            assertTrue(
                "the page's onEvent handles a '$name' event the contract does not name, so the " +
                    "guest could not send it",
                name in named,
            )
        }
    }

    @Test
    fun everyFieldThePageReadsIsDeclaredOnTheRouteThatAnswersIt() {
        val state = GuestApi.route(Method.GET, "/api/state")!!
        val transcript = GuestApi.route(Method.GET, "/api/conversations/{name}/transcript")!!
        val created = GuestApi.route(Method.POST, "/api/conversations")!!
        val everywhere = (GuestApi.ROUTES.flatMap { it.response.names } +
            GuestApi.EVENTS.flatMap { it.fields.map { f -> f.name } }).toSet()

        // The four variables the page binds to exactly one answer each. `got` is rebound per call,
        // so it is checked against every field the contract declares rather than against a route it
        // does not belong to.
        for (variable in listOf("state", "transcript", "made")) {
            assertTrue(
                "the page no longer binds `$variable`, so the per-route field check below would " +
                    "pass without checking anything",
                page.contains("$variable =") || page.contains("($variable)"),
            )
        }
        val perRoute = mapOf(
            "state" to state.response.names.toSet(),
            // `one` is two different parameters in the page — a row of `conversations[]` in the
            // list, and a transcript entry in `entry(one)` — so it is checked against both shapes.
            "one" to (
                state.response.elementOf("conversations") +
                    transcript.response.elementOf("entries")
                ).map { it.name }.toSet(),
            "transcript" to transcript.response.names.toSet(),
            "made" to created.response.names.toSet(),
            "got" to everywhere,
        )
        // `got.data` is the one pair this skips, and it is a local in the page's own SSE framing
        // (`var got = takeEvent(raw)`), not a response: the same word, a different thing.
        val read = VARIABLE_FIELD.findAll(page)
            .map { it.groupValues[1] to it.groupValues[2] }
            .filterNot { (variable, field) -> variable == "got" && field == "data" }
            .toList()
        assertTrue("no response field is read by the page at all", read.isNotEmpty())
        for ((variable, field) in read) {
            val where = if (variable == "one") {
                "GET /api/state's conversations[] entry"
            } else {
                "the route that answers `$variable`"
            }
            assertTrue(
                "the page reads `$variable.$field` and $where does not declare it",
                field in perRoute.getValue(variable),
            )
        }
    }

    @Test
    fun everyEventPayloadThePageReadsIsOnThatEventsOwnFields() {
        val fields = pageEventFields()
        for (name in pageEvents()) {
            val event = GuestApi.EVENTS.firstOrNull { it.name == name }
                ?: error("the page handles a '$name' event the contract does not name")
            for (field in fields[name].orEmpty()) {
                assertTrue(
                    "the page reads `data.$field` out of a '$name' event that does not carry it",
                    event.fields.any { it.name == field },
                )
            }
        }
    }

    @Test
    fun everyRefusalSentenceTheAppsOwnServerHoldsIsInTheContract() {
        val literal = ERROR_LITERAL.findAll(chatApi)
            .map { it.groupValues[1] }
            // `${…}` is a Kotlin interpolation over objects this file does not model, and a refusal
            // built from one is the errno shape, which the contract states directly.
            .filter { !it.contains("\${") }
            .map { sentence -> DOLLAR.replace(sentence) { "{${it.groupValues[1]}}" } }
            .toList()
        // The token gate belongs to the app's own loopback server and this guest has no token to
        // check, so those sentences are the one documented exclusion — and the second assertion is
        // that every sentence left out says so, so the exclusion cannot quietly grow.
        val (aboutAToken, aboutTheApi) = literal.partition { it.contains("token") }
        assertEquals(
            "app/src/main/java/com/omp/terminal/web/ChatApi.kt holds these refusal sentences with " +
                "no interpolation in them",
            listOf(
                "'{name}' is already answering a question; wait for it, or cancel it",
                "a message needs a 'text' field",
                "no turn '{id}' is running",
                "no write is waiting for an answer under '{id}'",
            ),
            aboutTheApi.sorted(),
        )
        assertEquals(
            "every sentence this test skips has to be about the token gate, and nothing else",
            listOf(
                "that is not this install's token",
                "this server wants this install's own token: {refused}",
            ),
            aboutAToken.sorted(),
        )
        val said = GuestApi.ROUTES.flatMap { route -> route.refusals.map { it.sentence } }.toSet()
        for (sentence in aboutTheApi) {
            assertTrue(
                "the app's own server answers with \"$sentence\" and the contract has no refusal " +
                    "carrying it, so the guest would say something else for the same event",
                sentence in said,
            )
        }
    }

    // ---- the flags ----------------------------------------------------------------------------

    @Test
    fun everyFlagTheGuestRunsIsInTheHelpTextItWasTakenFrom() {
        val help = AgentCommand.HELP.joinToString("\n")
        val flags = AgentCommand.FLAGS.filter { it.startsWith("-") } + listOf(
            AgentCommand.CONTINUE,
            AgentCommand.MODEL,
            "--cwd",
            "--session-dir",
        )
        for (flag in flags) {
            // The help spells a flag as `--mode=<value>` and the guest passes `--mode=json`, so
            // it is the name that is compared. A bare `-p` has no `=` and is its own name, which
            // is why the split is on `=` and nothing else.
            assertTrue(
                "`$flag` is not in the help text this file quotes, so either the flag does not " +
                    "exist or the excerpt is out of date — and a flag that does not exist is a " +
                    "feature that silently does nothing on a phone",
                help.contains(flag.substringBefore('=')),
            )
        }
        // A bare value is checked against the line of the flag it belongs to, because `--mode=json`
        // is not the spelling of anything and `--approval-mode`'s own line is where `write` is.
        val approval = AgentCommand.HELP.first { it.contains("--approval-mode") }
        assertTrue(
            "the approval mode the guest passes is not one the help lists",
            AgentCommand.FLAGS.filterNot { it.startsWith("-") }.all { approval.contains(it) },
        )
    }

    @Test
    fun theGuestsPhpRunsTheVectorThisFileClaims() {
        val php = guestAsset("api/Agent.php")
        val wanted = AgentCommand.FLAGS + listOf(
            AgentCommand.CONTINUE,
            AgentCommand.MODEL,
            AgentCommand.BINARY,
            "--cwd",
            "--session-dir",
        )
        for (token in wanted) {
            assertTrue(
                "app/src/main/assets/guest/api/Agent.php does not pass `$token`, and " +
                    "omp/vm/guestapi/AgentCommand.kt claims it does",
                php.contains("\"$token\"") || php.contains("'$token'"),
            )
        }
        assertTrue(
            "the guest's PHP does not send the question on standard input, which is the only form " +
                "in which a question beginning with @ is text",
            php.contains("['pipe', 'r']"),
        )
    }

    // ---- the shape, served over a socket ------------------------------------------------------

    @Test
    fun everyRouteTheContractNamesIsServedWithItsOwnShape() {
        withFake { fake ->
            for (route in GuestApi.ROUTES) {
                val path = concrete(route.path)
                val reply = send(fake, route.method, path, requestBodyFor(route))
                assertEquals("${route.method.wire} $path: ${reply.text}", route.success, reply.status)
                assertTrue(
                    "${route.method.wire} $path answered ${reply.contentType} and the contract says " +
                        "${route.mediaType}",
                    reply.contentType.startsWith(route.mediaType.substringBefore(';')),
                )
                if (route.streamed) {
                    val frames = readFrames(reply)
                    assertTrue(
                        "the stream must end with the one word a reader of this grammar looks for",
                        frames.done,
                    )
                    assertEquals("an answer with nothing in it carries no payload", 0, frames.payloads.size)
                } else if (route.success == 204) {
                    assertEquals("a 204 carries no body", "", reply.text)
                } else {
                    val answer = Json.parse(reply.text)
                    for (field in route.response.fields) {
                        assertType("${route.method.wire} $path", field, answer)
                    }
                }
            }
        }
    }

    @Test
    fun theAnswerArrivesAsItIsWrittenAndEndsWithTheOneWordThatEndsAStream() {
        val script = listOf(
            GuestApiFake.Frame(
                "open",
                mapOf("conversation" to "photos", "hostPath" to "/mnt/omp/photos", "turn" to "turn-1"),
            ),
            GuestApiFake.Frame("text", mapOf("t" to "text", "text" to "one ")),
            GuestApiFake.Frame("text", mapOf("t" to "text", "text" to "line at a time")),
            GuestApiFake.Frame(
                "turn",
                mapOf(
                    "conversation" to "photos",
                    "turn" to "turn-1",
                    "message" to GuestApi.TURN_MESSAGES[0],
                ),
            ),
        )
        withFake(GuestApiFake(script)) { fake ->
            val reply = send(
                fake,
                Method.POST,
                concrete("/api/conversations/{name}/message"),
                """{"text":"what is in here?"}""",
            )
            assertEquals(200, reply.status)
            assertTrue(reply.contentType, reply.contentType.startsWith(GuestApi.EVENT_STREAM))

            // Read it as it arrives: every event is timestamped as the reader takes it, and the
            // spread between the first and the last is the proof that this was a stream and not a
            // body that arrived in one lump when the answer was already finished.
            val reader = Sse()
            val names = ArrayList<String>()
            val seen = ArrayList<Pair<Long, String>>()
            BufferedReader(InputStreamReader(reply.stream!!, Charsets.UTF_8)).forEachLine { line ->
                if (line.startsWith("event: ")) names.add(line.removePrefix("event: "))
                for (payload in reader.feed(line)) seen.add(System.nanoTime() to payload)
            }
            for (payload in reader.finish()) seen.add(System.nanoTime() to payload)

            assertEquals("every event carries its name in the event line", script.map { it.name }, names)
            assertTrue("the stream must be finished by the time the body ends", reader.done)
            assertEquals("the payloads are read with this project's own SSE grammar", 4, seen.size)
            val spread = (seen.last().first - seen.first().first) / 1_000_000
            assertTrue(
                "the whole answer arrived within ${spread}ms, so this is not a stream: the fake " +
                    "wrote ${script.size} events ${GuestApiFake.GAP_MILLIS}ms apart",
                spread >= (script.size - 1) * GuestApiFake.GAP_MILLIS / 2,
            )
            for ((index, frame) in script.withIndex()) {
                val payload = Json.parse(seen[index].second)
                assertEquals("a '${frame.name}' event names itself in the payload", frame.name, payload.str("t"))
                for (field in GuestApi.EVENTS.first { it.name == frame.name }.fields) {
                    assertNotNull(
                        "a '${frame.name}' payload carries no '${field.name}'",
                        payload.field(field.name),
                    )
                }
            }
        }
    }

    @Test
    fun everyRefusalTheContractNamesIsServableInItsOwnWords() {
        val refusals = GuestApi.ROUTES.flatMap { route -> route.refusals.map { route to it } }
        assertTrue("the contract names no refusals at all", refusals.isNotEmpty())
        for ((route, refusal) in refusals) {
            val fake = GuestApiFake(refuse = mapOf((route.method to route.path) to refusal.status))
            fake.start()
            fake.use {
                val reply = send(it, route.method, concrete(route.path), requestBodyFor(route))
                assertEquals("${route.path} ${refusal.status}", refusal.status, reply.status)
                assertEquals(
                    "${route.method.wire} ${route.path} answered ${refusal.status} with a " +
                        "different sentence",
                    refusal.fill(GuestApiFake.PLACEHOLDERS),
                    Json.parse(reply.text).str("error"),
                )
            }
        }
    }

    @Test
    fun aUrlTheContractDoesNotNameIsNotTheContract() {
        withFake { fake ->
            val reply = send(fake, Method.GET, "/api/nothing-here")
            assertEquals(404, reply.status)
            assertEquals(GuestApiFake.NOT_THE_CONTRACT, reply.text)
        }
    }

    // ---- the fake, and the reading of it -------------------------------------------------------

    private class Reply(
        val status: Int,
        val contentType: String,
        val text: String,
        val stream: InputStream?,
    )

    private class Frames(val payloads: List<String>, val done: Boolean)

    private fun withFake(fake: GuestApiFake = GuestApiFake(), body: (GuestApiFake) -> Unit) {
        fake.start()
        fake.use { body(it) }
    }

    private fun send(
        fake: GuestApiFake,
        method: Method,
        path: String,
        body: String? = null,
    ): Reply {
        val connection = URL(fake.baseUrl() + path).openConnection() as HttpURLConnection
        connection.requestMethod = method.wire
        connection.connectTimeout = 5_000
        connection.readTimeout = 20_000
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val type = connection.getHeaderField("Content-Type") ?: ""
        // A 4xx carries its body on the error stream, and a fake that answered a refusal with an
        // empty body would be testing nothing.
        val stream = (if (status < 400) connection.inputStream else connection.errorStream)
            ?: ByteArray(0).inputStream()
        if (streamed(path) && status == 200) {
            return Reply(status, type, "", stream)
        }
        return Reply(status, type, stream.bufferedReader(Charsets.UTF_8).use { it.readText() }, null)
    }

    private fun readFrames(reply: Reply): Frames {
        val reader = Sse()
        val payloads = ArrayList<String>()
        BufferedReader(InputStreamReader(reply.stream!!, Charsets.UTF_8)).forEachLine { line ->
            payloads.addAll(reader.feed(line))
        }
        payloads.addAll(reader.finish())
        return Frames(payloads, reader.done)
    }

    /** Whether [path] is the streamed route with its placeholders filled in. */
    private fun streamed(path: String): Boolean =
        GuestApi.ROUTES.any { it.streamed && concrete(it.path) == path }

    /** A path with no placeholders in it, which is what a socket can be asked for. */
    private fun concrete(shape: String): String =
        shape.replace("{name}", "photos").replace("{id}", "turn-1")

    private fun requestBodyFor(route: Route): String? = when {
        route.path == "/api/conversations" -> """{"name":"photos"}"""
        route.streamed -> """{"text":"what is in here?"}"""
        route.request?.field("allow") != null -> """{"allow":true}"""
        else -> null
    }

    private fun assertType(where: String, field: Field, answer: Json) {
        val value = answer.field(field.name)
        assertNotNull("$where does not answer with '${field.name}'", value)
        when (field.type) {
            JType.STRING -> assertTrue("$where.${field.name} is not a string", value is Json.Str)
            JType.BOOLEAN -> assertTrue("$where.${field.name} is not a boolean", value is Json.Bool)
            JType.INTEGER -> assertTrue("$where.${field.name} is not a number", value is Json.Num)
            JType.ARRAY -> assertTrue("$where.${field.name} is not an array", value is Json.Arr)
            JType.OBJECT -> assertTrue("$where.${field.name} is not an object", value is Json.Obj)
            JType.OBJECT_ARRAY -> {
                assertTrue("$where.${field.name} is not an array", value is Json.Arr)
                val one = (value as Json.Arr).items.firstOrNull()
                assertNotNull("$where.${field.name} is empty, so its element shape is untested", one)
                for (inner in field.element?.fields.orEmpty()) {
                    assertType("$where.${field.name}[]", inner, one as Json)
                }
            }
        }
    }

    // ---- reading the page ---------------------------------------------------------------------

    /**
     * One URL the page asks for.
     *
     * @param shape the URL as the page spells it, with a `{}` where a parameter goes. It is a
     *   `var` because the second and third literals of a concatenation are joined into it as they
     *   are read.
     */
    private class Call(val method: Method, val path: String, val where: String, shape: String) {
        var shape: String = shape
    }

    /**
     * Every `/api/…` URL the page asks for, with the method it asks with.
     *
     * **The parser reads every string literal, not only the ones that begin `/api/`,** because a
     * URL is built out of several of them: `"/api/conversations/" + encodeURIComponent(name) +
     * "/transcript"` is one URL with a parameter in the middle of it, and the second and third
     * literals start with a slash and nothing else. A path starts at a literal beginning `/api/`
     * and is joined to the next literal only when the text between them is nothing but a `+` and
     * possibly one `encodeURIComponent(…)` — which is what stops a `+` inside an expression
     * between two calls from merging them, and what stops `"same-origin"` from being read as a URL.
     */
    private fun pageCalls(): List<Call> {
        val literals = ArrayList<Triple<String, Pair<String, Int>?, IntRange>>()
        for (match in LITERAL.findAll(page)) {
            literals.add(Triple(match.groupValues[1], enclosingCall(match.range.first), match.range))
        }
        val calls = ArrayList<Call>()
        var current: Call? = null
        var previousEnd = 0
        var previousCall: Pair<String, Int>? = null
        for ((text, call, range) in literals) {
            // The gap runs from the end of the last literal to the **start** of this one. Using
            // this literal's end would put the whole of it in the gap, and nothing would ever join.
            val gap = page.substring(previousEnd, range.first)
            if (text.startsWith("/api/") && call != null) {
                val (name, open) = call
                val method = when {
                    name == "stream" -> Method.POST
                    page.substring(open, callEnd(open)).contains("""method: "POST"""") -> Method.POST
                    else -> Method.GET
                }
                val line = page.take(range.first).count { it == '\n' } + 1
                current = Call(method, text, "$name() at app.js:$line", text)
                // A parameter at the *end* of a URL, with nothing after it:
                // `api("/api/approvals/" + encodeURIComponent(id), …)`. There is no second literal
                // to join, so the call's own text is what says the segment is a name.
                val rest = page.substring(range.last + 1, callEnd(open))
                if (TRAILING_PARAMETER.containsMatchIn(rest)) current.shape += "{}"
                calls.add(current)
            } else if (current != null && call != null && call == previousCall &&
                gap.isNotEmpty() && GAP.matches(gap)
            ) {
                // A `+ encodeURIComponent(…) +` is where a parameter goes, and `{}` is how the
                // contract's own placeholder is spelled once the names are flattened.
                current.shape += (if (gap.contains("encodeURIComponent")) "{}" else "") + text
            }
            previousEnd = range.last + 1
            previousCall = call
        }
        return calls
    }

    /**
     * The name of the `api(` or `stream(` call at [at] and the index just after its `(` — or null
     * when the literal is inside neither, which is most of them and is not an error.
     */
    private fun enclosingCall(at: Int): Pair<String, Int>? {
        val api = page.lastIndexOf("api(", at)
        val stream = page.lastIndexOf("stream(", at)
        return when {
            // The index of the `(` itself, which is what [callEnd] and [matching] want.
            stream > api -> "stream" to stream + "stream".length
            api >= 0 -> "api" to api + "api".length
            else -> null
        }
    }

    /** The index of the parenthesis that closes the call whose arguments start at [from]. */
    private fun callEnd(from: Int): Int = matching(from, '(', ')')

    /** The index of the brace that closes the block whose `{` is the first one at or after [from]. */
    private fun bodyEnd(from: Int): Int = matching(page.indexOf('{', from), '{', '}')

    /** The index of the closer that matches the opener at [open], ignoring braces inside strings. */
    private fun matching(open: Int, opener: Char, closer: Char): Int {
        if (open < 0 || page.getOrNull(open) != opener) {
            error("no '$opener' at $open in the page; the parser is out of date")
        }
        var depth = 0
        var inString = false
        for (i in open until page.length) {
            val c = page[i]
            when {
                inString -> if (c == '"' && page[i - 1] != '\\') inString = false
                c == '"' -> inString = true
                c == opener -> depth++
                c == closer -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        error("the '$opener' at $open is never closed")
    }

    /** The event names the page's own `onEvent` dispatches on. */
    private fun pageEvents(): Set<String> = EVENT_ARM.findAll(page).map { it.groupValues[1] }.toSet()

    /**
     * The payload fields the page reads out of each event, per arm of `onEvent`.
     *
     * **An arm ends where `onEvent`'s own body ends**, not where the next arm starts: `onEvent` is
     * not the last function in the file, and an arm that ran to the end of the page would be
     * credited with every `data.` in every function after it. An arm that hands `data` to a
     * function of its own — the approval dialog is the one that does — is followed into it, because
     * the fields it reads are fields of the same event.
     */
    private fun pageEventFields(): Map<String, List<String>> {
        val onEvent = page.indexOf("function onEvent(name, data)")
        if (onEvent < 0) error("the page has no onEvent; the parser is out of date")
        val end = bodyEnd(onEvent)
        val arms = EVENT_ARM.findAll(page.substring(onEvent, end)).toList()
        val out = LinkedHashMap<String, List<String>>()
        for (index in arms.indices) {
            val name = arms[index].groupValues[1]
            val from = onEvent + arms[index].range.last
            val to = arms.getOrNull(index + 1)?.let { onEvent + it.range.first } ?: end
            val arm = page.substring(from, to)
            val fields = LinkedHashSet<String>()
            DATA_FIELD.findAll(arm).forEach { fields.add(it.groupValues[1]) }
            for (call in DATA_CALL.findAll(arm)) {
                val function = page.indexOf("function ${call.groupValues[1]}(data)")
                if (function >= 0) {
                    DATA_FIELD.findAll(page.substring(function, bodyEnd(function)))
                        .forEach { fields.add(it.groupValues[1]) }
                }
            }
            out[name] = fields.toList()
        }
        return out
    }

    // ---- the files, read off this disk ---------------------------------------------------------

    private val page: String by lazy { readRepoFile("app/src/main/assets/web/app.js") }

    private val chatApi: String by lazy {
        readRepoFile("app/src/main/java/com/omp/terminal/web/ChatApi.kt")
    }

    private fun guestAsset(name: String): String = readRepoFile("app/src/main/assets/guest/$name")

    /**
     * A file of this repository, found by walking up from wherever the test was started.
     *
     * **The real file and not a fixture of it.** The claim under test is that the guest serves what
     * the page asks for, and a copy in a test would pass while the two drifted apart. A repository
     * without these files has nothing to say, and the test fails rather than passing against a
     * stand-in.
     */
    private fun readRepoFile(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val file = File(dir, relative)
            if (file.isFile) return file.readText()
            dir = dir.parentFile
        }
        throw AssertionError(
            "$relative was not found above ${File(".").absolutePath}: the test that says the guest " +
                "serves what the page asks for cannot be run without it",
        )
    }

    private companion object {
        /** Every double-quoted literal in the page: a URL is built out of several of them. */
        val LITERAL = Regex(""""((?:[^"\\]|\\.)*)"""")

        /**
         * A `+` and, at most, one `encodeURIComponent(…)`: nothing else joins two literals.
         *
         * The trailing `\s*` is not decoration — the text between two literals runs up to the
         * *next* literal's opening quote, so the gap ends with the space after its last `+`.
         */
        val GAP = Regex("""^\s*\+\s*(encodeURIComponent\([^()]*\)\s*)?\+\s*$""")

        /**
         * A `+ encodeURIComponent(…)` and then a comma or a closing parenthesis: a parameter at the
         * **end** of a URL, with no `+ "/…"` after it. The `[,)]` is what keeps it from matching the
         * same text in `"/api/conversations/" + encodeURIComponent(name) + "/transcript"`, where the
         * parameter is in the middle and the segment after it arrives as a literal of its own.
         */
        val TRAILING_PARAMETER = Regex("""^\s*\+\s*encodeURIComponent\([^()]*\)\s*[,)]""")

        val EVENT_ARM = Regex("""name === "(\w+)"""")

        val DATA_FIELD = Regex("""\bdata\.(\w+)""")

        val DATA_CALL = Regex("""\b(\w+)\(data\)""")

        val ERROR_LITERAL = Regex("""error\("((?:[^"\\]|\\.)*)"\)""")

        val VARIABLE_FIELD = Regex("""(?<![.\w])(state|one|transcript|made|got)\.(\w+)""")

        val DOLLAR = Regex("""\$(\w+)""")

        /** A contract path with every placeholder flattened, which is how the page spells it. */
        fun shape(path: String): String = path.replace(Regex("""\{\w+}"""), "{}")
    }
}
