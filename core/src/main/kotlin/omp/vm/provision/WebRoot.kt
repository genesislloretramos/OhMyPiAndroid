package omp.vm.provision

import omp.shell.fs.FsException
import omp.shell.fs.Vfs
import java.io.IOException

/**
 * The bytes of the app's three pages, from wherever this build keeps them.
 *
 * An interface and not an `android.content.res.AssetManager`, for the reason every other seam in
 * this package is an interface: `:core` has no `android.*` in it, and the thing that has to be
 * checked here — that the guest's copy and the app's copy are the same bytes — is a question about
 * a directory of files, not about an APK.
 *
 * **There is one copy of the chat UI in this project and this seam is it.** The app serves `/`,
 * `/app.css` and `/app.js` out of `app/src/main/assets/web/`, and the caller hands this class a
 * source reading that same directory, so the guest's document root is not a second copy somebody
 * has to remember to update: it is those three files, read once, written into the Debian. The test
 * that says so is [WebRootTest]'s — it reads both sides off this disk and fails when they differ,
 * which is the only form of "they are the same" that means anything.
 */
fun interface WebSource {
    /** The bytes of `web/<name>` — one of [WebRoot.FILES] — or null when this build has none. */
    fun read(name: String): ByteArray?
}

/** What a [WebRoot.install] ended up doing. */
enum class WebOutcome {
    /** Bytes were written: the document root had no copy, or a different one. */
    INSTALLED,

    /** Every file was already the app's own bytes. Nothing was opened for writing. */
    ALREADY_INSTALLED,

    /** Refused before a byte: the Debian is not here, or a name would leave the document root. */
    REFUSED,

    /** Stopped on a failure. Nothing is half-installed, and the report says which file. */
    FAILED,
}

/**
 * What happened to the guest's document root, and every line that says so.
 *
 * A report rather than an exception for the reason [ProvisionReport] is one: the caller is a
 * provisioning screen, and a screen cannot render a stack trace.
 */
data class WebReport(
    val outcome: WebOutcome,
    /** The directory inside the Debian that was installed into, whatever became of it. */
    val documentRoot: String,
    val filesWritten: Int,
    val filesUnchanged: Int,
    val lines: List<String>,
)

/**
 * Puts this app's three pages where the Apache inside the Debian can serve them, at provisioning
 * time, and nothing else.
 *
 * ### The document root, and why it is `/var/www/html` and not another path
 *
 * A Debian netboot rootfs is a minimal image: unpacked, it has no `apache2`, no `/var/www` and no
 * configuration this layer could have predicted. So the path is chosen by the **packaged
 * `apache2` package's own default**, `/etc/apache2/sites-enabled/000-default.conf`'s
 * `DocumentRoot /var/www/html`, and it is the only choice that works with **no configuration at
 * all**:
 *
 * - **Not a subdirectory of it.** The page's own URLs are absolute — `index.html` fetches
 *   `/app.css`, `/app.js`, `/login` and `/api/…` — and it has no `<base>` element and this app does
 *   not add one. A document root served at any path other than `/` breaks every one of them, and
 *   nothing on that page can be changed from here.
 * - **Not `/srv/www`, which is what a tutorial says.** Debian publishes no `/srv/www`, ships no
 *   vhost for it, and serving it means writing a `sites-available` file and running `a2ensite`
 *   inside a guest that has never been booted. That is a claim about a machine nobody has.
 * - **Not the app's own files directory, bound in.** Apache would serve it, and it holds the model
 *   key. A document root that can reach a credential is a document root with a credential inside
 *   it.
 *
 * ### What it does, and what it refuses
 *
 * | | |
 * |---|---|
 * | refuses | when the Debian is not unpacked yet — this does not fabricate a rootfs — and when a name would leave the document root, by the same rule [TarEntry.relativeName] applies to a tar member |
 * | overwrites | a file whose bytes differ, and **says which file and why it was different** |
 * | no-ops | a file whose bytes already match: the file is not opened for writing at all, so even its mtime is left alone |
 * | sets no mode | there is no `chmod` in here. A file it creates carries the umask's bits, a file a user has made in the guest keeps the bits it has, and what is guaranteed is only that nothing here is made executable — these are three static pages with nothing to run |
 * | never downloads | the bytes are read from the app's own assets; there is no second source and no cache of its own |
 *
 * ### The one ordering constraint, and why it is not optional
 *
 * **This runs after the guest's own `apt`, never before it.** Debian's `apache2` package ships its
 * own `/var/www/html/index.html`, and `dpkg` overwrites a path a package owns without asking — so
 * a UI written into the document root first is Debian's placeholder a minute later. That is also
 * why the "already the same" answer is worth having rather than being an optimisation: the second
 * install after an `apt` is the one that finds `index.html` different, puts the app's page back,
 * and names it in the report.
 *
 * **What is not verified here.** That Apache in this guest ever answers on this document root, and
 * that the WebView can reach it, are both questions about a booted proot guest; this class writes
 * files into a directory. The port Apache is to answer on is the app's decision and is not in this
 * build. A user who edits the page inside the guest loses the edit on the next install, and the
 * report is what tells them so.
 *
 * @param files the pages to install. A parameter rather than a constant so the refusal below is
 *   reachable from a test; a build that served one page instead of three is not a thing this app
 *   has.
 */
class WebRoot(
    val paths: ProvisionPaths,
    private val source: WebSource,
    private val files: List<String> = FILES,
) {

    /**
     * Where [name] would be written, inside the document root, or null when it would not be.
     *
     * The same three rules a tar member is held to — no absolute name, no `..`, nothing empty —
     * because the failure is the same failure: a name that resolves outside the directory it was
     * resolved against, and a `Provisioner` that unpacked one of those would have written a
     * member of the archive into the app's own storage.
     */
    fun destinationOf(name: String): String? {
        if (name.isEmpty() || name.startsWith("/")) return null
        val parts = name.split('/')
        if (parts.any { it == ".." }) return null
        val relative = parts.filter { it.isNotEmpty() }.joinToString("/")
        return if (relative.isEmpty()) null else "${paths.webRoot}/$relative"
    }

    /**
     * The whole install: read the app's three pages, put them where the guest's Apache looks, and
     * say for each one whether it was written, was already the same, or was refused.
     *
     * **Everything is read and every name is resolved before a byte is written.** Half a page set
     * is a document root that serves an `index.html` with no stylesheet, and a provisioning step
     * that stops there has made the guest's UI worse than it was, so a missing source file is a
     * refusal with nothing written rather than two of three.
     */
    fun install(vfs: Vfs): WebReport {
        val lines = ArrayList<String>()
        val root = paths.webRoot
        val planned = ArrayList<Page>(files.size)
        for (name in files) {
            val path = destinationOf(name)
                ?: return refuse(
                    lines,
                    "$name is not a name inside $root, so nothing was written. A page that " +
                        "resolves outside the document root is a page somewhere else in the Debian.",
                )
            val bytes = try {
                source.read(name)
            } catch (e: IOException) {
                null
            } ?: return refuse(
                lines,
                "web/$name is not in this build — the app's own source returned nothing for it — " +
                    "so nothing was written and $root is as it was.",
            )
            planned += Page(name, path, bytes)
        }
        if (!paths.state(vfs).rootfsInstalled) {
            return refuse(
                lines,
                "there is no unpacked Debian at ${paths.rootfsDir} (${ProvisionPaths.ROOTFS_MARKER} " +
                    "is not in it), so there is no Apache and no document root to install into. " +
                    "Nothing was written.",
            )
        }
        try {
            mkdirs(vfs, root)
        } catch (e: FsException) {
            lines += "$root could not be created: ${e.errno.text}. Nothing was written."
            return WebReport(WebOutcome.FAILED, root, 0, 0, lines)
        }

        var written = 0
        var same = 0
        for (page in planned) {
            val had = bytesAt(vfs, page.path)
            if (had != null && had.contentEquals(page.bytes)) {
                same++
                lines += "${page.path}: ${page.bytes.size} bytes, the same as the app's own " +
                    "web/${page.name}, so it was not opened for writing."
                continue
            }
            try {
                vfs.openWrite(page.path, false).use { out -> out.write(page.bytes) }
            } catch (e: FsException) {
                lines += "${page.path}: ${e.errno.text}. The pages written before it are still " +
                    "there; the report says which one stopped the run."
                return WebReport(WebOutcome.FAILED, root, written, same, lines)
            }
            written++
            lines += if (had == null) {
                "${page.path}: written, ${page.bytes.size} bytes. There was no file there."
            } else {
                "${page.path}: written, ${page.bytes.size} bytes over a different copy " +
                    "(${had.size} bytes), which this step does not keep. A user who edited the page " +
                    "inside the guest loses the edit, and Debian's own apache2 package ships an " +
                    "index.html here, so any apt after the first one lands in this branch too."
            }
        }
        lines += if (written == 0) {
            "nothing was written: all ${planned.size} page(s) in $root are the same bytes the app " +
                "serves, and this step will write them again the moment they differ."
        } else {
            "the document root at $root now holds $written page(s) written and $same already " +
                "matching the app's own copy."
        }
        val outcome = if (written == 0) WebOutcome.ALREADY_INSTALLED else WebOutcome.INSTALLED
        return WebReport(outcome, root, written, same, lines)
    }

    private fun refuse(lines: MutableList<String>, why: String): WebReport {
        lines += why
        return WebReport(WebOutcome.REFUSED, paths.webRoot, 0, 0, lines)
    }

    /** What is at [path] right now, or null when there is nothing there to compare against. */
    private fun bytesAt(vfs: Vfs, path: String): ByteArray? = try {
        vfs.readBytes(path)
    } catch (e: FsException) {
        null
    }

    private class Page(val name: String, val path: String, val bytes: ByteArray)

    companion object {
        /**
         * The three files, and the whole list the chat UI is.
         *
         * The app's server serves exactly these at `/`, `/app.css` and `/app.js`; the guest's
         * Apache serves the same three out of [ProvisionPaths.WEB_DOCROOT]. A page added to the
         * app's assets without being added here is a page the guest cannot serve, and
         * [WebRootTest] is what notices.
         */
        val FILES = listOf("index.html", "app.css", "app.js")
    }
}
