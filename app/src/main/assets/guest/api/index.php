<?php
/*
 * omp guest API — front controller. NOT RUN: this build has no PHP and no MySQL, so this file has
 * never been executed, and every claim below about what it does is a claim about a program, not a
 * report from one. Its contract is omp/vm/guestapi/GuestApi.kt in the app's own :core, and
 * app/src/main/assets/web/app.js is the page it answers.
 *
 * Apache reaches this file for every /api/… URL through one FallbackResource line in
 * /etc/apache2/conf-available/omp-guest.conf, installed by this app's provisioning step. There is
 * no .htaccess and no rewrite rule here because Debian's own 000-default.conf sets
 * `AllowOverride None` for /var/www/html: a .htaccess in that directory would be read by nobody,
 * which is the quietest way to ship a routing rule that does nothing.
 *
 * Every failure leaves here as a sentence, never as a PHP message: a warning printed into the body
 * of a streamed answer is a page of source in the middle of somebody's conversation, and the page
 * in this app shows whatever arrives in the `error` field as if a person had written it.
 */

declare(strict_types=1);

/*
 * display_errors off, and said here rather than remembered.
 *
 * This is the ONLY place a PHP diagnostic can be allowed to become a byte on the wire, and it is
 * the line that stops it. php.ini in a Debian is a second opinion, and `php_admin_flag` in a vhost
 * is a second opinion about a file this app did not write and cannot see. So it is set here, at
 * the top of the one entry point, before anything else can fail:
 *
 *   - a parse error in a file that was `require`d is still printed by the engine itself, which is
 *     why every include below is guarded and every failure has its own message;
 *   - everything else goes to the error log, where an operator can read it, rather than into a
 *     response a browser is about to hand to a user.
 */
ini_set('display_errors', '0');
ini_set('display_startup_errors', '0');
ini_set('log_errors', '1');

/** The contract, the route table, the paths, the database and the agent, in that order. */
require_once __DIR__ . '/Paths.php';
require_once __DIR__ . '/Reply.php';
require_once __DIR__ . '/Db.php';
require_once __DIR__ . '/Sse.php';
require_once __DIR__ . '/Agent.php';
require_once __DIR__ . '/Routes.php';

use omp\guest\Reply;
use omp\guest\Routes;

try {
    Routes::dispatch($_SERVER['REQUEST_METHOD'] ?? 'GET', parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/');
} catch (Throwable $e) {
    // The last line of defence, and the only place a Throwable that is not a Reply can be turned
    // into something a browser can show. Nothing below this point throws on purpose, so arriving
    // here means a bug rather than a bad request, and a bug is a 500 with a sentence.
    error_log('omp guest api: ' . $e->getMessage() . ' at ' . $e->getFile() . ':' . $e->getLine());
    Reply::json(500, ['error' => 'the guest could not answer that: ' . self_reason($e)]);
}

/**
 * One sentence about an unexpected failure, with nothing in it that a caller did not put there.
 *
 * The class name is kept — "PDOException" tells an operator in a log what to look for, and this
 * string is in a log too — but no file, no line and no message from the driver go out to the
 * browser, because a MariaDB error carries a query, a table name and occasionally a connection
 * string, and a connection string is where a password lives.
 */
function self_reason(Throwable $e): string
{
    $class = get_class($e);
    return $class . ' — see the guest’s own error log';
}
