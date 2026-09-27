package omp.vm.guestapi

import omp.shell.fs.FsException
import omp.shell.fs.Vfs
import omp.vm.provision.ProvisionPaths
import omp.vm.provision.mkdirs
import omp.vm.provision.WebOutcome
import omp.vm.provision.WebSource

/**
 * One installed file: where its bytes come from, and where in the Debian they go.
 *
 * @param source the name under `app/src/main/assets/guest/`, which is also the name under
 *   `assets/guest/` in the APK.
 * @param destination an **absolute path inside the guest**, because `/usr/local/share/omp` and
 *   `/etc/apache2/conf-available` are the two places the tree goes and neither is the document
 *   root. A relative destination is refused rather than joined onto anything.
 */
data class GuestFile(val source: String, val destination: String)

/**
 * What happened to the guest's PHP, and every line that says so.
 *
 * A report rather than an exception, for the reason [omp.vm.provision.WebReport] is one: the caller
 * is a provisioning screen, and a screen cannot render a stack trace.
 */
data class GuestApiReport(
    val outcome: WebOutcome,
    /** Where the PHP went, whatever became of it. */
    val apiDir: String,
    /** The Apache drop-in that makes the guest's Apache reach it, or null when it was refused. */
    val apacheConf: String?,
    val filesWritten: Int,
    val filesUnchanged: Int,
    val lines: List<String>,
)

/**
 * Puts the guest's own PHP into the Debian, next to the three pages and after the guest's `apt`.
 *
 * ### Where it goes, and why not into the document root
 *
 * | | |
 * |---|---|
 * | the PHP | `/usr/local/share/omp/` — **outside** `/var/www/html`, because a document root that can reach the code that reads the database is a document root that can be asked to do anything the code can do, and because a file under the document root is a file `curl` can fetch |
 * | the Apache drop-in | `/etc/apache2/conf-available/omp-guest.conf`, which `a2enconf omp-guest` links into `conf-enabled` |
 *
 * **The drop-in is not decoration and it is not optional.** `app.js` fetches `/api/…` by absolute
 * path and none of those paths is a file, so *something* has to turn a URL into a program. Debian's
 * own `000-default.conf` serves `/var/www/html` with `AllowOverride None`, which is exactly the
 * setting that makes an `.htaccess` rewrite read by nobody — the quietest way to ship a routing
 * rule that does nothing. One `FallbackResource` line in the server configuration is the smallest
 * thing that works, and `FallbackResource` is `mod_dir`, which is compiled in and enabled by
 * default: no module, no `a2enmod`, one directive. `FallbackResource` applies only to a request
 * that names no file, so `/`, `/app.css` and `/app.js` keep being served as themselves.
 *
 * **The whole of the drop-in's content is in this repository**,
 * `app/src/main/assets/guest/apache/omp-guest.conf.in`, with `@DOCUMENT_ROOT@` and `@API_INDEX@` in
 * it, and this class fills those two in from [ProvisionPaths]. A template and not a string here,
 * because a sentence an operator has to read in a terminal inside a guest is a sentence that will
 * be edited by hand eventually, and this is the one file where a hand edit is a routing change.
 *
 * ### The one ordering constraint, and it is the same one `WebRoot` has
 *
 * **This runs after the guest's own `apt`, never before it.** Two reasons and the second is the
 * one that bites:
 *
 * 1. `/etc/apache2/conf-available/` and `/usr/local/share/omp/` are created by the packages
 *    `omp.vm.provision.GuestPackages` installs, and `GuestPackages`'s third step enables
 *    `php8.4` — a package that does nothing until `a2enmod` has run. Writing the tree before that
 *    is writing into a Debian with no PHP in it;
 * 2. `GuestPackages` writes its mark only when the whole `apt` sequence exits zero, and it has to
 *    run *again* from the top after a failure. So a tree installed before a failed `apt` is a tree
 *    `dpkg` may overwrite — which is the same sentence [omp.vm.provision.WebRoot] writes about
 *    `index.html`, and the same rule.
 *
 * The sequence a caller runs is `GuestPackages.install`, then **this**, then
 * `GuestPackages.enableApi()`. The last one is after this because `a2enconf` on a file that is not
 * there exits non-zero, and a step that fails for that reason would make `apt` — 57 MB of a
 * download — run again on the next boot.
 *
 * ### What this class does not do
 *
 * It writes files. It does not start Apache, does not start MariaDB, does not check that PHP can
 * parse what it wrote, and does not make the drop-in live: `enableApi()` is a separate call, on the
 * other side of a file that has to exist first. **None of the PHP it installs has ever been run** —
 * this build has no PHP and no MySQL — and every file under `app/src/main/assets/guest/` says so in
 * its own first comment.
 */
class GuestApiTree(
    val paths: ProvisionPaths,
    private val source: WebSource,
    private val files: List<GuestFile> = FILES,
) {

    /**
     * Where [name] would be written inside the Debian, or null when it would not be.
     *
     * **The same three rules a tar member and a page name are held to** — no absolute name, no
     * `..`, nothing empty — and one more: a destination outside [ProvisionPaths.rootfsDir] is
     * refused, because a tree whose destination table could name the phone's own storage would be a
     * table that could write there.
     */
    fun destinationOf(file: GuestFile): String? {
        if (file.source.isEmpty() || file.source.startsWith("/")) return null
        if (file.destination.isEmpty() || !file.destination.startsWith("/")) return null
        // `..` is refused in **both** names, and the reason is a prefix rather than a prefix after
        // collapsing: `<rootfs>/../../../data/local/tmp/x` starts with `<rootfs>/` as a string and
        // is not inside it. A textual prefix check on an uncollapsed path is the whole of that bug,
        // so the only way out is to refuse the name outright.
        if (file.source.split('/').any { it == ".." }) return null
        // `trimStart('/')` and not `split('/')`: an absolute path starts with a separator, so
        // splitting it as it stands leaves an empty first element and refuses every path.
        if (file.destination.trimStart('/').split('/').any { it == ".." || it.isEmpty() }) return null
        val root = "${paths.rootfsDir}/"
        val out = "${paths.rootfsDir}${file.destination}"
        if (!out.startsWith(root)) return null
        return out
    }

    /**
     * The whole install: read every file, write the ones that differ, and say for each whether it
     * was written, was already the same, or was refused.
     *
     * **Everything is read and every name is resolved before a byte is written**, for the reason
     * [omp.vm.provision.WebRoot.install] gives: half a PHP tree is a guest whose Apache reaches an
     * `index.php` that `require`s a file that is not there, and that is a 500 on every request.
     */
    fun install(vfs: Vfs): GuestApiReport {
        val lines = ArrayList<String>()
        val planned = ArrayList<Planned>(files.size)
        for (file in files) {
            val destination = destinationOf(file)
                ?: return refuse(
                    lines,
                    "${file.source} would be written to '${file.destination}', which is not a path " +
                        "inside the Debian at ${paths.rootfsDir}, so nothing was written.",
                )
            val bytes = try {
                source.read(file.source)
            } catch (e: java.io.IOException) {
                null
            } ?: return refuse(
                lines,
                "guest/${file.source} is not in this build — the app's own source returned nothing " +
                    "for it — so nothing was written and the guest's Apache would reach whatever " +
                    "was there before.",
            )
            planned += Planned(file, destination, render(file, bytes))
        }
        if (!paths.state(vfs).rootfsInstalled) {
            return refuse(
                lines,
                "there is no unpacked Debian at ${paths.rootfsDir} " +
                    "(${ProvisionPaths.ROOTFS_MARKER} is not in it), so there is no Apache and no " +
                    "php8.4 to install the guest's API into. Nothing was written.",
            )
        }
        val written = ArrayList<String>()
        for (one in planned) {
            try {
                mkdirs(vfs, one.destination.substringBeforeLast('/'))
            } catch (e: FsException) {
                lines += "${one.destination.substringBeforeLast('/')} could not be created: " +
                    "${e.errno.text}. Nothing was written."
                return GuestApiReport(WebOutcome.FAILED, paths.guestApiDir, null, 0, 0, lines)
            }
        }
        var put = 0
        var same = 0
        for (one in planned) {
            val had = bytesAt(vfs, one.destination)
            if (had != null && had.contentEquals(one.bytes)) {
                same++
                lines += "${one.destination}: ${one.bytes.size} bytes, the same as the app's own " +
                    "guest/${one.file.source}, so it was not opened for writing."
                continue
            }
            try {
                vfs.openWrite(one.destination, false).use { out -> out.write(one.bytes) }
            } catch (e: FsException) {
                lines += "${one.destination}: ${e.errno.text}. The files written before it are still " +
                    "there; the report says which one stopped the run."
                return GuestApiReport(
                    WebOutcome.FAILED,
                    paths.guestApiDir,
                    null,
                    put,
                    same,
                    lines,
                )
            }
            put++
            written.add(one.destination)
            lines += if (had == null) {
                "${one.destination}: written, ${one.bytes.size} bytes. There was no file there."
            } else {
                "${one.destination}: written, ${one.bytes.size} bytes over a different copy " +
                    "(${had.size} bytes), which this step does not keep. A user who edited the " +
                    "guest's PHP inside the Debian loses the edit on the next install."
            }
        }
        lines += if (put == 0) {
            "nothing was written: all ${planned.size} file(s) are the same bytes the app ships, " +
                "and this step will write them again the moment they differ."
        } else {
            "the guest's API is $put file(s) in ${paths.guestApiDir} and $same already matching " +
                "the app's own copy."
        }
        lines += "no server was started by this step: it writes files, and ${paths.guestConfName} " +
            "is linked into Apache's own configuration by a separate step that runs after it. " +
            "Whether anything is listening is a question about a booted guest, which no step in " +
            "this layer decides."
        val conf = "${paths.rootfsDir}${paths.guestConf}"
        val outcome = if (put == 0) WebOutcome.ALREADY_INSTALLED else WebOutcome.INSTALLED
        return GuestApiReport(outcome, paths.guestApiDir, conf, put, same, lines)
    }

    /**
     * The bytes as they are written, with the two placeholders in the Apache drop-in filled in.
     *
     * **Only the drop-in is a template.** A `.php` file is copied byte for byte, because a `require`
     * of a file that was rendered differently from the one in the APK is a bug nobody can find.
     */
    private fun render(file: GuestFile, bytes: ByteArray): ByteArray {
        if (!file.source.endsWith(TEMPLATE_SUFFIX)) return bytes
        val text = String(bytes, Charsets.UTF_8)
            .replace(DOCUMENT_ROOT_TOKEN, ProvisionPaths.WEB_DOCROOT)
            .replace(API_INDEX_TOKEN, paths.guestApiDir + "/" + INDEX_NAME)
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun refuse(lines: MutableList<String>, why: String): GuestApiReport {
        lines += why
        return GuestApiReport(WebOutcome.REFUSED, paths.guestApiDir, null, 0, 0, lines)
    }

    private fun bytesAt(vfs: Vfs, path: String): ByteArray? = try {
        vfs.readBytes(path)
    } catch (e: FsException) {
        null
    }

    private class Planned(val file: GuestFile, val destination: String, val bytes: ByteArray)

    companion object {

        /** The name the front controller has inside the Debian, which is what FallbackResource names. */
        const val INDEX_NAME = "index.php"

        /** The only file in this tree that is rendered rather than copied. */
        const val TEMPLATE_SUFFIX = ".conf.in"

        /** Replaced with [ProvisionPaths.WEB_DOCROOT]. */
        const val DOCUMENT_ROOT_TOKEN = "@DOCUMENT_ROOT@"

        /** Replaced with the guest's own API directory and [INDEX_NAME]. */
        const val API_INDEX_TOKEN = "@API_INDEX@"

        /**

         * The whole guest-side tree, and where each file goes inside the Debian.
         *
         * Public and pure so that what would be written is readable and testable on a JVM with no
         * guest: the test pins every destination, and the destinations are the deliverable — the
         * writing is [install]'s half and needs a filesystem.
         */
        val FILES: List<GuestFile> = listOf(
            GuestFile("api/index.php", "$API_SUBDIR/index.php"),
            GuestFile("api/Reply.php", "$API_SUBDIR/Reply.php"),
            GuestFile("api/Paths.php", "$API_SUBDIR/Paths.php"),
            GuestFile("api/Db.php", "$API_SUBDIR/Db.php"),
            GuestFile("api/Sse.php", "$API_SUBDIR/Sse.php"),
            GuestFile("api/Agent.php", "$API_SUBDIR/Agent.php"),
            GuestFile("api/Routes.php", "$API_SUBDIR/Routes.php"),
            GuestFile("api/schema.sql", "$API_SUBDIR/schema.sql"),
            GuestFile("apache/omp-guest.conf.in", "/etc/apache2/conf-available/omp-guest.conf"),
        )

        /** Where the PHP goes, named once because [FILES] and the drop-in both need it. */
        const val API_SUBDIR = ProvisionPaths.GUEST_API_DIR
    }
}

