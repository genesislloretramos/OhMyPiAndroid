<?php
/*
 * omp guest API — the stream, written by hand. NOT RUN: this build has no PHP and no MySQL, so this
 * file has never been executed, and nothing has ever been flushed through it.
 */

declare(strict_types=1);

namespace omp\guest;

/**
 * Server-Sent Events, in the grammar `omp.agent.http.Sse` implements on the client side.
 *
 * **The same grammar, deliberately, in both directions.** `data:` lines, a blank line ending an
 * event, and `data: [DONE]` to finish — because that is a format this project's own reader parses,
 * and one a browser reads with `fetch` and a `ReadableStream` and nothing else on the page. Every
 * event carries its name twice: in the `event:` line a browser dispatches on, and as `"t"` in the
 * JSON, because `omp.agent.http.Sse` reads `event:` and drops it and a payload whose name lived
 * only there would be an anonymous string to the one reader in this project with a test on it.
 */
final class Sse
{
    /**
     * Take the response over: no `Content-Length`, no buffering, no compression.
     *
     * **Three things stand between a line of text and a browser here, and all three are turned off
     * deliberately.**
     *
     * - `while (ob_get_level()) ob_end_flush()` — `mod_php` runs inside a buffer by default, and a
     *   buffer is the one thing that turns a stream into a body that arrives all at once when the
     *   answer is already finished. That is exactly what this class exists to not do.
     * - `apache_setenv('no-gzip', '1')` — Debian's Apache compresses `text/event-stream` if
     *   `mod_deflate` is on, and a compressor that holds a few kilobytes back to build a window
     *   will hold the first tokens of an answer until there is a window worth sending.
     * - `header('X-Accel-Buffering: no')` — harmless without nginx, and the one line that makes
     *   this correct if anybody ever puts one in front.
     *
     * `ignore_user_abort(false)` is what PHP already does and is set anyway, because the failure it
     * prevents is the browser's Stop button leaving a child process running with nobody reading
     * its output.
     */
    public static function begin(): void
    {
        while (ob_get_level() > 0) {
            ob_end_flush();
        }
        ignore_user_abort(false);
        if (function_exists('apache_setenv')) {
            apache_setenv('no-gzip', '1');
        }
        header('Content-Type: text/event-stream; charset=utf-8');
        header('Cache-Control: no-cache, no-store');
        header('X-Accel-Buffering: no');
        header('Connection: close');
        self::flush();
    }

    /**
     * One event, and out it goes.
     *
     * **A payload holding a newline is split across two `data:` lines**, which is what the SSE
     * specification says a reader joins back together with a newline. Emitting a raw newline inside
     * one `data:` line would end the event early and leave the reader holding half a JSON document,
     * and the page's own framing code splits on the blank line and would hand the first half to
     * `JSON.parse`.
     */
    public static function event(string $name, array $payload): void
    {
        $payload['t'] = $name;
        $json = json_encode($payload, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
        if ($json === false) {
            // A question is whatever a person typed, and a string that is not valid UTF-8 cannot
            // be encoded. The turn ends with a sentence rather than with a stream that stops for a
            // reason the page cannot see.
            $json = json_encode(['t' => $name, 'text' => 'a line of this answer was not text this could send'], JSON_UNESCAPED_UNICODE);
        }
        $out = 'event: ' . $name . "\n";
        foreach (explode("\n", (string) $json) as $line) {
            $out .= 'data: ' . $line . "\n";
        }
        echo $out . "\n";
        self::flush();
    }

    /**
     * The end of the stream, in the one word a reader of this grammar looks for.
     *
     * **The connection closes after it, and that is load-bearing.** The page deliberately keeps
     * reading past `[DONE]` to the end of the body, because cancelling the reader would abort the
     * request underneath and record a `net::ERR_ABORTED` for a stream that arrived perfectly well.
     * The next read is the one that has to end it, and that is this request returning.
     */
    public static function end(): void
    {
        echo 'data: ' . "[DONE]\n\n";
        self::flush();
    }

    private static function flush(): void
    {
        @flush();
        if (function_exists('litespeed_finish_request')) {
            @litespeed_finish_request();
        }
    }
}
