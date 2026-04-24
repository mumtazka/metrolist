/**
 * SearchRanker — client-side re-ranking of YouTube Music search results.
 *
 * YouTube's API returns results in its own order which often puts covers, remixes,
 * lyric videos, and sped-up versions before the original song. This object applies
 * a heuristic scoring function to surface originals first.
 */

package com.metrolist.desktop.search

import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
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
    // (e.g. "(Live)" is a penalty but "Live Forever" is not)
    private val TITLE_BRACKET_PENALTY_KEYWORDS = setOf(
        "live", "acoustic", "lyrics", "lyric video",
    )

    // ── Penalty substrings found in ARTIST names ───────────────────────────
    // These are known lyric/compilation channels, not real artists
    private val ARTIST_PENALTY_SUBSTRINGS = setOf(
        "unique sound", "holly's", "vybely", "clouds", "rllyrics",
        "r&btype", "r&blyps", "topic", "lyric", "lyrics",
        "duskresonance", "zrlouds", "trxnzitions", "melo nada",
        "akoustic", "melancolia",
    )

    // ── Known official-title suffixes that are actually legit ──────────────
    private val OFFICIAL_SUFFIXES = setOf(
        "official audio", "official video", "official music video", "official mv",
    )

    /**
     * Score a [SongItem] relative to the search [query].
     * Higher score = more likely to be the original/official track.
     * [originalIndex] is used as a tiebreaker (lower = appeared earlier in API response).
     */
    private fun score(
        song: SongItem,
        query: String,
        likedIds: Set<String>,
        originalIndex: Int,
    ): Double {
        var score = 0.0
        val titleLower = song.title.lowercase()
        val artistNames = song.artists.joinToString(" ") { it.name }.lowercase()
        val queryLower = query.trim().lowercase()

        // ── Boost: liked by the user ────────────────────────────────────────
        if (song.id in likedIds) score += 100.0

        // ── Boost: Clean title (no penalty keywords) ────────────────────────
        val titleHasPenaltyWord = TITLE_PENALTY_KEYWORDS.any { titleLower.contains(it) }
        val titleHasBracketPenalty = TITLE_BRACKET_PENALTY_KEYWORDS.any { kw ->
            titleLower.contains("($kw)") || titleLower.contains("[$kw]") ||
            titleLower.contains("- $kw)")
        }
        if (!titleHasPenaltyWord && !titleHasBracketPenalty) score += 50.0

        // ── Boost: Official audio (ATV = Audio Track Video, YouTube's official flag) ──
        val musicVideoType = song.musicVideoType
        if (musicVideoType == MUSIC_VIDEO_TYPE_ATV || musicVideoType == null) {
            // ATV or no video type at all = almost certainly official audio
            score += 30.0
        }

        // ── Boost: Has album (official releases almost always do) ───────────
        if (song.album != null) score += 20.0

        // ── Boost: Artist name matches query (user searched by artist name) ───
        if (queryLower.isNotBlank() && artistNames.contains(queryLower)) score += 15.0

        // ── Boost: Title literally starts with query term ───────────────────
        if (titleLower.startsWith(queryLower)) score += 10.0

        // ── Boost: Title contains "official" keyword ─────────────────────────
        if (OFFICIAL_SUFFIXES.any { titleLower.contains(it) }) score += 8.0

        // ── Penalty: title has cover/remix/sped-up etc ──────────────────────
        if (titleHasPenaltyWord) score -= 40.0
        if (titleHasBracketPenalty) score -= 25.0

        // ── Penalty: artist is a known lyric/compilation channel ────────────
        val artistIsBadChannel = ARTIST_PENALTY_SUBSTRINGS.any { artistNames.contains(it) }
        if (artistIsBadChannel) score -= 30.0

        // ── Penalty: title contains " - " suggesting "Artist - Song (Lyrics)" format ─
        // e.g.  "Lana Del Rey - Young And Beautiful (Lyrics)"
        if (titleLower.contains(" - ") && (titleLower.contains("(lyrics") || titleLower.contains("[lyrics"))) {
            score -= 35.0
        }

        // Tie-break: subtract a tiny fraction based on original API position
        // so earlier API results win when scores are equal
        score -= originalIndex * 0.001

        return score
    }

    /**
     * Compute a 0..1 relevance score for an artist name vs the query.
     * Splits both into tokens and counts overlap.
     */
    private fun artistRelevance(artistTitle: String, query: String): Double {
        val artistTokens = artistTitle.lowercase().split(Regex("\\W+")).filter { it.length > 1 }.toSet()
        val queryTokens  = query.lowercase().split(Regex("\\W+")).filter { it.length > 1 }.toSet()
        if (queryTokens.isEmpty() || artistTokens.isEmpty()) return 0.0
        val overlap = artistTokens.intersect(queryTokens).size
        return overlap.toDouble() / queryTokens.size.toDouble()
    }

    /**
     * Re-rank [items] returned by the API.
     *
     * - [ArtistItem]s are filtered by relevance to the query and capped to avoid flooding
     * - [SongItem] items are sorted by score, preserving API order as tiebreaker
     * - Non-song, non-artist items (Albums, Playlists) keep their relative API order
     */
    fun rankResults(
        items: List<YTItem>,
        query: String,
        likedIds: Set<String>,
    ): List<YTItem> {
        if (items.isEmpty()) return items

        // Split into categories, preserving original indices for tiebreaking
        val artists = mutableListOf<Pair<Int, ArtistItem>>()
        val songs   = mutableListOf<Pair<Int, SongItem>>()
        val others  = mutableListOf<Pair<Int, YTItem>>()   // Albums, Playlists, etc.

        items.forEachIndexed { idx, item ->
            when (item) {
                is ArtistItem -> artists.add(idx to item)
                is SongItem   -> songs.add(idx to item)
                else          -> others.add(idx to item)
            }
        }

        val queryLower = query.trim().lowercase()

        // ── Filter + sort artists by relevance ─────────────────────────────
        // Score each artist by token overlap with the query
        val scoredArtists = artists.mapNotNull { (idx, a) ->
            val rel = artistRelevance(a.title, query)
            if (rel == 0.0) null else Triple(rel, idx, a)
        }.sortedWith(compareByDescending<Triple<Double, Int, ArtistItem>> { it.first }
            .thenBy { it.second })

        // Decide how many artists to keep:
        // - If query looks like an artist name (the first API artist is an exact/near match), keep up to 2
        // - Otherwise (song title search), keep at most 1
        val queryIsArtistSearch = scoredArtists.isNotEmpty() &&
            scoredArtists.first().first >= 0.8  // ≥80% token overlap = almost certainly artist search
        val artistKeepCount = if (queryIsArtistSearch) 2 else 1
        val sortedArtists = scoredArtists.take(artistKeepCount).map { it.third }

        // ── Sort songs by score ─────────────────────────────────────────────
        val sortedSongs = songs
            .sortedByDescending { (idx, song) -> score(song, query, likedIds, idx) }
            .map { it.second }

        // ── Others keep original relative order ─────────────────────────────
        val sortedOthers = others.map { it.second }

        // Final order: Artists → Songs → Everything else
        return sortedArtists + sortedSongs + sortedOthers
    }
}
