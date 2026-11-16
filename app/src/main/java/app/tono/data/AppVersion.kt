package app.tono.data

/** Dotted version numbers ("0.3.0", "v1.2"), compared part by part. */
object AppVersion {
    fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").substringBefore('-').split('.')
            .map { p -> p.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    /** Negative if [a] is older than [b], zero if the same, positive if newer. */
    fun compare(a: String, b: String): Int {
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    fun isNewer(candidate: String, current: String) = compare(candidate, current) > 0
}
