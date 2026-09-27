package omp.vm.doctor

import omp.shell.fs.FsException
import omp.shell.fs.Vfs
import omp.vm.provision.WebRoot
import omp.vm.provision.WebSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * This build's own three chat pages, read out of the APK the package manager installed.
 *
 * ### Why the APK and not an `AssetManager`
 *
 * **The fact the doctor has to report is whether the guest's document root holds *this build's*
 * bytes, and the only copy of those bytes in `:core` is the installed package.** `:app` reads them
 * out of an `AssetManager`; this class reads the same three entries out of the same file, through
 * the same [Vfs] seam every other path in the shell goes through, because `:core` has no
 * `android.*` in it and an asset manager is one. A file's content is a file's content whichever
 * end of the seam read it, and the APK is the artefact that was actually installed.
 *
 * The path comes from [omp.shell.PlatformServices.packagePaths] rather than from a constant,
 * because the directory an app is installed into is chosen by the package manager, per install, and
 * a path built out of a package name is a path that is wrong on the next release of Android.
 *
 * ### What it costs, stated because it is not free
 *
 * A stream cannot seek, so finding `assets/web/index.html` means reading every entry before it and
 * discarding it: `AndroidManifest.xml`, `classes.dex`, `resources.arsc` and the `res/` tree are
 * decompressed and thrown away first. That is a few seconds on a slow phone and a few hundred
 * milliseconds on a fast one, which is why the read happens **once**, lazily, and only when the
 * report actually has a document root to compare — and why the pass stops as soon as all three
 * files are in hand. There is no cheaper honest way to ask the question: `ZipFile` would seek, and
 * it takes a `java.io.File`, which is exactly the thing this project does not let a class in
 * `:core` reach around the seam with.
 *
 * ### What it refuses
 *
 * | | |
 * |---|---|
 * | names nothing | [refusal] is why — the platform named no installed package, the file could not be opened, or it is not an archive |
 * | answers null | this build has no `web/<name>`; the caller says so rather than reporting a mismatch |
 * | never writes | and never leaves the device: this reads one file the app already has |
 */
class BuildPages private constructor(
    /** Why the pages could not be read at all, or null when they were. */
    val refusal: String?,
    private val bytes: Map<String, ByteArray>,
) : WebSource {

    override fun read(name: String): ByteArray? = bytes[name]

    /** True when the APK was opened and none of the three files was in it. */
    val empty: Boolean get() = refusal == null && bytes.isEmpty()

    companion object {

        /** The directory inside the APK the pages are in, and the one `ChatApi` is given. */
        const val ASSET_DIR = "assets/web"

        /**
         * The three pages out of [apk], or the reason they are not here.
         *
         * @param apk the installed package's own path, or null when the platform named none — a
         *   real answer on a device that has not finished installing this build, and the reason the
         *   caller reports rather than a silent empty.
         */
        fun of(vfs: Vfs, apk: String?): BuildPages {
            if (apk.isNullOrEmpty()) {
                return BuildPages("the platform has not named an installed package for this app", emptyMap())
            }
            val wanted = WebRoot.FILES.map { "$ASSET_DIR/$it" }.toSet()
            val found = LinkedHashMap<String, ByteArray>()
            val opened = try {
                ZipInputStream(vfs.openRead(apk))
            } catch (e: FsException) {
                return BuildPages("$apk could not be opened: ${e.errno.text}", emptyMap())
            } catch (e: ZipException) {
                return BuildPages("$apk could not be read as an archive: ${e.message}", emptyMap())
            }
            try {
                opened.use { zip ->
                    while (found.size < wanted.size) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        if (name in wanted) found[name] = zip.readAtMost(MAX_PAGE_BYTES)
                    }
                }
            } catch (e: IOException) {
                // A truncated APK: whatever was read before the failure is kept and the reason is
                // reported, because "two of the three match" is still worth a user's time and a
                // thrown exception is worth none of it.
                return BuildPages("$apk could not be read to the end: ${e.message}", found)
            }
            val pages = LinkedHashMap<String, ByteArray>()
            for (file in WebRoot.FILES) found["$ASSET_DIR/$file"]?.let { pages[file] = it }
            return BuildPages(null, pages)
        }

        /**
         * A page bigger than this is not a page.
         *
         * The three files in `app/src/main/assets/web/` are tens of kilobytes between them. A cap
         * keeps a hostile or corrupt entry from being read into memory in full, and it is a cap on
         * a file this app wrote, not on anything a user supplied.
         */
        const val MAX_PAGE_BYTES = 4L * 1024 * 1024

        /** One entry's bytes, or as many of them as the cap allows before the stream is dropped. */
        private fun ZipInputStream.readAtMost(cap: Long): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var held = 0L
            while (true) {
                val read = read(buffer)
                if (read < 0) break
                val keep = minOf(read.toLong(), cap - held).toInt()
                if (keep <= 0) break
                out.write(buffer, 0, keep)
                held += keep
                if (held >= cap) break
            }
            return out.toByteArray()
        }
    }
}
