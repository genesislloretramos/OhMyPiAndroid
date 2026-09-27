<?php
/*
 * omp guest API — every path a request names. NOT RUN: this build has no PHP and no MySQL, so this
 * file has never been executed. It is the one piece of the guest with no test runner behind it, and
 * it is therefore the most boring code in the tree: the app's own rule, plus one check the app does
 * not need, spelled out below.
 */

declare(strict_types=1);

namespace omp\guest;

/** A refusal, carrying the status and the one sentence the browser is going to be shown. */
final class Refusal extends \RuntimeException
{
    public int $status;

    public function __construct(int $status, string $sentence)
    {
        parent::__construct($sentence);
        $this->status = $status;
    }
}

/**
 * Where the conversations are, and how a name from a URL is turned into a directory.
 *
 * ### The rule, and where it comes from
 *
 * **A conversation is one folder, and the folder is named by the user.** The app's own
 * `omp.vm.workspace.Workspace.open` decides which folder a name reaches, and this is that decision:
 *
 * 1. a name holding `/`, `\` or `..` is refused outright — before the filesystem is consulted, so a
 *    name that walks out of the container never reaches a stat that would answer about it;
 * 2. the name is then sanitised by `omp.vm.workspace.WorkspaceName`'s own rules, and a name that
 *    sanitises away to nothing is refused;
 * 3. the sanitised name is joined onto the container, and the result has to be a directory.
 *
 * **The refusal carries the path that was asked for, not the one that was looked up**, which is what
 * `Workspace.open` does and what makes the sentence match the app's byte for byte.
 *
 * ### The one check the app does not need, and this one does
 *
 * The app's own boundary is `omp.agent.tools.Sandbox`, and it is written in Kotlin against a `Vfs`
 * that answers `realpathOf`. Here there is no `Vfs`: there is a PHP filesystem call, and a
 * filesystem call resolves a symlink. So **after** the name has been sanitised and **before** the
 * path is used, the real path of the container and the real path of the candidate are asked for and
 * compared, component by component. A conversation folder that is a symlink pointing out of the
 * container is refused, exactly as `Sandbox.resolve` refuses a link that leads out — and it is
 * refused here rather than argued about later, because the failure it prevents is a request that
 * reads a directory the caller was not given.
 *
 * **Everything is refused rather than repaired.** A name that is not exactly the folder's own name
 * is not "close enough", and there is no `..` here that resolves to something inside: rule 1
 * already ended that conversation.
 */
final class Paths
{
    /**
     * The guest's own mount of the conversations folder.
     *
     * `/mnt/omp` is where `omp.vm.provision.ProotCommand` binds it, and the same path the
     * namespace VM uses, so a conversation is one folder under two names and not two folders.
     */
    public const DEFAULT_ROOT = '/mnt/omp';

    /**
     * The same folder as a file manager on the phone shows it.
     *
     * The page offers to copy this string, and a user pastes it into a file manager, so it is the
     * phone's path and never the guest's bind.
     */
    public const DEFAULT_HOST_ROOT = '/storage/emulated/0/Documents/omp';

    /**
     * The cap on a name, in code points.
     *
     * 60 is `omp.vm.workspace.WorkspaceName.MAX`, and the reason is there: 60 code points is at
     * most 240 bytes in UTF-8, which fits the 255-byte limit the exFAT volume behind
     * `Documents/omp` puts on one path component even when every character is astral.
     */
    public const MAX_NAME = 60;

    /** The container itself, from the guest's own environment. */
    public static function root(): string
    {
        $root = getenv('OMP_WORKSPACE_ROOT');
        return is_string($root) && $root !== '' ? rtrim($root, '/') : self::DEFAULT_ROOT;
    }

    /** The same folder as the phone shows it, from the guest's own environment. */
    public static function hostRoot(): string
    {
        $host = getenv('OMP_HOST_ROOT');
        return is_string($host) && $host !== '' ? rtrim($host, '/') : self::DEFAULT_HOST_ROOT;
    }

    /**
     * The container, or a refusal in the app's own words.
     *
     * **The app answers this with `omp.vm.HostAccess`'s sentence**, because on a phone the question
     * is whether the platform granted all-files access. Inside the guest there is no platform
     * grant to ask about: the question is whether this directory exists, and the honest answer to
     * that is the shell's own errno wording.
     */
    public static function container(): string
    {
        $root = self::root();
        $real = @realpath($root);
        if ($real === false || !is_dir($real)) {
            throw new Refusal(503, self::errno($root, is_dir($root) ? 'not a directory' : null));
        }
        return $real;
    }

    /**
     * The conversation called `$name`, or a refusal.
     *
     * @return the folder's real path. The **real** path and not the joined one, so that everything
     *   downstream — the agent's working directory, the transcript, the session directory — works
     *   from what the filesystem says rather than from what a URL claimed.
     */
    public static function conversation(string $name): string
    {
        $root = self::container();
        $asked = self::child($root, $name);

        // Rule 1: nothing that could be a path is a name. Checked on the raw name, before any
        // sanitising, because sanitising would turn "../../etc" into "etc" and answer about a
        // folder the caller never named.
        if ($name === '' || strpbrk($name, "/\\") !== false || strpos($name, '..') !== false) {
            throw new Refusal(404, self::errno($asked, 'missing'));
        }
        if (strlen($name) > 4 * self::MAX_NAME || preg_match('/[\x00-\x1F\x7F]/u', $name) !== 1) {
            throw new Refusal(404, self::errno($asked, 'missing'));
        }

        // Rule 2: the app's own sanitising, and nothing survives it is not a name.
        $safe = self::sanitise($name);
        if ($safe === null) {
            throw new Refusal(404, self::errno($asked, 'missing'));
        }
        // The folder owns its name. A name that had to be changed to become a name is not the name
        // that was asked for, and answering about `Photos` when `photos` was asked for is the
        // case-folding this project refuses everywhere else.
        if ($safe !== $name) {
            throw new Refusal(404, self::errno($asked, 'missing'));
        }

        // Rule 3: the path, and the one check the app gets from its Vfs.
        $path = self::child($root, $safe);
        $real = @realpath($path);
        if ($real === false) {
            throw new Refusal(404, self::errno($path, 'missing'));
        }
        if (!self::inside($root, $real)) {
            // A link that leads out of the container. `Sandbox.resolve` refuses this; the sentence
            // is the same 404 rather than a new one, because from the caller's side it is exactly
            // that: there is no conversation of that name.
            throw new Refusal(404, self::errno($path, 'missing'));
        }
        if (!is_dir($real)) {
            throw new Refusal(404, self::errno($path, 'not a directory'));
        }
        return $real;
    }

    /**
     * Every conversation in the container, as `[name => real path]`.
     *
     * **A symlinked entry is left out**, not followed: a link in the conversations folder is
     * something a person made and this API does not decide what it points at.
     */
    public static function conversations(): array
    {
        $root = self::container();
        $out = [];
        $entries = @scandir($root);
        if ($entries === false) {
            return $out;
        }
        foreach ($entries as $entry) {
            if ($entry === '.' || $entry === '..') {
                continue;
            }
            $path = self::child($root, $entry);
            if (is_link($path) || !is_dir($path)) {
                continue;
            }
            $real = @realpath($path);
            if ($real === false || !self::inside($root, $real)) {
                continue;
            }
            $out[$entry] = $real;
        }
        return $out;
    }

    /**
     * The name a conversation's own folder is called on disk, which is the answer a filesystem
     * gives and not the one a URL claimed.
     *
     * The app asks for the same thing in `Workspace.open` through `reportedName`, because
     * `Documents/omp` is exFAT and there `Photos` and `photos` are one directory.
     */
    public static function onDiskName(string $path): string
    {
        return basename($path);
    }

    /** The phone's path for a folder inside the guest. */
    public static function hostPath(string $path): string
    {
        return self::child(self::hostRoot(), self::onDiskName($path));
    }

    /**
     * Whether `$candidate` is `$base` itself or something under it.
     *
     * **A prefix, with a separator.** `/mnt/omp-evil` is not inside `/mnt/omp`, and a comparison
     * that forgets the separator is the whole of that bug. Both sides are compared after
     * `realpath`, so `..` and links have already been resolved by the filesystem rather than by
     * this string handling.
     */
    public static function inside(string $base, string $candidate): bool
    {
        $base = rtrim($base, '/');
        return $candidate === $base || strncmp($candidate, $base . '/', strlen($base) + 1) === 0;
    }

    /** `dir/name`, the way `omp.vm.workspace.Workspace.child` spells it. */
    public static function child(string $dir, string $name): string
    {
        return rtrim($dir, '/') . '/' . $name;
    }

    /**
     * The app's own name rules, in PHP.
     *
     * **Ported from `omp.vm.workspace.WorkspaceName.sanitize` and kept deliberately literal**: a
     * conversation folder is a name a person typed and then sees in a file manager, so what is
     * removed is only what cannot be part of a name — separators, control characters and the
     * punctuation a filesystem reserves — and nothing here normalises UTF-8, in either direction.
     * `café` typed as `e` plus a combining accent is two spellings and stays two, because a name
     * the app silently rewrote is a folder the user cannot find again.
     *
     * **No mbstring, deliberately.** `php8.4-mbstring` is not in the package list this app
     * installs, so a `mb_substr` here would be a call to a function the guest may not have. PCRE
     * is compiled into PHP itself and does code points with the `u` modifier, which is enough for
     * a cut on a character boundary.
     *
     * @return the name, or null when nothing in `$raw` could be part of one.
     */
    public static function sanitise(string $raw): ?string
    {
        if (trim($raw) === '') {
            return null;
        }
        // A name that is not UTF-8 cannot be cut on a character boundary, so it is not a name.
        if (preg_match('//u', $raw) !== 1) {
            return null;
        }
        $out = '';
        foreach (preg_split('//u', $raw, -1, PREG_SPLIT_NO_EMPTY) as $character) {
            $code = self::codePoint($character);
            if ($code < 0x20 || $code === 0x7F) {
                continue; // a control character: part of no filename
            }
            if ($character === '/' || $character === '\\') {
                continue; // the separator, and the one that makes a name a path on a desktop
            }
            if (self::isSpace($character)) {
                // Runs of spaces collapse to one dash: keeping spaces is legal and hostile, since
                // every shell the name is later typed into has to quote it.
                if ($out !== '' && substr($out, -1) !== '-') {
                    $out .= '-';
                }
                continue;
            }
            if (self::isMark($code)) {
                // A combining mark belongs to the letter before it and dropping one deletes the
                // accent the user typed. A variation selector is the exception: what it modifies is
                // an emoji, and the emoji is already gone.
                if (!self::isVariation($code)) {
                    $out .= $character;
                }
                continue;
            }
            if (self::isLetterOrDigit($code) || $character === '-' || $character === '_' || $character === '.') {
                $out .= $character;
            }
            // Everything else — a `?`, a `*`, an emoji, a zero-width joiner — is dropped rather than
            // escaped, because a name is a name and not a shell token.
        }
        // A leading `-` reads as a flag and a leading `.` reads as hidden, or as `..`.
        $out = ltrim($out, '-.');
        // A trailing dot or dash reads as nothing at all on a phone's own filesystem.
        $out = rtrim($out, '-.');
        if ($out === '') {
            return null;
        }
        return self::fit($out);
    }

    /** One code point of one character, or -1 when it is not one. */
    private static function codePoint(string $character): int
    {
        $length = strlen($character);
        if ($length === 1) {
            return ord($character);
        }
        if ($length === 2) {
            return ((ord($character[0]) & 0x1F) << 6) | (ord($character[1]) & 0x3F);
        }
        if ($length === 3) {
            return ((ord($character[0]) & 0x0F) << 12) | ((ord($character[1]) & 0x3F) << 6) |
                (ord($character[2]) & 0x3F);
        }
        if ($length === 4) {
            return ((ord($character[0]) & 0x07) << 18) | ((ord($character[1]) & 0x3F) << 12) |
                ((ord($character[2]) & 0x3F) << 6) | (ord($character[3]) & 0x3F);
        }
        return -1;
    }

    private static function isLetterOrDigit(int $code): bool
    {
        if ($code < 0) {
            return false;
        }
        if ($code < 0x80) {
            return ($code >= 0x30 && $code <= 0x39) || ($code >= 0x41 && $code <= 0x5A) ||
                ($code >= 0x61 && $code <= 0x7A);
        }
        return preg_match('/^[\p{L}\p{N}]$/u', self::characterOf($code)) === 1;
    }

    private static function isSpace(string $character): bool
    {
        return preg_match('/^\s$/u', $character) === 1;
    }

    private static function isMark(int $code): bool
    {
        return $code >= 0 && preg_match('/^\p{M}$/u', self::characterOf($code)) === 1;
    }

    private static function isVariation(int $code): bool
    {
        return ($code >= 0xFE00 && $code <= 0xFE0F) || ($code >= 0xE0100 && $code <= 0xE01EF);
    }

    /**
     * A code point as the one character a `preg` with `u` can be given.
     *
     * PCRE takes UTF-8 and not an integer, and `mb_chr` is mbstring, which this guest may not have
     * — `php8.4-mbstring` is not in the package list this app installs. So the code point is
     * encoded here, by hand, and the answer is a valid UTF-8 string whatever the code point is. A
     * code point that is not a character is U+FFFD, so a predicate asked about it answers false
     * rather than throwing.
     */
    private static function characterOf(int $code): string
    {
        if ($code < 0) {
            return "\xEF\xBF\xBD";
        }
        if ($code < 0x80) {
            return chr($code);
        }
        if ($code < 0x800) {
            return chr(0xC0 | ($code >> 6)) . chr(0x80 | ($code & 0x3F));
        }
        if ($code < 0x10000) {
            return chr(0xE0 | ($code >> 12)) . chr(0x80 | (($code >> 6) & 0x3F)) .
                chr(0x80 | ($code & 0x3F));
        }
        if ($code <= 0x10FFFF) {
            return chr(0xF0 | ($code >> 18)) . chr(0x80 | (($code >> 12) & 0x3F)) .
                chr(0x80 | (($code >> 6) & 0x3F)) . chr(0x80 | ($code & 0x3F));
        }
        return "\xEF\xBF\xBD";
    }

    /**
     * `<path>: <reason>`, in the shell's own errno wording.
     *
     * The same sentences `omp.shell.fs.Vfs` prints, because the app's own server passes them
     * through unchanged and a refusal that says something else on this server is two sentences for
     * one event.
     */
    public static function errno(string $path, ?string $which): string
    {
        $reason = match ($which) {
            'not a directory' => 'Not a directory',
            'is a directory' => 'Is a directory',
            'exists' => 'File exists',
            'denied' => 'Permission denied',
            'not empty' => 'Directory not empty',
            default => 'No such file or directory',
        };
        return $path . ': ' . $reason;
    }
}
