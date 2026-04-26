/**
 * SearchRanker — client-side re-ranking + smart Top Result selection.
 *
 * Provides two public APIs:
 *  - rankResults() : reorders all search results for display in the list
 *  - pickTopResult() : selects the single best item for the hero card (song OR artist)
 */

package com.metrolist.desktop.search

import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV

object SearchRanker {

    // ── Penalty keywords found in song TITLES ──────────────────────────────
    private val TITLE_PENALTY_KEYWORDS = setOf(
        "cover", "remix", "sped up", "speed up", "slowed", "reverb",
        "instrumental", "karaoke", "8d audio", "1 hour", "2 hour", "10 hour",
        "hours looping", "hour loop", "nightcore", "rock version",
        "lofi version", "lo-fi version", "piano version", "violin version",
        "tribute to", "in the style of", "as made famous",
    )

    // Words that must appear in parens/brackets to count as a penalty
    private val TITLE_BRACKET_PENALTY_KEYWORDS = setOf(
        "live", "acoustic", "lyrics", "lyric video",
    )

    // Penalty artist name substrings (lyric/compilation channels)
    private val ARTIST_PENALTY_SUBSTRINGS = setOf(
        "unique sound", "holly's", "vybely", "clouds", "rllyrics",
        "r&btype", "r&blyps", "topic", "lyric", "lyrics",
        "duskresonance", "zrlouds", "trxnzitions", "melo nada",
        "akoustic", "melancolia",
    )

    // Official suffixes that are fine
    private val OFFICIAL_SUFFIXES = setOf(
        "official audio", "official video", "official music video", "official mv",
    )

    // Words in the query that are dead giveaways it's a SONG (not an artist) search
    private val SONG_SIGNAL_WORDS = setOf(
        "song", "music", "album", "feat", "ft", "remix", "cover",
        "official", "audio", "video", "mv", "lyrics", "lyric",
        "version", "edition", "single", "ep", "ost", "soundtrack",
    )

    /**
     * Score a [SongItem] relative to the search [query].
     * Higher score = more likely to be the original/official track the user wants.
     */
    fun scoreSong(
        song: SongItem,
        query: String,
        likedIds: Set<String>,
        originalIndex: Int,
    ): Double {
        var score = 0.0
        val titleLower = song.title.lowercase().trim()
        val artistNames = song.artists.joinToString(" ") { it.name }.lowercase()
        val queryLower = query.trim().lowercase()

        // ── Boost: liked by the user ────────────────────────────────────────
        if (song.id in likedIds) score += 100.0

        // ── Boost: EXACT title match (e.g. query = "sailor song", title = "Sailor Song") ──
        if (titleLower == queryLower) score += 200.0

        // ── Boost: Title STARTS with the full query ──────────────────────────
        if (titleLower.startsWith(queryLower)) score += 80.0

        // ── Boost: All query tokens appear in title ───────────────────────────
        val queryTokens = queryLower.split(Regex("\\s+")).filter { it.length > 1 }
        val titleTokens = titleLower.split(Regex("\\W+")).toSet()
        val tokenCoverage = queryTokens.count { it in titleTokens }.toDouble() / queryTokens.size.coerceAtLeast(1)
        if (tokenCoverage >= 1.0) score += 60.0
        else if (tokenCoverage >= 0.75) score += 30.0

        // ── Boost: Clean title (no penalty keywords) ────────────────────────
        val titleHasPenaltyWord = TITLE_PENALTY_KEYWORDS.any { titleLower.contains(it) }
        val titleHasBracketPenalty = TITLE_BRACKET_PENALTY_KEYWORDS.any { kw ->
            titleLower.contains("($kw)") || titleLower.contains("[$kw]") ||
            titleLower.contains("- $kw)")
        }
        if (!titleHasPenaltyWord && !titleHasBracketPenalty) score += 50.0

        // ── Boost: ATV = official audio flag ─────────────────────────────────
        val musicVideoType = song.musicVideoType
        if (musicVideoType == MUSIC_VIDEO_TYPE_ATV || musicVideoType == null) score += 30.0

        // ── Boost: Has album ─────────────────────────────────────────────────
        if (song.album != null) score += 20.0

        // ── Boost: Artist name matches query ──────────────────────────────────
        if (queryLower.isNotBlank() && artistNames.contains(queryLower)) score += 15.0

        // ── Boost: Official keyword ───────────────────────────────────────────
        if (OFFICIAL_SUFFIXES.any { titleLower.contains(it) }) score += 8.0

        // ── Penalties ─────────────────────────────────────────────────────────
        if (titleHasPenaltyWord) score -= 40.0
        if (titleHasBracketPenalty) score -= 25.0

        val artistIsBadChannel = ARTIST_PENALTY_SUBSTRINGS.any { artistNames.contains(it) }
        if (artistIsBadChannel) score -= 30.0

        if (titleLower.contains(" - ") && (titleLower.contains("(lyrics") || titleLower.contains("[lyrics"))) {
            score -= 35.0
        }

        // Tie-break: earlier API results win when scores are equal
        score -= originalIndex * 0.001

        return score
    }

    /**
     * Compute a 0..1 relevance score for an artist name vs the query.
     * Splits both into tokens and counts overlap relative to query length.
     */
    fun artistRelevance(artistTitle: String, query: String): Double {
        val artistTokens = artistTitle.lowercase().split(Regex("\\W+")).filter { it.length > 1 }.toSet()
        val queryTokens  = query.lowercase().split(Regex("\\W+")).filter { it.length > 1 }.toSet()
        if (queryTokens.isEmpty() || artistTokens.isEmpty()) return 0.0
        val overlap = artistTokens.intersect(queryTokens).size
        return overlap.toDouble() / queryTokens.size.toDouble()
    }

    /**
     * Determine if the query itself "sounds like" a song title search
     * based on known signal words (e.g. "sailor SONG", "love me not").
     * Returns a 0..1 signal strength where 1.0 = definitely a song search.
     */
    private fun querySongSignalStrength(query: String): Double {
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }.toSet()
        val signalCount = tokens.count { it in SONG_SIGNAL_WORDS }
        // Even 1 signal word (e.g. "song") is a very strong indicator
        return (signalCount * 0.5).coerceAtMost(1.0)
    }

    /**
     * Re-rank [items] returned by the API for the list display.
     */
    fun rankResults(
        items: List<YTItem>,
        query: String,
        likedIds: Set<String>,
    ): List<YTItem> {
        if (items.isEmpty()) return items

        val artists = mutableListOf<Pair<Int, ArtistItem>>()
        val songs   = mutableListOf<Pair<Int, SongItem>>()
        val others  = mutableListOf<Pair<Int, YTItem>>()

        items.forEachIndexed { idx, item ->
            when (item) {
                is ArtistItem -> artists.add(idx to item)
                is SongItem   -> songs.add(idx to item)
                else          -> others.add(idx to item)
            }
        }

        // ── Filter + sort artists by relevance ─────────────────────────────
        val scoredArtists = artists.mapNotNull { (idx, a) ->
            val rel = artistRelevance(a.title, query)
            if (rel == 0.0) null else Triple(rel, idx, a)
        }.sortedWith(compareByDescending<Triple<Double, Int, ArtistItem>> { it.first }
            .thenBy { it.second })

        val queryIsArtistSearch = scoredArtists.isNotEmpty() &&
            scoredArtists.first().first >= 0.8
        val artistKeepCount = if (queryIsArtistSearch) 2 else 1
        val sortedArtists = scoredArtists.take(artistKeepCount).map { it.third }

        // ── Sort songs by score ─────────────────────────────────────────────
        val sortedSongs = songs
            .sortedByDescending { (idx, song) -> scoreSong(song, query, likedIds, idx) }
            .map { it.second }

        val sortedOthers = others.map { it.second }

        return sortedArtists + sortedSongs + sortedOthers
    }

    /**
     * Pick the single best item for the hero "Top Result" card.
     *
     * Decision rules (in priority order):
     *  1. If the query contains song-signal words ("song", "album", "feat" …) → ALWAYS pick best song
     *  2. If the best song's title is an exact match for the query → ALWAYS pick that song
     *  3. If the best song's title starts with the full query → prefer song unless artist relevance is ≥0.95
     *  4. If artist relevance ≥ 0.9 AND no song title starts with query → pick artist
     *  5. Default: pick the best-scored song
     */
    fun pickTopResult(items: List<YTItem>, query: String, likedIds: Set<String>): YTItem? {
        if (items.isEmpty()) return null

        val queryLower = query.trim().lowercase()

        // Signal: does the query itself contain song-type words?
        val songSignal = querySongSignalStrength(query)

        // Best artist
        val bestArtist = items
            .filterIsInstance<ArtistItem>()
            .mapNotNull { a ->
                val rel = artistRelevance(a.title, query)
                if (rel > 0.0) Pair(rel, a) else null
            }
            .maxByOrNull { it.first }

        val artistScore = bestArtist?.first ?: 0.0

        // Best song
        val songs = items.filterIsInstance<SongItem>()
        val bestSong = songs
            .mapIndexed { idx, s -> Pair(scoreSong(s, query, likedIds, idx), s) }
            .maxByOrNull { it.first }

        val songTitleLower = bestSong?.second?.title?.lowercase()?.trim() ?: ""
        val songIsExactMatch    = songTitleLower == queryLower
        val songStartsWithQuery = songTitleLower.startsWith(queryLower)

        return when {
            // Rule 1: query has song-signal words → always show song
            songSignal > 0.0 && bestSong != null -> bestSong.second

            // Rule 2: exact song title match → always show song
            songIsExactMatch && bestSong != null -> bestSong.second

            // Rule 3: song starts with query → prefer song unless artist is extremely close
            songStartsWithQuery && bestSong != null && artistScore < 0.95 -> bestSong.second

            // Rule 4: very high artist relevance AND no song starts with query → show artist
            artistScore >= 0.9 && !songStartsWithQuery && bestArtist != null -> bestArtist.second

            // Rule 5: default — best song
            bestSong != null -> bestSong.second

            // Fallback
            else -> items.firstOrNull()
        }
    }
}
