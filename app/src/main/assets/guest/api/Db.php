<?php
/*
 * omp guest API — conversations and messages, in MariaDB. NOT RUN: this build has no PHP and no
 * MySQL, so this file has never been executed, and nothing here has ever opened a socket.
 */

declare(strict_types=1);

namespace omp\guest;

use PDO;
use PDOException;

/**
 * The guest's own database, its schema, and the two tables the API is really about.
 *
 * ### Why a database at all, when a conversation is a folder
 *
 * **Because the page asks three questions a directory cannot answer cheaply.** Which conversations
 * exist, what the most recent few hundred lines of one said, and whether a turn is still running —
 * and the third of those has to be answerable by a *different* process from the one streaming the
 * answer, because the browser's Stop button is its own HTTP request. The folder holds the
 * conversation; this holds the index and the running turns.
 *
 * ### The schema is created on first use, and the migration is not decoration
 *
 * `schema.sql` is applied on the first request that reaches here, and every statement in it is
 * `IF NOT EXISTS`, so a guest that already has the tables pays one round trip and changes nothing.
 * **`migrate()` then adds the two columns `schema.sql` does not have** — `conversations.title` and
 * `turns.ended` — and the code below reads both of them. So the migration runs on a fresh install
 * too, and a guest whose tables were made by an earlier build of this file still answers. A
 * migration that only ever runs on somebody else's database is a migration nobody has run.
 *
 * ### What is never in here
 *
 * **No key, and no key's length.** `configured` and `keyStored` are answered by
 * `Agent::hasKey()`, which asks the guest's environment whether a variable is set and never reads
 * one. A row in this database is a conversation, and a conversation is text.
 */
final class Db
{
    /** Where the socket is, by default, in a Debian with `mariadb-server` from the archive. */
    public const DEFAULT_DSN = 'mysql:unix_socket=/run/mysqld/mysqld.sock;charset=utf8mb4';

    /** The schema's own name, so nothing this app does can touch a database it did not make. */
    public const DEFAULT_DATABASE = 'omp';

    private static ?PDO $pdo = null;

    /** The connection, opened and migrated on the first call in a request. */
    public static function pdo(): PDO
    {
        if (self::$pdo === null) {
            self::$pdo = self::connect();
        }
        return self::$pdo;
    }

    /**
     * Open, create the schema if it is not there, and add what the migration adds.
     *
     * **The database is created with `CREATE DATABASE IF NOT EXISTS` and not assumed.** A guest
     * that has been asked for LAMP and given it has a server and a root account and no schema, and
     * the alternative to creating one here is a first request that fails with "Unknown database".
     *
     * The name is not interpolated into the statement: it is quoted, and it is quoted because it
     * comes from the environment. A DSN is not a secret, but a name from an environment is still
     * input, and the habit of interpolating is the thing this project keeps refusing.
     */
    private static function connect(): PDO
    {
        $dsn = self::env('OMP_DB_DSN', self::DEFAULT_DSN);
        $name = self::env('OMP_DB_NAME', self::DEFAULT_DATABASE);
        $user = self::env('OMP_DB_USER', 'root');
        $password = self::env('OMP_DB_PASSWORD', '');

        $server = self::pdoFor($dsn, $user, $password);
        $server->exec('CREATE DATABASE IF NOT EXISTS `' . str_replace('`', '', $name) .
            '` DEFAULT CHARACTER SET utf8mb4');
        $pdo = self::pdoFor($dsn . ';dbname=' . $name, $user, $password);
        self::applySchema($pdo);
        self::migrate($pdo);
        return $pdo;
    }

    private static function pdoFor(string $dsn, string $user, string $password): PDO
    {
        try {
            return new PDO($dsn, $user, $password, [
                // ERRMODE_EXCEPTION, because the alternative is a query that fails and returns
                // false, and a false that is not checked becomes an empty transcript rather than an
                // error the user could see.
                PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
                // Real prepared statements, so a conversation name and a question are never
                // assembled into SQL by this file at all.
                PDO::ATTR_EMULATE_PREPARES => false,
                PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            ]);
        } catch (PDOException $e) {
            // The message of a PDOException names the DSN, and a DSN can carry a password. The
            // browser is told what is missing in the guest's own words; the log gets the rest.
            error_log('omp guest api: the database is not reachable — ' . $e->getMessage());
            throw new Refusal(
                503,
                'the guest has no database to answer from: start mariadb in the guest, or point ' .
                    'OMP_DB_DSN, OMP_DB_NAME and OMP_DB_PASSWORD at one that is running',
            );
        }
    }

    /** `schema.sql`, split on the semicolon at the end of a statement. */
    private static function applySchema(PDO $pdo): void
    {
        $sql = @file_get_contents(__DIR__ . '/schema.sql');
        if ($sql === false) {
            throw new Refusal(500, 'the guest is missing its own schema: schema.sql is not next to index.php');
        }
        // A statement is everything up to a `;` that is not inside a string. This file's schema has
        // no semicolon inside one, which is stated here so that the next person to add a default
        // value with a `;` in it finds out why this stopped working.
        foreach (array_filter(array_map('trim', explode(';', $sql))) as $statement) {
            if (str_starts_with($statement, '--')) {
                continue;
            }
            $pdo->exec($statement);
        }
    }

    /**
     * The three columns `schema.sql` does not carry, and the code below reads all of them.
     *
     * **`ended` is what makes a turn a turn**: a turn is running exactly while its `ended` is NULL,
     * so the same column answers "is this conversation busy", "has it been stopped" and "what did
     * the Stop button do". One fact in one column, rather than a status string and a flag that can
     * disagree with it.
     *
     * `SHOW COLUMNS` rather than `information_schema`, because that is MariaDB's own answer to the
     * question and the one an operator would run by hand when this went wrong. The existence check
     * is by hand as well, because `ALTER TABLE` has no `IF NOT EXISTS` in this version and a second
     * `ALTER` on a column that exists is an error rather than a no-op.
     */
    private static function migrate(PDO $pdo): void
    {
        self::addColumn($pdo, 'conversations', 'title', "VARCHAR(191) NOT NULL DEFAULT ''");
        self::addColumn($pdo, 'turns', 'ended', 'BIGINT NULL');
        self::addColumn($pdo, 'turns', 'cancel_requested', 'TINYINT NOT NULL DEFAULT 0');
    }

    private static function addColumn(PDO $pdo, string $table, string $column, string $type): void
    {
        $found = $pdo->query("SHOW COLUMNS FROM `$table` LIKE '$column'")->fetchAll();
        if ($found !== []) {
            return;
        }
        try {
            $pdo->exec("ALTER TABLE `$table` ADD COLUMN `$column` $type");
        } catch (PDOException $e) {
            // Two requests can reach this at once, and the loser of that race finds the column
            // already there. That is a success, and treating it as a failure would make a busy
            // guest look broken.
            $again = $pdo->query("SHOW COLUMNS FROM `$table` LIKE '$column'")->fetchAll();
            if ($again === []) {
                throw $e;
            }
        }
    }

    // ---- conversations -------------------------------------------------------------------------

    /** The row for a conversation, or null when this guest has never seen it. */
    public static function conversation(string $name): ?array
    {
        $statement = self::pdo()->prepare('SELECT id, name, created, modified, title FROM conversations WHERE name = ?');
        $statement->execute([$name]);
        $row = $statement->fetch();
        return $row === false ? null : $row;
    }

    /**
     * The row for a conversation, made if it is not there.
     *
     * **The folder owns the name and this only records it.** A conversation is created by making a
     * directory; this is the index entry for a directory that exists, and a request for a
     * conversation that was never opened does not make a row for it until something is said in it.
     */
    public static function touch(string $name, int $millis): array
    {
        $row = self::conversation($name);
        if ($row !== null) {
            self::pdo()->prepare('UPDATE conversations SET modified = ? WHERE id = ?')
                ->execute([$millis, $row['id']]);
            $row['modified'] = $millis;
            return $row;
        }
        self::pdo()->prepare(
            'INSERT INTO conversations (name, created, modified, title) VALUES (?, ?, ?, ?)',
        )->execute([$name, $millis, $millis, '']);
        $row = self::conversation($name);
        if ($row === null) {
            throw new Refusal(500, 'the guest could not record that conversation');
        }
        return $row;
    }

    public static function setTitle(string $name, string $title): void
    {
        $row = self::conversation($name);
        if ($row !== null) {
            self::pdo()->prepare('UPDATE conversations SET title = ? WHERE id = ?')
                ->execute([$title, $row['id']]);
        }
    }

    // ---- messages ------------------------------------------------------------------------------

    /**
     * One row of a conversation, oldest last, for the transcript.
     *
     * **No `approved`, and no way to pass one.** The guest's agent is asked about a write by
     * nobody, so there is no approval to record and a parameter that could only ever be null is a
     * parameter that invites somebody to fill it in with a `y` that a person never gave.
     */
    public static function addMessage(
        int $conversationId,
        string $role,
        string $content,
        ?string $tool = null,
    ): void {
        self::pdo()->prepare(
            'INSERT INTO messages (conversation_id, at, role, content, tool) VALUES (?, ?, ?, ?, ?)',
        )->execute([$conversationId, self::now(), $role, $content, $tool]);
    }

    /**
     * A conversation's messages: the last `$limit` of them, and how many there are.
     *
     * **The total is counted, not inferred from the page.** A chat shows the recent conversation,
     * and a truncated list that does not say it was truncated is the failure this project keeps
     * refusing to ship.
     *
     * @return array{entries: list<array>, total: int}
     */
    public static function messages(int $conversationId, int $limit): array
    {
        $total = (int) self::pdo()
            ->query('SELECT COUNT(*) FROM messages WHERE conversation_id = ' . $conversationId)
            ->fetchColumn();
        $statement = self::pdo()->prepare(
            'SELECT role, content, tool, approved FROM messages WHERE conversation_id = ' .
                $conversationId . ' ORDER BY id DESC LIMIT ' . $limit,
        );
        $statement->execute();
        $rows = $statement->fetchAll();
        return ['entries' => array_reverse($rows), 'total' => $total];
    }

    // ---- turns ---------------------------------------------------------------------------------

    /**
     * Claim a conversation for one turn, or say that it is already busy.
     *
     * **The row is the lock, and the uniqueness is the database's.** Two messages for one
     * conversation arrive as two requests; the loser of the race finds the row already there and is
     * answered with the app's own 409, because two answers appended to one conversation's history
     * would interleave into something that is no longer an ordered conversation.
     */
    public static function claimTurn(string $turn, int $conversationId, int $millis): bool
    {
        // Asked first, and in its own statement, so that the answer to "is this conversation
        // busy?" is never a side effect of an insert that failed. A dead connection is reported
        // where it belongs — as a database that is not there — and not as a conversation that
        // happens to be answering something.
        $open = self::pdo()->prepare(
            'SELECT id FROM turns WHERE conversation_id = ? AND ended IS NULL LIMIT 1',
        );
        $open->execute([$conversationId]);
        if ($open->fetch() !== false) {
            return false;
        }
        try {
            self::pdo()->prepare(
                'INSERT INTO turns (id, conversation_id, started) VALUES (?, ?, ?)',
            )->execute([$turn, $conversationId, $millis]);
        } catch (PDOException $e) {
            // The loser of a race between two requests for the same conversation. The row is
            // already there, which is the answer this method gives.
            return false;
        }
        // Any other turn of this conversation that never ended is a turn that is still running: an
        // Apache worker killed mid-answer leaves its row behind, and the page's Stop button should
        // still be able to reach it.
        self::pdo()->prepare(
            'UPDATE turns SET ended = ? WHERE conversation_id = ? AND ended IS NULL AND id <> ?',
        )->execute([$millis, $conversationId, $turn]);
        return true;
    }
    /** Whether a turn is still running, and whether it has just been asked to stop. */
    public static function turnState(string $turn): ?array
    {
        $statement = self::pdo()->prepare('SELECT ended, cancel_requested FROM turns WHERE id = ?');
        $statement->execute([$turn]);
        $row = $statement->fetch();
        return $row === false ? null : $row;
    }

    /**
     * Ask a running turn to stop, or say there is nothing to stop.
     *
     * **This does not kill anything.** The request that is streaming the answer owns the child
     * process, and a flag is the only thing that can reach it: `proc_terminate` needs the handle,
     * and a handle does not survive into another Apache worker. The streaming loop asks this
     * question every quarter of a second and acts on it.
     */
    public static function cancelTurn(string $turn): bool
    {
        $state = self::turnState($turn);
        if ($state === null || $state['ended'] !== null) {
            return false;
        }
        self::pdo()->prepare('UPDATE turns SET cancel_requested = 1 WHERE id = ?')->execute([$turn]);
        return true;
    }

    /** Whether the Stop button has been pressed for a turn. */
    public static function isCancelled(string $turn): bool
    {
        $state = self::turnState($turn);
        return $state !== null && (int) $state['cancel_requested'] === 1;
    }

    // ---- small things --------------------------------------------------------------------------

    private static function env(string $name, string $fallback): string
    {
        $value = getenv($name);
        return is_string($value) && $value !== '' ? $value : $fallback;
    }

    /** Milliseconds since the epoch, which is what the page's `new Date(…)` wants. */
    public static function now(): int
    {
        return (int) round(microtime(true) * 1000);
    }
}
