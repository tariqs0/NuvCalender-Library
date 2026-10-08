package com.nuvio.app.features.library

import com.nuvio.app.features.calendar.CalendarDay
import com.nuvio.app.features.calendar.LibraryTitleInfo
import com.nuvio.app.features.calendar.isLibraryRefreshDue
import com.nuvio.app.features.calendar.libraryEpisodesOf
import com.nuvio.app.features.details.MetaDetailsParser
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Addon meta responses (Stremio meta protocol, as served by Cinemeta, anime and other addons)
 * flowing through the app's parser into the Library lists — before and after a refresh that
 * picks up a new episode or season.
 */
class LibraryAddonSyncTest {
    private val today = CalendarDay.parse("2026-10-09")!!.epochDay

    private fun video(id: String, season: Int, episode: Int, released: String?, field: String = "episode") =
        """{"id":"$id:$season:$episode","title":"Episode $episode","season":$season,"$field":$episode""" +
            (released?.let { ""","released":"$it"""" } ?: "") + "}"

    private fun meta(id: String, type: String, vararg videos: String) =
        """{"meta":{"id":"$id","type":"$type","name":"$id","videos":[${videos.joinToString(",")}]}}"""

    private fun infoFrom(payload: String) =
        LibraryTitleInfo(episodes = libraryEpisodesOf(MetaDetailsParser.parse(payload)))

    private fun watched(id: String, type: String, vararg episodes: Pair<Int, Int>) =
        episodes.map { (s, e) -> WatchedItem(id = id, type = type, name = id, season = s, episode = e, markedAtEpochMs = 1L) }

    private fun classify(item: LibraryItem, watched: List<WatchedItem>, payload: String, progress: List<WatchProgressEntry> = emptyList()) =
        classifyLibraryItem(item, watched, progress, false, infoFrom(payload), today)

    @Test
    fun cinemetaSeriesReturnsToContinueWatchingWhenARefreshShowsANewAiredEpisode() {
        val item = LibraryItem(id = "tt9288030", type = "series", name = "Reacher", savedAtEpochMs = 1L)
        val seen = watched("tt9288030", "series", 1 to 1, 1 to 2)
        val before = meta(
            "tt9288030", "series",
            video("tt9288030", 1, 1, "2026-09-25T00:00:00.000Z"),
            video("tt9288030", 1, 2, "2026-10-02T00:00:00.000Z"),
        )
        assertEquals(LibrarySmartList.Watched, classify(item, seen, before).list)

        // The addon now lists episode 3 (out today) and an announced season 2.
        val after = meta(
            "tt9288030", "series",
            video("tt9288030", 1, 1, "2026-09-25T00:00:00.000Z"),
            video("tt9288030", 1, 2, "2026-10-02T00:00:00.000Z"),
            video("tt9288030", 1, 3, "2026-10-09T00:00:00.000Z"),
            video("tt9288030", 2, 1, "2027-02-01T00:00:00.000Z"),
        )
        val refreshed = classify(item, seen, after)
        assertEquals(LibrarySmartList.ContinueWatching, refreshed.list)
        assertEquals(1 to 3, refreshed.nextSeason to refreshed.nextEpisode)
        assertEquals(1 to 2, refreshed.lastSeason to refreshed.lastEpisode)
    }

    @Test
    fun animeAddonSeriesReturnsWhenANewSeasonPremieres() {
        val item = LibraryItem(id = "kitsu:46474", type = "anime", name = "Anime", savedAtEpochMs = 1L)
        val seen = watched("kitsu:46474", "anime", 1 to 1, 1 to 2)
        val before = meta(
            "kitsu:46474", "anime",
            video("kitsu:46474", 1, 1, "2026-01-05T15:00:00.000Z"),
            video("kitsu:46474", 1, 2, "2026-01-12T15:00:00.000Z"),
        )
        assertEquals(LibrarySmartList.Watched, classify(item, seen, before).list)

        val after = meta(
            "kitsu:46474", "anime",
            video("kitsu:46474", 1, 1, "2026-01-05T15:00:00.000Z"),
            video("kitsu:46474", 1, 2, "2026-01-12T15:00:00.000Z"),
            video("kitsu:46474", 2, 1, "2026-10-08T15:00:00.000Z"),
        )
        val refreshed = classify(item, seen, after)
        assertEquals(LibrarySmartList.ContinueWatching, refreshed.list)
        assertEquals(2 to 1, refreshed.nextSeason to refreshed.nextEpisode)
    }

    @Test
    fun olderAddonsThatNumberEpisodesWithNumberAreUnderstood() {
        val item = LibraryItem(id = "legacy:1", type = "series", name = "Legacy", savedAtEpochMs = 1L)
        val payload = meta(
            "legacy:1", "series",
            video("legacy:1", 1, 1, "2026-09-01T00:00:00.000Z", field = "number"),
            video("legacy:1", 1, 2, "2026-10-01T00:00:00.000Z", field = "number"),
        )
        assertEquals(listOf(1 to 1, 1 to 2), infoFrom(payload).episodes.map { it.season to it.episode })
        val result = classify(item, watched("legacy:1", "series", 1 to 1), payload)
        assertEquals(LibrarySmartList.ContinueWatching, result.list)
        assertEquals(1 to 2, result.nextSeason to result.nextEpisode)
    }

    @Test
    fun undatedOrSpecialEpisodesNeverPullACaughtUpSeriesBack() {
        val item = LibraryItem(id = "tt1", type = "series", name = "Show", savedAtEpochMs = 1L)
        val payload = meta(
            "tt1", "series",
            video("tt1", 0, 1, "2026-10-01T00:00:00.000Z"),
            video("tt1", 1, 1, "2026-09-01T00:00:00.000Z"),
            video("tt1", 1, 2, null),
        )
        assertEquals(LibrarySmartList.Watched, classify(item, watched("tt1", "series", 1 to 1), payload).list)
    }

    @Test
    fun watchProgressFromSyncShowsTheEpisodeAndPercent() {
        val item = LibraryItem(id = "tt9288030", type = "series", name = "Reacher", savedAtEpochMs = 1L)
        val payload = meta(
            "tt9288030", "series",
            video("tt9288030", 1, 1, "2026-09-25T00:00:00.000Z"),
            video("tt9288030", 1, 2, "2026-10-02T00:00:00.000Z"),
        )
        val progress = WatchProgressEntry(
            contentType = "series", parentMetaId = "tt9288030", parentMetaType = "series",
            videoId = "tt9288030:1:2", title = "Reacher", seasonNumber = 1, episodeNumber = 2,
            lastPositionMs = 1_800_000L, durationMs = 3_600_000L, lastUpdatedEpochMs = 50L, progressPercent = 50f,
        )
        val result = classify(item, watched("tt9288030", "series", 1 to 1), payload, listOf(progress))
        assertEquals(LibrarySmartList.ContinueWatching, result.list)
        assertEquals(1 to 2, result.lastSeason to result.lastEpisode)
        assertEquals(0.5f, result.progressFraction)
    }

    @Test
    fun refreshCadenceKeepsAnOpenLibraryCurrent() {
        val hour = 3_600_000L
        // Loaded at 09:00; the open Library re-checks every 15 minutes and re-reads at 12:00.
        val loaded = 9 * hour
        val checks = (1..16).map { loaded + it * hour / 4 }
        val firstRefresh = checks.first { isLibraryRefreshDue(loaded, it) }
        assertEquals(12 * hour, firstRefresh)
        assertTrue(checks.takeWhile { it < 12 * hour }.none { isLibraryRefreshDue(loaded, it) })
    }
}
