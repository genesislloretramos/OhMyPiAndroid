<?php
/*
 * omp guest API — every answer that is not a stream. NOT RUN: this build has no PHP and no MySQL, so
 * this file has never been executed. The sentences it sends are the app’s own, from
 * app/src/main/java/com/omp/terminal/web/ChatApi.kt, and
 * omp/vm/guestapi/GuestApiContractTest is what notices when one of them drifts.
 */

declare(strict_types=1);

namespace omp\guest;

/**
 * One HTTP answer, and nothing else.
 *
 * **Every body is `{"error": "…"}` or a JSON object the page already knows how to read**, because
 * that is the whole of what `api()` in app.js does with a failure: parse the body, take `error`,
 * show it. A 500 carrying an HTML page is a user staring at Apache’s error document, and a 500
 * carrying a stack trace is a user staring at this project’s source.
 */
final class Reply
{
    /** Nothing is ever sent twice from one request, and a warning about it is not worth reading. */
    private static bool $sent = false;

    /** A JSON object, with the keys in the order they were given. */
    public static function json(int $status, array $fields): void
    {
        if ($status === 204) {
            self::empty(204);
            return;
        }
        $body = json_encode($fields, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
        // json_encode returns false for a string that is not valid UTF-8, and a conversation is
        // whatever a person typed. A false here would otherwise be an empty body with a 200 on it,
        // which the page reads as "nothing is there" rather than as a failure.
        self::raw($status, $body === false ? '{"error":"a field was not text this could send"}' : $body, 'application/json; charset=utf-8');
    }

    /** The one refusal shape: a status and one sentence. */
    public static function error(int $status, string $sentence): void
    {
        self::json($status, ['error' => $sentence]);
    }

    /** A 204: no content, no body, and no `Content-Length` to contradict it. */
    public static function empty(int $status): void
    {
        self::raw($status, '', null);
    }

    /**
     * The bytes, with the headers they need.
     *
     * @param string|null $type null for no body at all, which is what a 204 is.
     */
    public static function raw(int $status, string $body, ?string $type): void
    {
        if (self::$sent) {
            // A route that answered and then returned is a bug in the route, and continuing would
            // mean appending a second document to a body that already has one.
            error_log('omp guest api: a route tried to answer twice; the second answer was dropped');
            return;
        }
        self::$sent = true;
        if (!headers_sent()) {
            http_response_code($status);
            header('Cache-Control: no-store');
            header('X-Content-Type-Options: nosniff');
            if ($type !== null) {
                header('Content-Type: ' . $type);
            }
        }
        if ($body !== '' && $type !== null) {
            echo $body;
        }
    }
}
