package omp.vm.workspace

import omp.shell.cmd.Cmds
import omp.shell.fs.FsErrno
import omp.shell.fs.FsException

/**
 * The one rendering of a conversation list, so the command and anything that comes after it print the
 * same table and say the same things about the same folders.
 *
 * It is a function and not a format string because half of what it has to say is not the listing at
 * all: an empty root is not a table with no rows, a root that is not there is not an empty root, and
 * a folder whose metadata cannot be read is not a folder the user made. Each of those has its own
 * line, and a caller that had to invent them would invent them differently.
 *
 * Every row leads with the **directory** name, because the directory is the name — a folder renamed
 * in the Android file manager shows its new name here, and the name the app gave it appears once,
 * next to the row, as history. A stale name in a metadata file is never quietly preferred over what
 * is on disk, and never quietly dropped either.
 */
object WorkspaceList {

    /**
     * The listing, as lines without a trailing newline, ready for a command's `outLine` per line.
     *
     * @throws FsException for anything other than a missing root: this renders what [Workspace.list]
     * found, and a failure to read the root is the caller's error to report, not a table's. A root
     * that is *unreadable* is not a missing root and must not be printed as one — on a phone that is
     * the difference between "make a conversation" and "grant storage access".
     */
    fun render(workspace: Workspace): List<String> {
        // The parenthetical exists to say "these are two names for one folder", and on the phone
        // they are one name: `root` and `hostRoot` are the same string, so printing it would name
        // the same directory twice on every single listing a device user sees. Inside the namespace
        // they differ and the parenthetical is the whole point, so it is compared once here rather
        // than in each of the three messages below.
        val inVm = if (workspace.root == workspace.hostRoot) "" else "  (${workspace.root} in the VM)"
        val entries = try {
            workspace.list()
        } catch (e: FsException) {
            // Only a root that genuinely is not there gets this line. Every other errno is the
            // caller's to report, and reporting it as a missing folder would send the user to
            // create a conversation that fails the same way.
            if (e.errno != FsErrno.NO_SUCH_FILE) throw e
            // The namespace path first, because that is the half the caller asked about, and the
            // phone's only when it is a different path: a missing root is the one message where a
            // user is about to go looking for a folder, and on the phone the two names are one and
            // naming it twice would be the duplication this line exists to avoid.
            val onPhone = if (workspace.root == workspace.hostRoot) "" else
                " (on the phone that is ${workspace.hostRoot})"
            return listOf(
                "no conversations: ${workspace.root} does not exist$onPhone; " +
                    "the folder is made with the first one",
            )
        }
        if (entries.isEmpty()) {
            return listOf("no conversations yet in ${workspace.hostRoot}$inVm")
        }
        val nameWidth = entries.maxOf { columns(it.name) }.coerceAtLeast(COLUMN_NAME)
        val numberWidth = entries.size.toString().length
        val out = ArrayList<String>(entries.size + 2)
        out += "conversations in ${workspace.hostRoot}$inVm:"
        out += header(numberWidth, nameWidth)
        for ((i, entry) in entries.withIndex()) {
            val index = (i + 1).toString().padStart(numberWidth)
            out += "  $index  ${pad(entry.name, nameWidth)}  ${Cmds.timestamp(entry.modifiedMillis)}  " +
                entry.hostPath + notes(entry, workspace)
        }
        return out
    }

    /**
     * The column labels. A listing whose header is a single unlabelled line cannot tell the user
     * which of the two times in this class is on screen, and a table of projects is a thing people
     * ask "when did I start this?" about.
     */
    private fun header(numberWidth: Int, nameWidth: Int): String =
        "  ${"#".padStart(numberWidth)}  ${pad("name", nameWidth)}  ${"modified".padEnd(COLUMN_TIME)}  where"

    /**
     * What is odd about this row, if anything. Said once, in the row, and never instead of the
     * directory's own name.
     */
    private fun notes(entry: Entry, workspace: Workspace): String = when (entry.meta) {
        // Short, because a row already carries a full host path and this is an 80-column screen.
        MetadataState.NONE -> "  (not made by omp)"
        MetadataState.CORRUPT -> "  (.omp-workspace is not in this app's format)"
        // A conversation omp made, whose file something else has locked or damaged. Saying "not
        // made by omp" here would be a positive lie about the user's own folder.
        MetadataState.UNREADABLE -> "  (.omp-workspace could not be read)"
        // Read here rather than carried on the entry: the recorded name is a historical fact about
        // one file, and only a mismatch is worth saying.
        MetadataState.RECORDED -> {
            val recorded = workspace.metadata(entry)[Workspace.NAME] ?: ""
            if (recorded.isEmpty() || recorded == entry.name) "" else "  (renamed from \"$recorded\")"
        }
    }

    /**
     * How many terminal columns [text] takes. `String.length` counts UTF-16 units, so a name made of
     * astral characters is padded as if it were twice as wide as it prints and the whole table to
     * its right steps out of line.
     */
    private fun columns(text: String): Int = text.codePointCount(0, text.length)

    private fun pad(text: String, width: Int): String =
        if (columns(text) >= width) text else text + " ".repeat(width - columns(text))

    private const val COLUMN_NAME = 4
    private const val COLUMN_TIME = 16
}
