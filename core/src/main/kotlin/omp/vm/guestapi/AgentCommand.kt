package omp.vm.guestapi

/**
 * The `omp` invocation the guest runs, and every fact about it that was measured rather than
 * assumed.
 *
 * **Everything in this file was read off the binary on this machine**, `omp/18.3.5` at
 * `/home/genesis/.local/bin/omp`, and the two things that could not be established are marked as
 * such. A flag that does not exist is a feature that silently does nothing on a phone, so nothing
 * here is derived from a plausible-sounding guess: [HELP] is the help text these flags were taken
 * from, and [omp.vm.guestapi.GuestApiContractTest] fails if a flag is used that the help does not
 * contain, or if the guest's PHP stops passing it.
 *
 * ```
 * /usr/local/bin/omp -p --mode=json --approval-mode write \
 *     --cwd <conversation> --session-dir <conversation>/.omp/agent [--continue]
 * ```
 *
 * with **the question written to the child's standard input, which is then closed.**
 *
 * - **`-p, --print` — "Non-interactive mode: process prompt and exit".** One prompt, then exit. This
 *   is the whole of "answer one question": there is no REPL to leave, and the exit status is
 *   something the guest can look at.
 * - **`--mode=json` — "Output mode: text (default), json, rpc, or rpc-ui".** **This is the flag the
 *   streaming requirement turns on.** Measured: with the default text mode and stdout on a file, 200
 *   lines of output appeared in one burst at the end; with `--mode=json` the same file grew at one,
 *   two, three and four seconds while the answer was still being written. The default mode is
 *   therefore unusable for a page that draws an answer as it arrives, and the JSON mode is
 *   newline-delimited objects whose `message_update` events carry a `text_delta` per chunk.
 * - **`--approval-mode write` — "Override tools.approvalMode for this session
 *   (always-ask|write|yolo)".** See [APPROVAL] below: this is the conservative end of a measured
 *   choice, not a default.
 * - **`--cwd=<value>` — "Directory to start in (overrides the launch cwd)".** The project is the
 *   conversation folder, which is what this project's whole model is: a conversation is a folder.
 * - **`--session-dir=<value>` — "Directory for session storage and lookup".** The session lives in
 *   the conversation's own folder, so a conversation is still one folder and a file in there is
 *   still a file under *Internal storage ▸ Documents*.
 * - **`--continue` — "Continue previous session".** Added when that directory already holds a
 *   session, and measured to work: two runs against one `--session-dir`, the second with `-c`,
 *   reported the **same session id**, and the second answer used a fact given only in the first.
 *   On a directory with no session in it `-c` is harmless — it starts one, also measured.
 * - **`--model=<value>` — "Model to use (fuzzy match: "opus", "gpt-5.2", or "openai/gpt-5.2")".**
 *   Passed only when the guest's environment names a model.
 *
 * ### Why the question goes in on standard input and not in `MESSAGES`
 *
 * The positional argument is the documented way — `omp -p "List all .ts files in src/"` — and
 * [HELP] says of `MESSAGES`: "Messages to send (prefix files with @)". **So a question the user
 * typed that begins with `@` is a file to include**, read out of the working directory by an agent
 * whose working directory is a conversation folder. A chat box must not be a way to name a file
 * for the model, so the guest writes the question on standard input and closes it, and `@` means
 * nothing there. Measured: the prompt on a closed pipe produced the same JSON stream and the same
 * answer as the positional form.
 *
 * **Closing standard input is not a detail — it is the thing that makes the agent start.** With
 * standard input an open pipe and no data, `omp` prints this to stderr and waits for ever:
 *
 * ```
 * Reading prompt from piped stdin (waiting for EOF; Ctrl+C to abort)…
 * Still starting after 10s — phase: readPipedInput
 * ```
 *
 * So the guest's child gets its standard input on `/dev/null` — or a pipe the guest writes the
 * question into and closes — and never a pipe it holds open. **That is also the reason the
 * approval dialog in the page can never open, and [APPROVAL] is what to read about it.**
 *
 * ### [APPROVAL] — what was measured, and what the guest therefore does
 *
 * The page has an approval dialog and an `/api/approvals/{id}` route, and the app's own agent puts
 * every write to the user. The guest cannot do that, and the reason is not a design choice:
 *
 * | run | stdin | flag | what happened |
 * |---|---|---|---|
 * | 1 | closed | none | the `write` tool **ran** and the file was created; nothing on stdout named a question |
 * | 2 | closed | `--approval-mode always-ask` | the `write` tool **did not run**, exit 0, nothing on stderr — the question was asked somewhere and read the end of input as a decline |
 * | 3 | closed | `--approval-mode yolo` | the `write` tool ran and the file was created |
 *
 * So the question is real, it is asked, and **it arrives on a stream that is at end of input**,
 * because that is the only standard input a child of a PHP process can be given here: a pipe the
 * guest holds open makes `omp` wait before it starts, and a pipe that is closed makes every
 * question read `-1`. `--mode=rpc` — "Output mode: text (default), json, rpc, or rpc-ui", with
 * `--no-ui` described as "With --mode rpc: run extensions headless" — is the mechanism a host
 * protocol would offer, and **this build establishes nothing about its messages**, so it is not
 * used.
 *
 * **`--approval-mode write` is therefore what the guest passes, and it is the conservative end of
 * a measured choice.** It asks when a tool would change the filesystem and nothing else, so the
 * agent can read a conversation, and every write is declined by a stream that is at end of input.
 * The alternative — passing nothing, which run 1 shows would let a model write with nobody asked —
 * is the one behaviour this project's own README makes a point of not having, and a page that is
 * told a question is coming and is never asked is worse than a page that is never told. **The cost
 * is stated plainly: the guest's agent is read-only until a question can reach a person, and
 * `/api/approvals/{id}` answers every id with the app's own 404 sentence.** Changing that is not a
 * flag away; it is a host protocol away, and the flag that turns it off is not something this
 * build offers a user by accident.
 *
 * ### Exit status
 *
 * Measured, because no help text enumerates it: **`0` when the turn finished**, **`2` for a usage
 * error** (an unknown flag), **`1` for a runtime failure** (a `--cwd` that is not a directory, with
 * `Error: Cannot change working directory to …` on stderr). **That is three observations and not a
 * specification**: this build does not know what status 3 or 130 mean, and the guest does not guess
 * either — it reports a non-zero status as a refusal line on the stream, in the words the agent
 * itself wrote to stderr, and closes the stream properly.
 *
 * ### Where its state is
 *
 * `--session-dir` for the session file, and `PI_CODING_AGENT_DIR` — "Session storage directory
 * (default: ~/.omp/agent)", and what `omp config path` prints — for the configuration, the settings
 * and the caches. **The guest points both at the conversation's own `.omp/agent/`,** because a
 * conversation is a folder and that folder is the one the user can open in a file manager.
 *
 * ### [UPDATE] — what `omp update` is, and where the boot runs it
 *
 * Its own help, first line: **"Check for and install updates"**, with `-f, --force`, `-c, --check`
 * ("Check for updates without installing"), `-l, --plugins`, `--canary` and `--stable`, and the
 * `GITHUB_TOKEN`/`GH_TOKEN` note. [UPDATE_HELP] holds those lines as they were read, and
 * [omp.vm.guestapi.AgentUpdate] runs the command at every start of the app with **no flags at
 * all** — the user wrote `omp update && omp`, so the agent is to become current rather than to be
 * asked whether it is, and a flag this build cannot quote from a help text is a flag that silently
 * does nothing on a phone.
 *
 * **What was measured on `omp/18.3.5` for the flags this file takes from the main help, and what
 * was not:** a turn's flags are quoted from `omp --help` above and are not restated here; the
 * update's own path — a check that finds nothing, a refused connect, exit statuses, and a command
 * that asks nobody — is measured and written down in [omp.vm.guestapi.AgentUpdate], which is the
 * file that acts on it. What an update that *installs* prints has never been observed on this
 * machine, so nothing in this build reads such a line.
 */
object AgentCommand {

    /** The agent inside the Debian, which is the bind that puts the app's downloaded copy there. */
    const val BINARY = "/usr/local/bin/omp"

    /**
     * The flags every turn is run with, in the order the vector has them.
     *
     * `--cwd` and `--session-dir` take their values from [argv]; every other entry here is a fixed
     * token, and the two conditional flags are [CONTINUE] and [MODEL].
     */
    val FLAGS = listOf("-p", "--mode=json", "--approval-mode", "write")

    /** Added when the conversation's session directory already holds a session. */
    const val CONTINUE = "--continue"

    /** Added with its value when the guest's environment names a model. */
    const val MODEL = "--model"

    /** Where the guest keeps a conversation's session, configuration and caches. */
    const val SESSION_SUBDIR = ".omp/agent"

    /** The file name the session is stored under, in the shape `omp` writes it. */
    const val SESSION_SUFFIX = ".jsonl"

    /** The environment variable holding the conversation-independent state directory. */
    const val AGENT_DIR_ENV = "PI_CODING_AGENT_DIR"

    /** What [AGENT_DIR_ENV] is when nothing sets it, quoted from the help text. */
    const val DEFAULT_AGENT_DIR = "~/.omp/agent"

    // ---- the events the guest reads out of the JSON stream --------------------------------------

    /** One JSON object per line, and this is the one carrying a chunk of the answer. */
    const val EVENT_MESSAGE_UPDATE = "message_update"

    /** The key inside a `message_update` that holds what the model just did. */
    const val FIELD_ASSISTANT_EVENT = "assistantMessageEvent"

    /** Its `type`, on the events that carry the model's words as they arrive. */
    const val ASSISTANT_TEXT_DELTA = "text_delta"

    /** Its `type`, on the event that ends a run of `text_delta`s and carries the whole text. */
    const val ASSISTANT_TEXT_END = "text_end"

    /** A tool the agent is about to run. Its name is at `toolName`; **its `type` was not read here.** */
    const val EVENT_TOOL_START = "tool_execution_start"

    /** The turn's end, carrying the whole assistant message. */
    const val EVENT_TURN_END = "turn_end"

    // ---- statuses ------------------------------------------------------------------------------

    /** The turn finished. */
    const val EXIT_OK = 0

    /** A usage error: an unknown flag, a missing value. */
    const val EXIT_USAGE = 2

    /** A runtime failure, such as a working directory that is not a directory. */
    const val EXIT_FAILED = 1

    // ---- the help text these flags were taken from ---------------------------------------------

    /**
     * The lines of `omp --help` this file's decisions rest on, copied out of it.
     *
     * **A dated excerpt, and not a substitute for running the help.** It is here so that a flag
     * added to [FLAGS] without one to justify it fails a test rather than a phone, and so that a
     * reader can see the sentence each decision came from without a machine. It is 18.3.5's; the
     * guest's binary is a download whose version this build does not pin at the call site, so a
     * flag that changes meaning upstream is not detectable from here and is stated as unverified.
     */
    val HELP = listOf(
        "  MESSAGES   Messages to send (prefix files with @)",
        "      --model=<value>                   Model to use (fuzzy match: \"opus\", \"gpt-5.2\", or \"openai/gpt-5.2\")",
        "      --cwd=<value>                     Directory to start in (overrides the launch cwd)",
        "      --mode=<value>                    Output mode: text (default), json, rpc, or rpc-ui",
        "  -p, --print                           Non-interactive mode: process prompt and exit",
        "  -c, --continue                        Continue previous session",
        "      --session-dir=<value>             Directory for session storage and lookup",
        "      --no-session                      Don't save session (ephemeral)",
        "      --approval-mode=<value>           Override tools.approvalMode for this session (always-ask|write|yolo)",
        "  PI_CODING_AGENT_DIR        - Session storage directory (default: ~/.omp/agent)",
    )

    /**
     * The lines of `omp update --help` that [omp.vm.guestapi.AgentUpdate]'s decision rests on.
     *
     * **A separate list from [HELP] because it is a different command's help**, and a merge of the
     * two would let a flag of one justify itself with the other command's text. It is here for the
     * same reason [HELP] is: so that a flag added to the boot's `omp update` without a line to
     * justify it fails a test rather than a phone. **The boot passes none of them, and that is
     * pinned too** — the vector is one token — because a boot that silently stopped checking for an
     * update would be a feature that does nothing while looking as though it ran.
     *
     * Read on this machine from `omp/18.3.5` at `/home/genesis/.local/bin/omp`, whose own summary
     * line was `omp v18.3.5`. The `EXAMPLES` note is in the list because it is the only place the
     * help mentions `GITHUB_TOKEN`, and this build does not set it: a rate-limited phone would fail
     * the check, which is the [omp.vm.guestapi.UpdateOutcome.FAILED] path and is reported as one.
     */
    val UPDATE_HELP = listOf(
        "Check for and install updates",
        "  \$ omp update [FLAGS]",
        "  -f, --force    Force update",
        "  -c, --check    Check for updates without installing",
        "  -l, --plugins  Update installed plugins",
        "      --canary   Switch to the canary channel and update",
        "      --stable   Switch back to the stable channel",
        "  # If GitHub rate-limits release metadata, set GITHUB_TOKEN or GH_TOKEN",
    )

    /**
     * The whole vector for one turn, as the guest builds it.
     *
     * @param conversation the conversation folder, inside the guest. **Already checked by the
     *   caller**: the page's name has been resolved inside the container and refused outside it
     *   before this is asked for, because a `--cwd` is the one path in this vector that decides
     *   where a model is allowed to work.
     * @param continuing whether that folder already holds a session.
     * @param model the model to ask for, or null for the agent's own default.
     */
    fun argv(conversation: String, continuing: Boolean, model: String? = null): List<String> {
        val argv = ArrayList<String>(FLAGS.size + 8)
        argv += FLAGS
        argv += "--cwd"
        argv += conversation
        argv += "--session-dir"
        argv += sessionDir(conversation)
        if (continuing) argv += CONTINUE
        if (!model.isNullOrBlank()) argv += listOf(MODEL, model)
        return argv
    }

    /** Where a conversation's session, configuration and caches live: its own `.omp/agent/`. */
    fun sessionDir(conversation: String): String = "$conversation/$SESSION_SUBDIR"

    /**
     * Whether [conversation] already holds a session to continue.
     *
     * `-c` on a folder with no session in it is harmless — measured, it starts one — so this is a
     * statement about what the guest would find rather than a guard, and it is here so the answer
     * is one function instead of a `scandir` in three places.
     */
    fun hasSession(sessionDir: String, names: List<String>): Boolean =
        names.any { it.endsWith(SESSION_SUFFIX) }
}
