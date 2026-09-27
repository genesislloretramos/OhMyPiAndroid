package omp.agent.store

import omp.agent.json.Json
import omp.agent.json.JsonException
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.Vfs
import omp.vm.workspace.Workspace

/**
 * One entry of a conversation, and the byte range it occupies in the transcript.
 *
 * [offset] and [byteLength] are not decoration: they are what lets a caller say "saved at 4210" and
 * lets a reader find the last line that is certainly complete, which after a crash is the only way
 * to know where the transcript actually ends. A transcript that is only a list of strings cannot
 * answer either question without counting bytes again, and counting bytes again is how an offset
 * off by one becomes a duplicated entry.
 */
data class Entry(
    /** Who said it. A [Kind], because the agent loop switches on it and a typo in a string is silent. */
    val kind: Kind,
    val content: String,
    /** Where this line starts, from the beginning of the transcript file, in bytes. */
    val offset: Long,
    /** How many bytes this line occupies, its terminating newline included. */
    val byteLength: Long,
    /** The other fields of the line, verbatim; a tool name, an exit status, a model. */
    val extra: Map<String, String> = emptyMap(),
)

/**
 * Who produced an entry.
 *
 * An enum and not a `String` because the agent loop branches on this: a `when` over a `String` needs
 * an `else` branch, and the `else` branch is where a misspelled role goes to be handled as if it
 * were a system message. [wire] is what goes on disk, and it is the spelling a transcript written
 * by a future version may carry — which is why [of] can answer null for a name this build does
 * not know instead of inventing a fifth kind for it.
 */
enum class Kind(val wire: String) {
    /** What the user typed. */
    USER("user"),

    /** What the model replied. */
    ASSISTANT("assistant"),

    /**
     * What a tool the agent ran came back with. Separate from [ASSISTANT] because it is evidence
     * rather than speech: a model turn and a `grep` that found nothing are not the same claim, and
     * a transcript that merged them could not tell them apart later.
     */
    TOOL_RESULT("tool-result"),

    /** Anything this app says into the transcript: a refusal, a note, a recovered skip. */
    SYSTEM("system"),

    ;

    companion object {
        /** The kind a [wire] name names, or null for one this build does not know. */
        fun of(wire: String): Kind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A line the reader did not accept, and why — counted, located, and never fatal.
 *
 * A transcript is a file in the user's `Documents`: a sync tool, a half-finished upload, a text
 * editor that saved as it was told to can all change it, and none of those is a reason to refuse
 * to show the conversation. So a bad line is described here and the rest of the transcript is
 * still returned — the alternative, throwing, loses a whole conversation to one bad byte.
 */
data class Skip(val offset: Long, val byteLength: Long, val reason: String)

/** What [Conversation.transcript] found: the entries it accepted, and what it did not. */
data class Transcript(val entries: List<Entry>, val skipped: List<Skip>) {
    /** How many lines were dropped; the message a command prints next to the count of entries. */
    val skippedCount: Int get() = skipped.size
}

/**
 * The refusal at [Conversation.MAX_TRANSCRIPT_BYTES].
 *
 * Its own type because the answer to it is not "try again": a transcript past the cap is a
 * conversation the user has to finish or clear, and a caller that catches [FsException] would
 * print "No space left on device" and send the user looking at storage that is nearly empty.
 */
class TranscriptFullException(val path: String, val bytes: Long, val limit: Long) :
    IllegalStateException(
        "transcript: $path is $bytes bytes, at or over the $limit-byte cap — nothing was " +
            "truncated; start a new conversation or clear this one",
    )

/**
 * The transcript of one conversation, living inside that conversation's folder.
 *
 * **The folder is the project and the transcript belongs to the project**, so it is written next
 * to the project files rather than in an app database the user cannot see, back up, or open in a
 * text editor. The one concession to tidiness is [META_DIR]: everything this app keeps is inside
 * one hidden directory, so `tree` on a project shows the project.
 *
 * ### JSON Lines, because a crash should cost one line
 *
 * One object per line, appended, never rewritten. A process killed mid-write leaves a partial
 * last line and [transcript] skips it, saying where it was; a whole-file format would leave a
 * half-written file that no reader could use at all. It is also the one format a person can read
 * with the tools they already have — `cat`, `less`, a text editor — which matters more here than
 * in a server, because the file is in the user's own `Documents`.
 *
 * [Json] escapes every control character below U+0020, so a line's content can never contain a
 * raw newline and the byte-level split below is safe; a lone surrogate is escaped too, so a line
 * is always encodable as UTF-8 even when the text that produced it was cut mid-character.
 *
 * Everything goes through the [Vfs] — this class is inside the namespace the agent runs in, and
 * the transcript is in the conversation folder, so there is nothing here that needs to be host-side.
 */
class Conversation(
    private val vfs: Vfs,
    /** The conversation's namespace path, e.g. `/mnt/omp/notes`. */
    val dir: String,
) {

    /** The directory this class and [AgentState] keep the app's bookkeeping in. */
    val metaDir: String = Workspace.child(dir, META_DIR)

    /** The transcript file, inside [metaDir]. */
    val path: String = Workspace.child(metaDir, TRANSCRIPT)


    /**
     * Appends one entry, and @return the byte offset it was written at.
     *
     * The offset is read from the file rather than remembered, so it stays right across a crash
     * and a restart: a caller that recorded "saved at 4210" can find that line again from the file
     * alone, which is the only copy of the fact that survives anything.
     *
     * The whole line — object and newline — is written in one [omp.shell.fs.Vfs.openWrite] and the
     * stream is closed before this returns, so a reader that arrives afterwards sees a complete
     * line or a partial one, never half of one object.
     *
     * **A partial line left by a crash is terminated before this one starts.** The app can be
     * killed between the write and the close, and the next append is the first thing that touches
     * the file afterwards; splicing a new line onto half an old one would destroy both the entry
     * being written and the one the user can still see. The newline costs one byte and turns the
     * wreck into a line [transcript] can name, locate and report.
     *
     * @throws TranscriptFullException when the result would be at or over
     *   [MAX_TRANSCRIPT_BYTES]. Nothing is written and nothing is dropped: a transcript that
     *   silently lost its oldest turns would be a transcript the agent then summarises as if it
     *   were the whole conversation.
     * @throws FsException when the conversation folder cannot be written to.
     */
    fun append(kind: Kind, content: String, extra: Map<String, String> = emptyMap()): Long {
        vfs.ensureDir(metaDir)
        val line = encode(kind, content, extra)
        val size = vfs.sizeOf(path)
        // Read before the file is opened for writing: this is a question about what is already
        // there, and a stream opened for append is no better a witness than the bytes themselves.
        val brokenTail = size > 0L && !endsWithNewline(size)
        // Past the newline that terminates a line a crash left half-written, because that byte is
        // part of the file by the time this call returns: the offset handed back has to address
        // the line, not the byte in front of it.
        val at = size + if (brokenTail) 1L else 0L
        if (at + line.size >= MAX_TRANSCRIPT_BYTES) {
            throw TranscriptFullException(path, size, MAX_TRANSCRIPT_BYTES)
        }
        vfs.openWrite(path, true).use { out ->
            if (brokenTail) {
                // A line that was cut mid-write gets its terminator before this one starts, so it
                // ends up as a whole bad line the reader can name and skip. Appending straight onto
                // it would splice this entry onto half an object and lose both: the recovery has to
                // cost the partial line, not the next one.
                out.write(NEWLINE.toInt())
            }
            out.write(line)
        }
        return at
    }

    /**
     * Whether the file's last byte terminates a line.
     *
     * The last byte only, by seeking to it: the alternative is reading a transcript that may be
     * megabytes long on every single append, to look at one byte. A [omp.shell.fs.Vfs] whose
     * streams cannot seek — a namespace synthesised in memory, most likely — falls back to reading
     * it all, which is correct and slow rather than wrong.
     */
    private fun endsWithNewline(size: Long): Boolean {
        vfs.openRead(path).use { stream ->
            if (stream.skip(size - 1) == size - 1) return stream.read() == NEWLINE.toInt()
        }
        val all = vfs.readBytes(path)
        return all[all.size - 1] == NEWLINE
    }

    /**
     * Every entry, in the order it was written.
     *
     * A convenience over [transcript] for the caller that only wants the conversation — the agent
     * loop sending history to a provider is that caller, and it has no reason to know that a
     * transcript can have a bad line in it.
     */
    fun read(): List<Entry> = transcript().entries

    /**
     * Every entry, plus every line that was not one.
     *
     * Two ways a line is refused, and both are expected rather than exceptional:
     *
     *  - **A trailing line with no newline.** A write that was cut mid-line — the process was
     *    killed, the battery went, the tab was closed — leaves exactly this. It is dropped, because
     *    a half-written JSON object parsed as far as it goes is a plausible-looking entry the agent
     *    would then act on.
     *  - **A line that does not parse, or whose role this build does not know.** Also dropped, and
     *    located: a transcript is a file in the user's storage, and a byte changed in a sync is
     *    not a reason to lose the other four hundred lines.
     *
     * A missing transcript is not a failure: a conversation nobody has said anything in yet has no
     * file, and that is the same answer as an empty one.
     */
    fun transcript(): Transcript {
        val bytes = try {
            vfs.readBytes(path)
        } catch (e: FsException) {
            if (e.errno != FsErrno.NO_SUCH_FILE) throw e
            return Transcript(emptyList(), emptyList())
        }
        val entries = ArrayList<Entry>()
        val skipped = ArrayList<Skip>()
        var start = 0
        while (start < bytes.size) {
            val newline = indexOfNewline(bytes, start)
            if (newline < 0) {
                // The tail with no newline: the only shape a cut-off write can have, and the only
                // line in the file whose completeness is in doubt.
                skipped += Skip(start.toLong(), (bytes.size - start).toLong(), "incomplete last line")
                break
            }
            val entry = decode(bytes, start, newline - start)
            if (entry != null) {
                entries += entry
            } else {
                skipped += Skip(
                    start.toLong(),
                    (newline + 1 - start).toLong(),
                    skippedAt(bytes, start, newline),
                )
            }
            start = newline + 1
        }
        return Transcript(entries, skipped)
    }

    /**
     * Throws the transcript away, and @return how many entries went with it.
     *
     * **A deliberate reset, and it is not reachable from a command without a confirmation** — it
     * takes a user's whole conversation and there is no other copy anywhere, so the count comes
     * back to the caller for exactly that reason: the command prints "this will delete N entries"
     * and asks first. The count is the number of entries [transcript] accepted, so a transcript
     * that was already damaged reports what was really in it rather than what is on disk.
     *
     * Only the transcript goes: the project files beside it and the conversation's configuration
     * in [AgentState] are not this class's to delete.
     */
    fun clear(): Int {
        val count = transcript().entries.size
        try {
            vfs.delete(path)
        } catch (e: FsException) {
            // Already gone is the state this was asked for.
            if (e.errno != FsErrno.NO_SUCH_FILE) throw e
        }
        return count
    }

    // ---- the line codec -----------------------------------------------------------------

    /** One line, terminator included, in the exact bytes it will occupy on disk. */
    private fun encode(kind: Kind, content: String, extra: Map<String, String>): ByteArray {
        // Role and content first, in that order, then the caller's own fields in the order it gave
        // them: a fixed prefix means a line can be read by eye in a text editor.
        val fields = LinkedHashMap<String, Json>()
        fields[ROLE] = Json.Str(kind.wire)
        fields[CONTENT] = Json.Str(content)
        for ((key, value) in extra) {
            if (key == ROLE || key == CONTENT) {
                throw IllegalArgumentException(
                    "transcript: the extra field '$key' is one the line already has; a transcript " +
                        "line has one role and one content",
                )
            }
            fields[key] = Json.Str(value)
        }
        return (Json.Obj(fields).toCompactString() + "\n").toByteArray(Charsets.UTF_8)
    }

    /** One line as an [Entry], or null when it is not one this build can read. */
    private fun decode(bytes: ByteArray, start: Int, length: Int): Entry? {
        val text = String(bytes, start, length, Charsets.UTF_8)
        val obj = try {
            Json.parse(text) as? Json.Obj ?: return null
        } catch (e: JsonException) {
            return null
        }
        val kind = obj.str(ROLE)?.let { Kind.of(it) } ?: return null
        val content = obj.str(CONTENT) ?: return null
        val extra = LinkedHashMap<String, String>()
        for ((key, value) in obj.fields) {
            if (key == ROLE || key == CONTENT) continue
            // A field that is not a string is not this format. [extra] can only carry strings, and
            // stringifying a number here would be inventing a value the line does not have.
            val str = (value as? Json.Str)?.value ?: return null
            extra[key] = str
        }
        return Entry(kind, content, start.toLong(), (length + 1).toLong(), extra)
    }

    /**
     * The next line terminator at or after [from].
     *
     * Byte-wise because the offsets this class reports are byte offsets, and because [Json] escapes
     * everything below U+0020 — so a 0x0A inside a string is impossible and the split cannot land
     * in the middle of a value.
     */
    private fun indexOfNewline(bytes: ByteArray, from: Int): Int {
        var i = from
        while (i < bytes.size) {
            if (bytes[i] == NEWLINE) return i
            i++
        }
        return -1
    }

    /**
     * Why a line was refused, for the message the reader gets.
     *
     * The line's own text is not quoted back: it is a line in a transcript, and a diagnostic that
     * printed a user's own message — or a model's — into a log for a parse failure is a diagnostic
     * nobody can paste into a bug report.
     */
    private fun skippedAt(bytes: ByteArray, start: Int, end: Int): String {
        val text = String(bytes, start, end - start, Charsets.UTF_8)
        val parsed = try {
            Json.parse(text)
        } catch (e: JsonException) {
            return "not JSON (${e.message})"
        }
        if (parsed !is Json.Obj) return "not a JSON object"
        val wire = parsed.str(ROLE)
            ?: return "no '$ROLE' string"
        if (Kind.of(wire) == null) return "unknown role '$wire'"
        if (parsed.str(CONTENT) == null) return "no '$CONTENT' string"
        return "a field is not a string"
    }

    companion object {
        /**
         * 8 MiB, the point at which [append] refuses rather than grows.
         *
         * A long session — a few thousand turns with the output of the tools it ran — lands well
         * inside it, and it is also the size at which reading the whole transcript is something a
         * phone should be asked to do on purpose. The refusal is the point: a transcript that grew
         * without bound would end one day in a failed write with a truncated history, and the agent
         * would carry on summarising a conversation it had quietly lost the beginning of. A user
         * who hits this gets a message with the number in it and two honest ways out.
         */
        const val MAX_TRANSCRIPT_BYTES = 8L * 1024 * 1024

        /** The role field, and the name every other field may not take. */
        const val ROLE = "role"

        /** The message field. */
        const val CONTENT = "content"

        /** 0x0A, the one byte a line ends with. */
        private const val NEWLINE: Byte = '\n'.code.toByte()
    }
}
