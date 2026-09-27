<?php
/*
 * omp guest API — the route table. NOT RUN: this build has no PHP and no MySQL, so this file has
 * never been executed. It is the PHP half of omp/vm/guestapi/GuestApi.kt, and
 * omp/vm/guestapi/GuestApiContractTest is what checks the two against app.js and against each
 * other: the table here is hand-written because PHP has no reflection to build a route from a
 * comment, and the cost of that is a test that fails the day the two disagree.
 */

declare(strict_types=1);

namespace omp\guest;

/**
 * Every route, and the one place a request is turned into an answer.
 *
 * ### The matching, and why it is a switch and not a table of regular expressions
 *
 * Six routes, and a name in a path is matched **as one path segment and nothing else**. There is
 * no `[^/]+` to be greedy with, no `..` to normalise away and no capture group to interpolate into
 * anything: a pattern is a literal with `{name}` where a segment goes, and a path that does not fit
 * one of them is a 404 that never reaches a handler. `Paths::conversation()` then does the real
 * refusal work, and it does it in one place.
 */
final class Routes
{
    /** The most entries one transcript answer carries, which is the app's own limit. */
    public const SHOWN_ENTRIES = 500;

    /** The turn ids this guest hands out. `turn-1`, `turn-2`, … and nothing a URL could collide with. */
    private static int $turns = 0;

    /**
     * The four sentences a turn can end as, in the app's own words.
     *
     * **Not a fifth.** The page shows this string as it is, so a sentence only this guest ever says
     * is a sentence a user has never seen anywhere else in this app.
     */
    public const TURN_MESSAGES = [
        'the model finished, and the answer is in the transcript',
        'stopped; what had arrived is in the transcript',
        'the endpoint or the key was refused; the transcript is unchanged',
        'this conversation has no provider, model and endpoint set',
    ];

    /** The one entry point, and the only place a `Refusal` is turned into bytes. */
    public static function dispatch(string $method, string $path): void
    {
        try {
            self::route($method, $path);
        } catch (Refusal $refusal) {
            Reply::error($refusal->status, $refusal->getMessage());
        }
    }

    private static function route(string $method, string $path): void
    {
        $parts = array_values(array_filter(explode('/', $path), static fn ($p) => $p !== ''));

        // /api/state
        if ($method === 'GET' && count($parts) === 2 && $parts[0] === 'api' && $parts[1] === 'state') {
            self::state();
            return;
        }
        // /api/conversations
        if ($method === 'POST' && count($parts) === 2 && $parts[0] === 'api' && $parts[1] === 'conversations') {
            self::create();
            return;
        }
        // /api/conversations/{name}/transcript
        if ($method === 'GET' && count($parts) === 4 && $parts[0] === 'api' &&
            $parts[1] === 'conversations' && $parts[3] === 'transcript') {
            self::transcript(urldecode($parts[2]));
            return;
        }
        // /api/conversations/{name}/message
        if ($method === 'POST' && count($parts) === 4 && $parts[0] === 'api' &&
            $parts[1] === 'conversations' && $parts[3] === 'message') {
            self::message(urldecode($parts[2]));
            return;
        }
        // /api/approvals/{id}
        if ($method === 'POST' && count($parts) === 3 && $parts[0] === 'api' &&
            $parts[1] === 'approvals') {
            self::approve(urldecode($parts[2]));
            return;
        }
        // /api/turns/{id}/cancel
        if ($method === 'POST' && count($parts) === 4 && $parts[0] === 'api' &&
            $parts[1] === 'turns' && $parts[3] === 'cancel') {
            self::cancel(urldecode($parts[2]));
            return;
        }
        // Anything else. The static pages are served by Apache as files and never arrive here,
        // because FallbackResource only applies to a path that names no file.
        Reply::error(404, 'no such route: ' . $method . ' /' . implode('/', $parts));
    }

    // ---- GET /api/state -------------------------------------------------------------------------

    /**
     * What there is, and where it is on this phone.
     *
     * **The container's real path is in here**, because a user of a headless host asks where the
     * conversations are first, and the answer is a path they can paste into a file manager.
     *
     * `configured` and `keyStored` are both answered from the guest's own environment and are both
     * booleans: a key is never read, never counted and never described. `title` is the first
     * question of a conversation when there is one, because a folder of `photos` tells nobody what
     * it became.
     */
    private static function state(): void
    {
        $root = Paths::container();
        $configured = Agent::hasKey();
        $provider = Agent::provider();
        $model = Agent::model();
        $conversations = [];
        foreach (Paths::conversations() as $name => $path) {
            $row = Db::conversation($name);
            $conversations[] = [
                'name' => $name,
                'title' => $row === null || $row['title'] === '' ? $name : (string) $row['title'],
                'modified' => $row === null ? (int) (@filemtime($path) ?: 0) : (int) $row['modified'],
                'hostPath' => Paths::hostPath($path),
                'configured' => $configured,
                'keyStored' => $configured,
                'provider' => $configured ? $provider : '',
                'model' => $model,
            ];
        }
        Reply::json(200, [
            'version' => Agent::version(),
            'hostRoot' => Paths::hostRoot(),
            'conversations' => $conversations,
        ]);
    }

    // ---- POST /api/conversations ----------------------------------------------------------------

    /**
     * A folder, made the way `omp new` makes it.
     *
     * **The same sanitising, the same `-2`, and the same generated name**, because the app's own
     * `Workspace.create` is what a user's other `omp new` calls, and two rules for one name is two
     * answers to the question "what will this folder be called".
     */
    private static function create(): void
    {
        $root = Paths::container();
        $body = self::body();
        $asked = isset($body['name']) && is_string($body['name']) ? trim($body['name']) : '';
        $base = $asked === ''
            ? Paths::generated(Db::now())
            : (Paths::sanitise($asked) ?? Paths::generated(Db::now()));

        $candidate = $base;
        $suffix = 1;
        while ($suffix <= 999 && file_exists(Paths::child($root, $candidate))) {
            $suffix++;
            // The suffix is paid for out of the name's own budget, so a long name is shortened
            // rather than allowed to become a path component a filesystem refuses.
            $candidate = Paths::fit($base, Paths::MAX_NAME - strlen("-$suffix")) . "-$suffix";
        }
        if ($suffix > 999) {
            throw new Refusal(409, Paths::errno(Paths::child($root, $base), 'exists'));
        }
        $path = Paths::child($root, $candidate);
        if (!@mkdir($path, 0775) && !is_dir($path)) {
            throw new Refusal(409, Paths::errno($path, 'denied'));
        }
        Db::touch($candidate, Db::now());
        Reply::json(201, [
            'name' => Paths::onDiskName($path),
            'path' => $path,
            'hostPath' => Paths::hostPath($path),
        ]);
    }

    // ---- GET /api/conversations/{name}/transcript -----------------------------------------------

    /**
     * One conversation as it is recorded.
     *
     * **The most recent [self::SHOWN_ENTRIES] rows and no more, with the real total beside them.**
     * `skipped` is always empty here, and it is in the answer anyway: a row in this guest's own
     * database is never half written, so there is no truncated line to report — and a field the
     * page reads must exist even when it is empty, or the page's own loop throws.
     */
    private static function transcript(string $name): void
    {
        $path = Paths::conversation($name);
        $row = Db::touch(Paths::onDiskName($path), Db::now());
        $found = Db::messages((int) $row['id'], self::SHOWN_ENTRIES);
        $entries = [];
        foreach ($found['entries'] as $entry) {
            $one = ['role' => (string) $entry['role'], 'content' => (string) $entry['content']];
            if ($entry['tool'] !== null) {
                $one['tool'] = (string) $entry['tool'];
            }
            $entries[] = $one;
        }
        Reply::json(200, [
            'conversation' => Paths::onDiskName($path),
            'hostPath' => Paths::hostPath($path),
            'transcriptPath' => Paths::child($path, '.omp/transcript.jsonl'),
            'total' => $found['total'],
            'shown' => count($entries),
            'skipped' => [],
            'entries' => $entries,
        ]);
    }

    // ---- POST /api/conversations/{name}/message --------------------------------------------------

    /**
     * One question, and the answer as it arrives.
     *
     * **The refusals come before a byte of the stream**, and that is the order the app's own server
     * uses: a 400 or a 409 is a JSON body the page's `stream()` can read, and a stream that opened
     * and then failed leaves the page watching a body that stopped for a reason it cannot see.
     *
     * **One turn per conversation**, and the row in the database is the lock — see
     * [Db::claimTurn]. **The question is recorded once the endpoint has answered**, for the reason
     * the app's own server gives: a question that never reached a model is not part of the history
     * the next one is built from.
     */
    private static function message(string $name): void
    {
        $path = Paths::conversation($name);
        $onDisk = Paths::onDiskName($path);
        $body = self::body();
        $question = isset($body['text']) && is_string($body['text']) ? trim($body['text']) : '';
        if ($question === '') {
            throw new Refusal(400, "a message needs a 'text' field");
        }
        $row = Db::touch($onDisk, Db::now());
        $turn = 'turn-' . (++self::$turns);
        if (!Db::claimTurn($turn, (int) $row['id'], Db::now())) {
            throw new Refusal(
                409,
                "'$onDisk' is already answering a question; wait for it, or cancel it",
            );
        }
        if (!Agent::hasKey()) {
            Db::endTurn($turn);
            // Refused before the stream opens, in the app's own sentence for a conversation with no
            // provider, model and endpoint set.
            throw new Refusal(400, self::TURN_MESSAGES[3]);
        }

        Sse::begin();
        Sse::event('open', [
            'conversation' => $onDisk,
            'hostPath' => Paths::hostPath($path),
            'turn' => $turn,
        ]);

        $answer = '';
        $ended = self::TURN_MESSAGES[0];
        try {
            $outcome = Agent::ask(
                $path,
                $question,
                $turn,
                static function (string $delta) use (&$answer): void {
                    $answer .= $delta;
                    Sse::event('text', ['text' => $delta]);
                },
                // A line the agent wrote about itself, or a tool it is about to run. Forwarded as
                // it is, because this project has one set of words for a refusal and a second set
                // in a web page is a second set to keep true.
                static function (string $line): void {
                    Sse::event(str_starts_with($line, 'omp: ') ? 'note' : 'refusal', ['text' => $line]);
                },
            );
            if (Db::isCancelled($turn) || $outcome['status'] !== 0) {
                $ended = self::TURN_MESSAGES[1];
            }
            if (!$outcome['answered'] && $outcome['status'] !== 0) {
                $ended = self::TURN_MESSAGES[2];
                Sse::event('error', [
                    'text' => 'omp exited with status ' . $outcome['status'] . '; see the guest’s own error log',
                ]);
            }
        } catch (Throwable $e) {
            Db::endTurn($turn);
            error_log('omp guest api: ' . $e->getMessage());
            // A turn that failed is still a turn that ended, and the page has to be told so rather
            // than left watching a stream that stopped for a reason it cannot see.
            Sse::event('error', ['text' => 'the guest could not finish this answer: ' . get_class($e)]);
            Sse::event('turn', ['conversation' => $onDisk, 'turn' => $turn, 'message' => self::TURN_MESSAGES[2]]);
            Sse::end();
            return;
        }

        // The question, then the answer, then the title: the first words of an answer are the best
        // name a conversation has, and the folder is what a person sees in a file manager. Both
        // records are written, because the database is the index the page reads and the folder is
        // the conversation itself — one of them being missing would make the other a copy.
        Db::addMessage((int) $row['id'], 'user', $question);
        self::appendToFolder($path, 'user', $question, null);
        if ($answer !== '') {
            Db::addMessage((int) $row['id'], 'assistant', $answer);
            self::appendToFolder($path, 'assistant', $answer, null);
            if ($row['title'] === '') {
                Db::setTitle($onDisk, self::titleOf($answer));
            }
        }
        Db::endTurn($turn);
        Sse::event('turn', ['conversation' => $onDisk, 'turn' => $turn, 'message' => $ended]);
        Sse::end();
    }

    // ---- POST /api/approvals/{id} ---------------------------------------------------------------

    /**
     * The answer to a write being asked about, and only the answer.
     *
     * **Every id is a 404 here, and that is not a bug that was left in.** The guest's agent is run
     * in print mode with its standard input at end of input, which is the only input a child of a
     * PHP process can be given here — a pipe held open makes `omp` wait before it starts, and a pipe
     * closed makes every question read `-1`. So no question is ever outstanding, and the page's
     * dialog never opens. The sentence is the app's own 404 anyway, because a refusal that says
     * something else on this server is two sentences for one event.
     *
     * The route exists because the page calls it, and it is here that a future host protocol — a
     * `--mode=rpc` one, which this build has established nothing about — would be wired in.
     */
    private static function approve(string $id): void
    {
        throw new Refusal(404, "no write is waiting for an answer under '$id'");
    }

    // ---- POST /api/turns/{id}/cancel -------------------------------------------------------------

    /**
     * Stop an answer that is running.
     *
     * **This sets a flag; it does not kill anything.** The request streaming the answer owns the
     * child process, and `proc_terminate` needs a handle that does not survive into another Apache
     * worker. The streaming loop asks this question four times a second and acts on it, so the
     * flag is issued within a request and acted on within a quarter of a second of that — the same
     * arithmetic as the app's own watchdog, and for the same reason.
     */
    private static function cancel(string $id): void
    {
        if (!Db::cancelTurn($id)) {
            throw new Refusal(404, "no turn '$id' is running");
        }
        Reply::empty(204);
    }

    // ---- small things ---------------------------------------------------------------------------

    /**
     * The request body as an array, or an empty one.
     *
     * **A body that is not a JSON object is an empty object, never a warning.** The page sends
     * `Content-Type: application/json` and a body that parses; anything else is somebody's `curl`,
     * and a warning printed into a response is a page of source on a phone.
     */
    private static function body(): array
    {
        $raw = file_get_contents('php://input');
        if (!is_string($raw) || $raw === '') {
            return [];
        }
        $parsed = json_decode($raw, true);
        return is_array($parsed) ? $parsed : [];
    }

    /**
     * A title for a conversation, from the first words of its first answer.
     *
     * One line, cut on a character boundary and on a space, so a title is something to read in a
     * list rather than a sentence that runs off the side of it.
     */
    private static function titleOf(string $answer): string
    {
        $line = strtok(trim($answer), "\n");
        if ($line === false || trim($line) === '') {
            return '';
        }
        $line = Paths::fit($line, 40);
        $at = strrpos($line, ' ');
        if ($at !== false && $at > 8) {
            $line = substr($line, 0, $at);
        }
        return $line;
    }

    /**
     * One line into the conversation folder's own transcript, in the app's format.
     *
     * **The folder is the conversation, so the file in it is the record** — the same
     * `.omp/transcript.jsonl` the namespace VM and the terminal write, one JSON object per line,
     * appended under a lock. The database is the index the page reads a conversation *through*; this
     * is the copy a person opens in a file manager, and it is what `transcriptPath` names. A
     * `transcriptPath` pointing at a file that is not there would be the one field in this API that
     * lies, and the page prints it in full whenever it shows a truncated list.
     *
     * A failure here is a log line and nothing else: the turn has already been answered and the
     * page has already drawn it, and a file that could not be appended must not un-say it.
     */
    private static function appendToFolder(string $path, string $role, string $content, ?string $tool): void
    {
        $line = json_encode(
            $tool === null
                ? ['role' => $role, 'content' => $content]
                : ['role' => $role, 'content' => $content, 'tool' => $tool],
            JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE,
        );
        if ($line === false) {
            return;
        }
        $file = Paths::child($path, '.omp/transcript.jsonl');
        $directory = dirname($file);
        if (!is_dir($directory) && !@mkdir($directory, 0775, true) && !is_dir($directory)) {
            error_log('omp guest api: could not make ' . $directory);
            return;
        }
        if (@file_put_contents($file, $line . "\n", FILE_APPEND | LOCK_EX) === false) {
            error_log('omp guest api: could not append to ' . $file);
        }
    }
}
