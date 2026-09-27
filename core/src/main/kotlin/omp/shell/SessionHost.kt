package omp.shell

/**
 * Who is in front of the terminal, for whoever owns it.
 *
 * Two REPLs share one [omp.term.Screen] and one [InputChannel] — the phone's shell, and the VM's
 * shell that `vm enter` runs on the same thread underneath it. Nothing in either session can see
 * the other, and the one thing that has to answer "which one is the user talking to" is the app: the
 * Back button cancels the foreground job of the session in front, and an `exit` typed in the VM
 * must return to the phone shell rather than closing the Activity.
 *
 * So the nested REPL reports itself in and out, and the host keeps the answer. It is deliberately
 * two methods and no stack: one-deep is the whole depth this shell can reach, because only `vm
 * enter` nests and it does not nest twice. A host that wanted more would hold a list here instead
 * and nothing else about this interface would change.
 */
interface SessionHost {

    /** A session has taken the terminal; it is now the one Back, Ctrl-C and the repaint mean. */
    fun sessionPushed(session: Session)

    /**
     * The session has given the terminal back. The host returns to whatever was in front, which
     * for a one-deep stack is the phone's own session — so this is also the signal that an `exit`
     * inside the VM is over and the app must not act on it.
     */
    fun sessionPopped(session: Session)
}
