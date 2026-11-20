package app.fayaz.otgmaster.feedback

/**
 * File and folder names in a feedback report, masked (#165).
 *
 * Backup, restore and verify errors name the file they failed on, and the log keeps
 * those lines. The in-app log should show the names, since that is how the user finds
 * the file. The report should not: it goes to a public issue. So the app marks each
 * path where it logs it ([mark]), and the report masks what is between the marks
 * ([maskMarked]). Masking by pattern instead would also hit lines like "MB/s" or
 * "/dev/bus/usb/002/005", which are not private and are needed to read a report.
 *
 * The marks are U+2068 FIRST STRONG ISOLATE and U+2069 POP DIRECTIONAL ISOLATE.
 * They are invisible, and they are the right Unicode for embedding user text in a line.
 */
object PathMask {
    private const val START = '⁨'
    private const val END = '⁩'

    /** [path] marked for masking in a report; it shows unchanged in the app. */
    fun mark(path: String): String = "$START$path$END"

    /** [line] with each marked path masked and the marks removed. */
    fun maskMarked(line: String): String {
        if (START !in line && END !in line) return line
        val out = StringBuilder()
        var i = 0
        while (i < line.length) {
            val s = line.indexOf(START, i)
            if (s < 0) { out.append(line, i, line.length); break }
            val e = line.indexOf(END, s + 1)
            out.append(line, i, s)
            // An unclosed mark (a line cut short) masks to the end rather than leaking the rest.
            val stop = if (e < 0) line.length else e
            out.append(mask(line.substring(s + 1, stop)))
            i = if (e < 0) line.length else e + 1
        }
        return out.toString().replace(END.toString(), "")
    }

    /**
     * Each name in [path] as its first character, `***` and its last three: so
     * `Holidays/IMG_2024.jpg` is `H***ays/I***jpg`. The last three usually
     * hold a file's extension, which is what a report needs. A name of six characters
     * or fewer keeps only its first, since first and last three would show most of it.
     */
    fun mask(path: String): String = path.split('/').joinToString("/") { name ->
        when {
            name.isEmpty() -> name
            name.length <= 6 -> "${name.first()}***"
            else -> "${name.first()}***${name.takeLast(3)}"
        }
    }
}
