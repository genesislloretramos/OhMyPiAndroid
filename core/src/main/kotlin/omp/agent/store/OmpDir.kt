package omp.agent.store

import omp.shell.fs.FsErrno
import omp.shell.fs.FsException
import omp.shell.fs.Vfs

/**
 * The app's bookkeeping inside a conversation folder: one hidden directory, and the names in it.
 *
 * A conversation folder is a project — the user opens it in the file manager, syncs it, and hands
 * it to another app — so anything this app keeps about the conversation belongs in one visibly
 * separate place inside it rather than loose among the project files. [META_DIR] is that place, and
 * it is a **dot** directory: `tree` without `-a`, every glob in the shell's matcher, and the
 * Android file manager's own default all leave dot names out, so a project listing shows the
 * project.
 *
 * The two files in it have separate owners and no shared code beyond the names below: the
 * transcript is append-only and rewritten never, the state file is rewritten whole and read whole.
 * Keeping the names here is what stops the two from drifting into `.omp/state` and `.ompx/state`
 * and leaving half the agent's state somewhere nothing reads it.
 */
internal const val META_DIR = ".omp"

/** The transcript: JSON Lines, one object per line, appended and never rewritten. */
internal const val TRANSCRIPT = "transcript.jsonl"

/** The per-conversation configuration, written whole through [TRANSCRIPT_SCRATCH]-and-rename. */
internal const val STATE = "state.json"

/** Where the next state file is written before it is renamed over [STATE]. */
internal const val STATE_SCRATCH = "state.json.tmp"

/**
 * `mkdir -p` over the [Vfs], which has only a one-level [Vfs.mkdir].
 *
 * A second copy of the private helper in `omp.vm.workspace.Workspace`: that one is private to a
 * class, and the alternative — making it public, or reaching into the VM's package from the agent's
 * — buys nothing. The two are the same four lines because `mkdir -p` is the same operation, and a
 * `mkdir` that already exists is the one answer that is not a failure.
 */
internal fun Vfs.ensureDir(path: String) {
    val at = StringBuilder()
    for (part in path.split('/')) {
        if (part.isEmpty()) continue
        at.append('/').append(part)
        try {
            mkdir(at.toString())
        } catch (e: FsException) {
            if (e.errno != FsErrno.FILE_EXISTS) throw e
        }
    }
}

/** [Vfs.stat]'s size, or 0 when the file is not there yet — which is what an empty one means. */
internal fun Vfs.sizeOf(path: String): Long = try {
    stat(path).size
} catch (e: FsException) {
    if (e.errno != FsErrno.NO_SUCH_FILE) throw e
    0L
}
