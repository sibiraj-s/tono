package app.tono.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchFiltersTest {

    private val now = 1_800_000_000L

    private fun song(
        title: String,
        durationSec: Long = 200,
        views: Long = 0,
        uploaded: Long? = null,
        live: Boolean = false,
        official: Boolean = true,
    ) = Song(
        url = "https://youtube.com/watch?v=${title.hashCode()}",
        title = title, artist = "A", durationSec = durationSec, source = SongSource.YouTube,
        viewCount = views, uploadedEpochSec = uploaded, isLive = live, isOfficial = official,
    )

    @Test
    fun `default filters hide covers and remixes but keep the original`() {
        val songs = listOf(song("Clair de Lune"), song("Clair de Lune (Piano Cover)"), song("Clair de Lune REMIX"))
        val kept = applyFilters(songs, SearchFilters(), now).map { it.title }
        assertEquals(listOf("Clair de Lune"), kept)
    }

    @Test
    fun `version words match whole words only`() {
        // "Discovery" contains "cover" but isn't a cover; "Alive" contains "live".
        val songs = listOf(song("Discovery"), song("Stayin' Alive"))
        val f = SearchFilters(hidden = setOf(VersionTag.Covers, VersionTag.Live))
        assertEquals(2, applyFilters(songs, f, now).size)
    }

    @Test
    fun `sped up, slowed and nightcore are detected`() {
        val f = SearchFilters(hidden = setOf(VersionTag.SpedUp, VersionTag.SlowedReverb, VersionTag.Nightcore, VersionTag.EightD))
        val songs = listOf(song("Song (sped up)"), song("Song - Slowed + Reverb"), song("Nightcore - Song"), song("Song 8D Audio"), song("Song"))
        assertEquals(listOf("Song"), applyFilters(songs, f, now).map { it.title })
    }

    @Test
    fun `live streams are always hidden`() {
        val songs = listOf(song("A", live = true), song("B"))
        assertEquals(listOf("B"), applyFilters(songs, SearchFilters.cleared(SearchFilters()), now).map { it.title })
    }


    @Test
    fun `length filter uses duration and drops unknown durations`() {
        val songs = listOf(song("short", 120), song("medium", 600), song("unknown", 0))
        val f = SearchFilters(hidden = emptySet(), length = LengthFilter.UnderFour)
        assertEquals(listOf("short"), applyFilters(songs, f, now).map { it.title })
    }

    @Test
    fun `date filter lets undated results through`() {
        val songs = listOf(song("old", uploaded = now - 400L * 86_400), song("new", uploaded = now - 86_400), song("undated"))
        val f = SearchFilters(hidden = emptySet(), uploaded = UploadedFilter.Month)
        assertEquals(listOf("new", "undated"), applyFilters(songs, f, now).map { it.title })
    }

    @Test
    fun `sort by views and newest`() {
        val songs = listOf(song("a", views = 5, uploaded = 10), song("b", views = 50, uploaded = 5), song("c", views = 1, uploaded = 30))
        assertEquals(listOf("b", "a", "c"), applyFilters(songs, SearchFilters(hidden = emptySet(), sort = SortOrder.MostViewed), now).map { it.title })
        assertEquals(listOf("c", "a", "b"), applyFilters(songs, SearchFilters(hidden = emptySet(), sort = SortOrder.Newest), now).map { it.title })
    }

    @Test
    fun `official only`() {
        val songs = listOf(song("a", official = false), song("b"))
        assertEquals(listOf("b"), applyFilters(songs, SearchFilters(hidden = emptySet(), officialOnly = true), now).map { it.title })
    }

    @Test
    fun `active count matches the design defaults`() {
        // Songs only + 128 kbps + hidden versions.
        assertEquals(3, SearchFilters().activeCount)
        assertEquals(0, SearchFilters.cleared(SearchFilters()).activeCount)
    }

    @Test
    fun `reset keeps ringtone defaults`() {
        val custom = SearchFilters(clipSeconds = 40, volumeLeveling = false, startAtChorus = false)
        val cleared = SearchFilters.cleared(custom)
        assertEquals(40, cleared.clipSeconds)
        assertEquals(false, cleared.volumeLeveling)
        assertEquals(false, cleared.startAtChorus)
    }


    @Test
    fun `formats durations`() {
        assertEquals("5:04", formatDuration(304))
        assertEquals("1:02:03", formatDuration(3723))
        assertEquals("0:42.3", formatMs(42_300, tenths = true))
    }

    @Test
    fun `same recording listed twice is shown once`() {
        val songs = listOf(
            song("Ala Bolelo (From \"Jailer 2\")", 241).copy(artist = "Anirudh Ravichander"),
            song("Ala Bolelo (From \"Jailer 2\")", 242).copy(artist = "Anirudh Ravichander, Someone"),
            song("Ala Bolelo (From \"Jailer 2 (Telugu)\")", 241).copy(artist = "Anirudh Ravichander"),
            song("Ala Bolelo (From \"Rajini The Jailer 2\")", 241).copy(artist = "Anirudh Ravichander"),
            song("Ala Bolelo (From \"Jailer 2\")", 300).copy(artist = "Anirudh Ravichander"), // different cut
        )
        val kept = dedupe(songs)
        assertEquals(listOf(241L, 241L, 300L), kept.map { it.durationSec })
        assertEquals("Ala Bolelo (From \"Jailer 2 (Telugu)\")", kept[1].title)
        assertEquals(241L, kept[0].durationSec)
    }

    @Test
    fun `noise tags are ignored when matching titles`() {
        assertEquals("let it be", normalizeTitle("Let It Be (Remastered 2009)"))
        assertEquals("let it be", normalizeTitle("Let It Be [Official Audio]"))
        assertEquals("let it be", normalizeTitle("Let It Be - Remastered 2009"))
        assertEquals("song", normalizeTitle("Song (From \"Movie\")"))
        assertEquals("song", normalizeTitle("Song [From The Album]"))
        assertEquals("song telugu", normalizeTitle("Song (From \"Movie (Telugu)\")"))
        assertEquals("song hindi", normalizeTitle("Song - Hindi (From \"Movie\")"))
    }

    @Test
    fun `language is added to the search unless already typed`() {
        val tamil = SearchFilters(language = SearchLanguage.Tamil)
        assertEquals("ala bolelo tamil", searchQuery(" ala bolelo ", tamil))
        assertEquals("ala bolelo Tamil version", searchQuery("ala bolelo Tamil version", tamil))
        assertEquals("ala bolelo", searchQuery("ala bolelo", SearchFilters()))
    }

    @Test
    fun `tone names drop the release tag`() {
        assertEquals("Kaavaalaa", toneName("Kaavaalaa (From \"Jailer\")"))
        assertEquals("Ala Bolelo", toneName("Ala Bolelo (From \"Jailer 2 (Telugu)\")"))
        assertEquals("Radhimaa", toneName("Radhimaa [From \"Think Indie\"]"))
        assertEquals("Let It Be", toneName("Let It Be"))
    }
}
