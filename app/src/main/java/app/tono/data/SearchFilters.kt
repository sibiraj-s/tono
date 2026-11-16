package app.tono.data

enum class LengthFilter(val label: String, val minSec: Long, val maxSec: Long) {
    Any("Any", 0, Long.MAX_VALUE),
    UnderFour("Under 4 min", 0, 4 * 60 - 1),
    FourToTwenty("4–20 min", 4 * 60, 20 * 60),
    OverTwenty("Over 20 min", 20 * 60 + 1, Long.MAX_VALUE),
}

enum class UploadedFilter(val label: String, val maxAgeSec: Long) {
    AnyTime("Any time", Long.MAX_VALUE),
    Week("This week", 7L * 86_400),
    Month("This month", 31L * 86_400),
    Year("This year", 366L * 86_400),
}

/** Steers search results toward a language by adding it to the query (YouTube has no language filter). */
enum class SearchLanguage(val label: String) {
    Any("Any"), Tamil("Tamil"), Telugu("Telugu"), Hindi("Hindi"), Malayalam("Malayalam"), Kannada("Kannada"), English("English"),
}

/** The query actually sent to YouTube: "ala bolelo" + Tamil -> "ala bolelo tamil" (unless already typed). */
fun searchQuery(typed: String, f: SearchFilters): String {
    val q = typed.trim()
    if (f.language == SearchLanguage.Any) return q
    val word = f.language.label.lowercase()
    return if (Regex("""\b$word\b""", RegexOption.IGNORE_CASE).containsMatchIn(q)) q else "$q $word"
}

enum class SortOrder(val label: String) { Relevance("Relevance"), MostViewed("Most viewed"), Newest("Newest") }

enum class MinQuality(val label: String, val kbps: Int) {
    Any("Any", 0), K70("70 kbps", 70), K128("128 kbps", 128), K160("160 kbps", 160),
}

enum class FormatPref(val label: String) { Opus("Opus"), M4a("M4A (AAC)") }

/** "Hide versions" chips: each hides results whose title matches one of its patterns. */
enum class VersionTag(val label: String, patterns: List<String>) {
    Covers("Covers", listOf("""\bcover\b""", """\bcovered by\b""")),
    Remixes("Remixes", listOf("""\bremix\b""", """\bremixed\b""", """\bbootleg\b""")),
    SlowedReverb("Slowed + reverb", listOf("""\bslowed\b""", """\breverb\b""")),
    SpedUp("Sped up", listOf("""\bsped[\s-]?up\b""", """\bspeed[\s-]?up\b""")),
    Karaoke("Karaoke", listOf("""\bkaraoke\b""", """\binstrumental with lyrics\b""")),
    Live("Live", listOf("""\blive\b""", """\bconcert\b""", """\bunplugged\b""")),
    Nightcore("Nightcore", listOf("""\bnightcore\b""")),
    EightD("8D audio", listOf("""\b8d\b""", """\b16d\b""")),
    ;

    val regex = Regex(patterns.joinToString("|"), RegexOption.IGNORE_CASE)
}

class ActiveChip(val label: String, val clear: (SearchFilters) -> SearchFilters)

data class SearchFilters(
    val songsOnly: Boolean = true,
    val language: SearchLanguage = SearchLanguage.Any,
    val length: LengthFilter = LengthFilter.Any,
    val uploaded: UploadedFilter = UploadedFilter.AnyTime,
    val sort: SortOrder = SortOrder.Relevance,
    val minQuality: MinQuality = MinQuality.K128,
    val format: FormatPref = FormatPref.Opus,
    val officialOnly: Boolean = false,
    val hidden: Set<VersionTag> = setOf(VersionTag.Covers, VersionTag.Remixes),
    // Ringtone defaults (used when the trimmer opens)
    val clipSeconds: Int = 30,
    val volumeLeveling: Boolean = true,
    val startAtChorus: Boolean = true,
) {
    /** Count shown on the Filters chip and the "Show results · N filters" button (same rules as the design). */
    val activeCount: Int
        get() = listOf(
            songsOnly,
            language != SearchLanguage.Any,
            length != LengthFilter.Any,
            uploaded != UploadedFilter.AnyTime,
            minQuality != MinQuality.Any,
            officialOnly,
            hidden.isNotEmpty(),
        ).count { it }

    /** The active-filter chips on the search screen; tapping one applies [ActiveChip.clear]. */
    val activeChips: List<ActiveChip>
        get() = buildList {
            if (songsOnly) add(ActiveChip("Songs only") { it.copy(songsOnly = false) })
            if (language != SearchLanguage.Any) add(ActiveChip(language.label) { it.copy(language = SearchLanguage.Any) })
            if (length != LengthFilter.Any) add(ActiveChip(length.label) { it.copy(length = LengthFilter.Any) })
            if (uploaded != UploadedFilter.AnyTime) add(ActiveChip(uploaded.label) { it.copy(uploaded = UploadedFilter.AnyTime) })
            if (sort != SortOrder.Relevance) add(ActiveChip(sort.label) { it.copy(sort = SortOrder.Relevance) })
            if (minQuality != MinQuality.Any) add(ActiveChip("${minQuality.kbps} kbps+") { it.copy(minQuality = MinQuality.Any) })
            if (officialOnly) add(ActiveChip("Official only") { it.copy(officialOnly = false) })
            if (hidden.isNotEmpty()) {
                val label = "No " + hidden.sortedBy { it.ordinal }.joinToString(" or ") { it.label.lowercase() }
                add(ActiveChip(label) { it.copy(hidden = emptySet()) })
            }
        }

    val activeLabels: List<String> get() = activeChips.map { it.label }

    companion object {
        /** What "Reset" clears to: nothing filtered, ringtone defaults kept. */
        fun cleared(current: SearchFilters) = SearchFilters(
            songsOnly = false, minQuality = MinQuality.Any, hidden = emptySet(),
            clipSeconds = current.clipSeconds,
            volumeLeveling = current.volumeLeveling,
            startAtChorus = current.startAtChorus,
            format = current.format,
        )
    }
}

/** Client-side part of filtering. YouTube search has no length/date/version filters we can pass through. */
fun applyFilters(songs: List<Song>, f: SearchFilters, nowEpochSec: Long): List<Song> {
    val kept = songs.filter { s ->
        val okLive = !s.isLive // live streams can't be cut into tones, so never shown
        val okLength = f.length == LengthFilter.Any ||
            (s.durationSec > 0 && s.durationSec in f.length.minSec..f.length.maxSec)
        // Results without a date (e.g. YouTube Music songs) pass the date filter rather than vanish.
        val okDate = f.uploaded == UploadedFilter.AnyTime || s.uploadedEpochSec == null ||
            nowEpochSec - s.uploadedEpochSec <= f.uploaded.maxAgeSec
        val okOfficial = !f.officialOnly || s.isOfficial
        val okVersion = f.hidden.none { it.regex.containsMatchIn(s.title) }
        okLive && okLength && okDate && okOfficial && okVersion
    }.let(::dedupe)
    return when (f.sort) {
        SortOrder.Relevance -> kept
        SortOrder.MostViewed -> kept.sortedByDescending { it.viewCount }
        SortOrder.Newest -> kept.sortedByDescending { it.uploadedEpochSec ?: Long.MIN_VALUE }
    }
}

// ---------- Duplicates ----------

/** Bracketed tags that don't change which recording it is. */
private val noiseTag = Regex(
    """[(\[]\s*(?:\d{4}\s+)?(?:remaster(?:ed)?(?:\s+\d{4})?|official(?:\s+(?:audio|video|music video|lyric video))?|""" +
        """lyrics?(?:\s+video)?|audio|video|visuali[sz]er|hd|hq|4k|explicit|clean|album version|single version)""" +
        """(?:\s+version)?\s*[)\]]""",
    RegexOption.IGNORE_CASE,
)
private val dashNoise = Regex("""\s+-\s+(?:\d{4}\s+)?remaster(?:ed)?(?:\s+\d{4})?\s*$""", RegexOption.IGNORE_CASE)
private val nonWord = Regex("""[^\p{L}\p{N}]+""")

/** "(From "Movie")", "(From "Movie (Telugu)")", "[From Album]" — which release it's on, not which recording. */
private val fromClause = Regex("""\(\s*from\b(?:[^()]|\([^()]*\))*\)|\[\s*from\b[^\]]*]""", RegexOption.IGNORE_CASE)

/** Language markers that DO mean a different recording (dubbed soundtrack versions). */
private val languageWord = Regex(
    """\b(tamil|telugu|hindi|kannada|malayalam|marathi|bengali|punjabi|gujarati|odia|english|spanish|korean|japanese|""" +
        """french|german|portuguese|arabic|urdu)\b""",
    RegexOption.IGNORE_CASE,
)

/**
 * Matching key for a title: "Song (Remastered 2011) [Official Audio]" -> "song",
 * "Song (From "Movie")" -> "song", "Song (From "Movie (Telugu)")" -> "song telugu".
 */
fun normalizeTitle(title: String): String {
    val languages = languageWord.findAll(title).map { it.value.lowercase() }.toSortedSet()
    val base = title.replace(fromClause, " ").replace(noiseTag, " ").replace(dashNoise, " ")
        .lowercase().replace(nonWord, " ").trim()
    val extra = languages.filterNot { Regex("""\b$it\b""").containsMatchIn(base) }
    return (listOf(base) + extra).joinToString(" ").trim()
}

/** First listed artist only, so "A, B" and "A & B" match "A". */
fun primaryArtist(artist: String): String =
    artist.split(",", "&", " x ", " feat", " ft.").first().lowercase().replace(nonWord, " ").trim()

/**
 * Drops the same recording listed more than once (single + album + compilation all show up as
 * separate YouTube Music results). Same normalized title, same main artist, and lengths within
 * 3 s count as one; the first (most relevant) is kept.
 */
fun dedupe(songs: List<Song>): List<Song> {
    val seen = HashMap<String, MutableList<Long>>()
    return songs.filter { s ->
        val key = normalizeTitle(s.title) + "|" + primaryArtist(s.artist)
        val durations = seen.getOrPut(key) { mutableListOf() }
        val dup = durations.any { d -> d <= 0 || s.durationSec <= 0 || kotlin.math.abs(d - s.durationSec) <= 3 }
        if (!dup) durations += s.durationSec
        !dup
    }
}
