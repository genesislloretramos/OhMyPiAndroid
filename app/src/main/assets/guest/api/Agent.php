<?php
/*
 * omp guest API — one turn of the real agent, streamed. NOT RUN: this build has no PHP and no
 * MySQL, so this file has never been executed. Every flag below was read off `omp --help` on the
 * machine that built this, and the vector is held to omp/vm/guestapi/AgentCommand.kt, which
 * GuestApiContractTest checks against this file and against that help text.
 */

declare(strict_types=1);

namespace omp\guest;

/**
 * One question, one child process, and the answer as it arrives.
 *
 * ### The vector
 *
 * ```
 * /usr/local/bin/omp -p --mode=json --approval-mode write \
 *     --cwd <conversation> --session-dir <conversation>/.omp/agent [--continue] [--model <model>]
 * ```
 *
 * with **the question written to the child's standard input, which is then closed.** Every flag is
 * justified in [AgentCommand] against the help text it was taken from; the three that matter most:
 *
 * - `-p, --print` — "Non-interactive mode: process prompt and exit". One prompt, then exit.
 * - `--mode=json` — the only mode measured to *stream*. With the default text mode, 200 lines of
 *   answer arrived in one burst at the end; with this, the same answer arrived a chunk at a time.
 * - `--approval-mode write` — asks when a tool would change the filesystem and nothing else. With
 *   standard input at end of input, a question is a decline, so the guest's agent reads and never
 *   writes. The other end of the same flag (`yolo`) was measured to write with nobody asked, and
 *   that is the one behaviour this project's README makes a point of not having.
 *
 * ### Why the question goes in on standard input
 *
 * The positional argument is the documented way, and the help says of it: "Messages to send (prefix
 * files with @)". **A question beginning with `@` is therefore a file for the model to include**, and
 * a chat box must not be a way to name a file. On standard input `@` is a character.
 *
 * **The pipe is closed, and that is not a detail.** With standard input an open pipe and no data,
 * `omp` prints "Reading prompt from piped stdin (waiting for EOF; Ctrl+C to abort)…" and waits for
 * ever — measured, twice. So the question goes in and the pipe goes straight to EOF.
 *
 * ### The key never comes near this file's output
 *
 * The child **inherits the guest's own environment** and reads `OPENAI_API_KEY` and its siblings
 * from it, the way it does on a terminal. This file never reads a key, never passes one, and never
 * puts one in an environment array of its own: there is nothing here to leak, because there is
 * nothing here. `hasKey()` below asks whether a variable is *set* and is the only thing that touches
 * one, and what the page is told is a boolean.
 */
final class Agent
{
    /** The agent inside the Debian, which is the bind `omp.vm.provision.ProotCommand` makes. */
    public const BINARY = '/usr/local/bin/omp';

    /** The flags every turn runs with. `--cwd` and `--session-dir` take their values from [run]. */
    public const FLAGS = ['-p', '--mode=json', '--approval-mode', 'write'];

    /** Added when that conversation's session directory already holds a session. */
    public const CONTINUE = '--continue';

    /** Added with its value when the guest's environment names a model. */
    public const MODEL = '--model';

    /** Where a conversation's session, configuration and caches live: its own `.omp/agent/`. */
    public const SESSION_SUBDIR = '.omp/agent';

    /** The file name a session is stored under, in the shape `omp` writes it. */
    public const SESSION_SUFFIX = '.jsonl';

    /**
     * The environment variables a key can arrive in, and the provider each one names.
     *
     * **The list is the help's own**, which is the only list that can be defended: it is what
     * `omp --help` prints under "Environment Variables", not what a tutorial says this project
     * should support. A provider not in here is a conversation this guest calls unconfigured, which
     * is a sentence the page already knows how to show.
     */
    public const KEY_VARIABLES = [
        'OPENAI_API_KEY' => 'openai',
        'ANTHROPIC_API_KEY' => 'anthropic',
        'GEMINI_API_KEY' => 'gemini',
        'OPENROUTER_API_KEY' => 'openrouter',
        'GROQ_API_KEY' => 'groq',
        'DEEPSEEK_API_KEY' => 'deepseek',
        'MISTRAL_API_KEY' => 'mistral',
        'XAI_API_KEY' => 'xai',
    ];

    /** How long a turn may run before the guest stops watching. `omp --max-time` is the real one. */
    private const WATCH_MILLIS = 250;

    /** How long one turn may run. Long, because a model is slow, and finite because Apache waits. */
    public const TIMEOUT_SECONDS = 900;

    /** The whole vector for one turn, as an argv — no shell, so nothing in it is ever word-split. */
    public static function argv(string $conversation, bool $continuing, ?string $model): array
    {
        $argv = self::FLAGS;
        $argv[] = '--cwd';
        $argv[] = $conversation;
        $argv[] = '--session-dir';
        $argv[] = self::sessionDir($conversation);
        if ($continuing) {
            $argv[] = self::CONTINUE;
        }
        if ($model !== null && $model !== '') {
            $argv[] = self::MODEL;
            $argv[] = $model;
        }
        return array_merge([self::BINARY], $argv);
    }

    /** Where a conversation's session, configuration and caches live. */
    public static function sessionDir(string $conversation): string
    {
        return $conversation . '/' . self::SESSION_SUBDIR;
    }

    /** Whether a conversation already holds a session to continue. */
    public static function hasSession(string $conversation): bool
    {
        $names = @scandir(self::sessionDir($conversation));
        if ($names === false) {
            return false;
        }
        foreach ($names as $name) {
            if (str_ends_with($name, self::SESSION_SUFFIX)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a key for some provider is in the guest's own environment.
     *
     * **A question about a variable being set, never about its value.** `getenv` with no second
     * argument returns the value, and the value is never assigned, compared or logged: this returns
     * as soon as it knows a name is present. The page is told a boolean, exactly as the terminal's
     * own `omp key --show` reports a file and a byte count and not a byte of the key.
     */
    public static function hasKey(): bool
    {
        foreach (array_keys(self::KEY_VARIABLES) as $name) {
            if (getenv($name) !== false) {
                return true;
            }
        }
        return false;
    }

    /** The provider whose key is present, or an empty string. */
    public static function provider(): string
    {
        foreach (self::KEY_VARIABLES as $name => $provider) {
            if (getenv($name) !== false) {
                return $provider;
            }
        }
        return '';
    }

    /** The model the guest's environment names, or an empty string for the agent's own default. */
    public static function model(): string
    {
        $model = getenv('OMP_GUEST_MODEL');
        return is_string($model) ? $model : '';
    }

    /**
     * What `GET /api/state` reports as the version answering: the agent's own.
     *
     * **Run with `proc_open` and not with a shell.** A shell would mean a command line, a `PATH` and
     * an argument this file has to escape correctly, for a version string that one pipe and no
     * shell can produce. The child inherits the guest's own environment, so no key is passed here
     * either — and if the binary is not there the answer is an empty string, which the page draws
     * as an empty version rather than as a failure to read one.
     */
    public static function version(): string
    {
        $pipes = [];
        $process = @proc_open([self::BINARY, '--version'], [
            0 => ['file', '/dev/null', 'r'],
            1 => ['pipe', 'w'],
            2 => ['pipe', 'w'],
        ], $pipes, null);
        if (!is_resource($process)) {
            return '';
        }
        $out = (string) stream_get_contents($pipes[1]);
        foreach ($pipes as $pipe) {
            if (is_resource($pipe)) {
                fclose($pipe);
            }
        }
        proc_close($process);
        // The first line and no more: it goes into a JSON field a page draws, and a multi-line
        // value would be a page with a line break in the middle of it.
        $first = strtok(trim($out), "\n");
        return $first === false ? '' : $first;
    }

    /**
     * Ask one question and hand every chunk to `$onText` as it arrives.
     *
     * @param string   $conversation the conversation folder, already resolved inside the container.
     * @param string   $question     what the user typed, written to the child's standard input.
     * @param string   $turn         this turn's id, so a cancel can be seen while it streams.
     * @param callable $onText       called with each `text_delta`, in order, as it arrives.
     * @param callable $onNote       called with each line of stderr, and with a tool's name.
     * @return array{status:int, answered:bool, text:string} the exit status, whether anything was
     *         said, and the whole answer — which the caller stores once, and which is **not** what
     *         the page draws: the page has been drawing the deltas since the first one.
     */
    public static function ask(
        string $conversation,
        string $question,
        string $turn,
        callable $onText,
        callable $onNote,
    ): array {
        $argv = self::argv($conversation, self::hasSession($conversation), self::model() ?: null);
        $pipes = [];
        // No `env` key: the child inherits the guest's own environment, which is where the key is
        // and is the only way a key reaches the agent without this file ever holding one.
        $process = @proc_open($argv, [
            0 => ['pipe', 'r'],
            1 => ['pipe', 'w'],
            2 => ['pipe', 'w'],
        ], $pipes, $conversation);
        if (!is_resource($process)) {
            throw new Refusal(503, 'the guest could not start ' . self::BINARY . ': no such program in the Debian');
        }
        stream_set_blocking($pipes[1], false);
        stream_set_blocking($pipes[2], false);

        // The question, and then the end of it. Closed rather than flushed: an open pipe here is the
        // "Reading prompt from piped stdin (waiting for EOF)" case, measured twice.
        fwrite($pipes[0], $question);
        fclose($pipes[0]);

        $answer = '';
        $answered = false;
        $held = '';
        $errors = '';
        $started = microtime(true);
        $lastWatch = 0.0;

        while (true) {
            $read = [];
            if (is_resource($pipes[1])) {
                $read[] = $pipes[1];
            }
            if (is_resource($pipes[2])) {
                $read[] = $pipes[2];
            }
            $write = null;
            $except = null;
            $ready = $read === [] ? false : stream_select($read, $write, $except, 0, 200000);
            $now = microtime(true);

            foreach ($read as $stream) {
                $chunk = fread($stream, 65536);
                if ($chunk === false || $chunk === '') {
                    if (feof($stream)) {
                        if ($stream === $pipes[1]) {
                            fclose($pipes[1]);
                        } else {
                            fclose($pipes[2]);
                        }
                    }
                    continue;
                }
                if ($stream === $pipes[1]) {
                    $held .= $chunk;
                    // One JSON object per line, so a line is the unit: a chunk that ends in the
                    // middle of a token is held until its newline arrives, and nothing is decoded
                    // out of half a line.
                    while (($at = strpos($held, "\n")) !== false) {
                        $line = trim(substr($held, 0, $at));
                        $held = substr($held, $at + 1);
                        if ($line === '') {
                            continue;
                        }
                        $event = json_decode($line, true);
                        if (!is_array($event)) {
                            $onNote($line);
                            continue;
                        }
                        if (self::isToolStart($event)) {
                            $onNote('omp: ' . self::toolName($event));
                            continue;
                        }
                        $delta = self::textDelta($event);
                        if ($delta !== null) {
                            $answered = true;
                            $answer .= $delta;
                            // Out to the browser now, before the next line is read. This is the
                            // whole of the streaming requirement.
                            $onText($delta);
                        }
                    }
                } else {
                    $errors .= $chunk;
                    $lines = preg_split('/\r\n|\r|\n/', $errors);
                    $errors = (string) array_pop($lines);
                    foreach ($lines as $line) {
                        if (trim($line) !== '') {
                            $onNote($line);
                        }
                    }
                }
            }

            if ($read === [] || $ready === false) {
                // A quarter of a second is the checkpoint, and it is where the Stop button is
                // seen: the same arithmetic the app's own watchdog uses, and for the same reason —
                // nothing can interrupt a read already parked inside a socket.
                if ($now - $lastWatch >= self::WATCH_MILLIS / 1000) {
                    $lastWatch = $now;
                    if (Db::isCancelled($turn)) {
                        proc_terminate($process, 15);
                        break;
                    }
                }
            }
            if ($now - $started > self::TIMEOUT_SECONDS) {
                proc_terminate($process, 9);
                $onNote('omp: the guest stopped waiting for this answer');
                break;
            }
        }

        foreach ($pipes as $pipe) {
            if (is_resource($pipe)) {
                fclose($pipe);
            }
        }
        $status = proc_close($process);
        if (trim($errors) !== '') {
            $onNote(trim($errors));
        }
        return ['status' => $status, 'answered' => $answered, 'text' => $answer];
    }

    /** Whether this event is a tool the agent is about to run. */
    private static function isToolStart(array $event): bool
    {
        return ($event['type'] ?? null) === 'tool_execution_start';
    }

    /**
     * The tool's name, as the event carries it.
     *
     * **`toolName` is the field this build saw, and `name` is the one it did not rule out.** Both
     * are read, and a tool whose name is in neither is announced as an unnamed tool rather than as
     * nothing at all — a `note` saying "a tool" is a sentence a page can show, and silence is not.
     */
    private static function toolName(array $event): string
    {
        $name = $event['toolName'] ?? $event['name'] ?? $event['tool'] ?? '';
        return is_string($name) && $name !== '' ? $name : 'a tool';
    }

    /**
     * The model's words inside this event, or null when it carries none.
     *
     * **Only `text_delta`, and not `text_end`.** `text_end` carries the whole text again, and
     * sending it would double every answer on the page. `thinking_delta` is dropped for the same
     * reason the default text mode does not print thoughts: the page draws an answer, and a
     * reasoning trace is not one.
     */
    private static function textDelta(array $event): ?string
    {
        if (($event['type'] ?? null) !== 'message_update') {
            return null;
        }
        $inner = $event['assistantMessageEvent'] ?? null;
        if (!is_array($inner) || ($inner['type'] ?? null) !== 'text_delta') {
            return null;
        }
        $delta = $inner['delta'] ?? null;
        return is_string($delta) ? $delta : null;
    }
}
