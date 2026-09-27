package omp.shell.parser

import omp.shell.fs.FsException
import omp.shell.fs.VEntry
import omp.shell.fs.VNodeType
import omp.shell.fs.Vfs

/**
 * Pathname expansion. `*`, `?`, `[...]` with ranges, and `**` which is recursive only when it is a
 * whole path segment. A pattern that matches nothing is left literal, which is bash behaviour.
 */
object Glob {

    fun hasMagic(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '*', '?' -> return true
                '[' -> if (i + 1 < text.length && text[i + 1] != ']') return true
            }
            i++
        }
        return false
    }

    /**
     * @return sorted matches, or null when [pattern] holds no magic. An empty list means the
     *   pattern had magic but matched nothing, and the caller keeps the pattern literal.
     */
    fun expand(vfs: Vfs, pattern: String, cwd: String): List<String>? {
        if (!hasMagic(pattern)) return null
        val absolute = pattern.startsWith("/")
        val segs = pattern.split('/').filter { it.isNotEmpty() }
        val base = if (absolute) "/" else cwd
        val results = ArrayList<String>()
        walk(vfs, base, segs, 0, results)
        results.sort()
        return results
    }

    private fun walk(vfs: Vfs, dir: String, segs: List<String>, index: Int, out: ArrayList<String>) {
        if (index == segs.size) {
            out += dir
            return
        }
        val seg = segs[index]
        if (seg == "**") {
            // Zero directories, then any depth.
            walk(vfs, dir, segs, index + 1, out)
            for (d in listDir(vfs, dir) ?: emptyList()) {
                if (isDirectory(vfs, d, dir)) walk(vfs, child(dir, d.name), segs, index, out)
            }
            return
        }
        val entries = listDir(vfs, dir) ?: return
        for (e in entries) {
            if (!matchSegment(seg, e.name)) continue
            val next = child(dir, e.name)
            val last = index == segs.size - 1
            if (last) {
                out += next
            } else if (isDirectory(vfs, e, dir)) {
                walk(vfs, next, segs, index + 1, out)
            }
        }
    }

    /** A [VEntry] appended to the directory it was listed in, the way a path spells a child. */
    private fun child(dir: String, name: String): String = dir.trimEnd('/') + "/" + name

    /** The entries in [dir], or null when it may not be listed, which ends the walk. */
    private fun listDir(vfs: Vfs, dir: String): List<VEntry>? = try {
        vfs.readDir(dir)
    } catch (e: FsException) {
        null
    }

    /** A stat is taken without following a link, and `**` walks what `java.io` called one. */
    private fun isDirectory(vfs: Vfs, entry: VEntry, dir: String): Boolean = when (entry.stat.type) {
        VNodeType.DIRECTORY -> true
        VNodeType.SYMLINK -> try {
            vfs.stat(vfs.realpath(child(dir, entry.name))).type == VNodeType.DIRECTORY
        } catch (e: FsException) {
            false
        }
        else -> false
    }

    /** Segment matcher for `*`, `?`, `[...]`; a leading `.` must be matched explicitly. */
    fun matchSegment(pattern: String, name: String): Boolean {
        if (name.startsWith(".") && !pattern.startsWith(".")) return false
        return match(pattern, 0, name, 0)
    }

    private fun match(p: String, pi: Int, s: String, si: Int): Boolean {
        var i = pi
        var j = si
        while (i < p.length) {
            val c = p[i]
            when (c) {
                '*' -> {
                    var k = i
                    while (k < p.length && p[k] == '*') k++
                    if (k == p.length) return true
                    for (m in j..s.length) {
                        if (match(p, k, s, m)) return true
                    }
                    return false
                }
                '?' -> {
                    if (j >= s.length) return false
                    if (j == 0 && s[0] == '.') return false
                    i++
                    j++
                }
                '[' -> {
                    if (j >= s.length) return false
                    if (j == 0 && s[0] == '.') return false
                    val end = closingBracket(p, i)
                    if (end < 0) {
                        if (s[j] != '[') return false
                        i++
                        j++
                        continue
                    }
                    if (!matchClass(p.substring(i + 1, end), s[j])) return false
                    i = end + 1
                    j++
                }
                '\\' -> {
                    if (i + 1 >= p.length) {
                        if (j >= s.length || s[j] != '\\') return false
                        i++
                        j++
                    } else {
                        if (j >= s.length || s[j] != p[i + 1]) return false
                        i += 2
                        j++
                    }
                }
                else -> {
                    if (j >= s.length || s[j] != c) return false
                    i++
                    j++
                }
            }
        }
        return j == s.length
    }

    private fun closingBracket(p: String, open: Int): Int {
        var i = open + 1
        if (i < p.length && (p[i] == '!' || p[i] == '^')) i++
        if (i < p.length && p[i] == ']') i++
        while (i < p.length) {
            if (p[i] == ']') return i
            i++
        }
        return -1
    }

    private fun matchClass(body: String, c: Char): Boolean {
        var i = 0
        var negate = false
        if (body.isNotEmpty() && (body[0] == '!' || body[0] == '^')) {
            negate = true
            i = 1
        }
        var hit = false
        while (i < body.length) {
            if (i + 2 < body.length && body[i + 1] == '-' && body[i + 2] != ']') {
                if (c in body[i]..body[i + 2]) hit = true
                i += 3
            } else {
                if (c == body[i]) hit = true
                i++
            }
        }
        return hit != negate
    }

    /** Whole-string match of a shell pattern, for `${VAR#pat}`; unlike a path segment this may span `/`. */
    fun matchWhole(pattern: String, text: String): Boolean {
        if (pattern.contains('/')) return pattern == text
        return match(pattern, 0, text, 0)
    }
}
