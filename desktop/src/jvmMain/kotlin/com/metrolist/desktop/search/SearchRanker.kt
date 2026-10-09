/**
 * SearchRanker — client-side re-ranking + smart Top Result selection.
 *
 * Public APIs:
 *  - rankResults()             : legacy re-rank for the list (kept for compatibility)
 *  - rankResultsWithArtistFill(): 3-tier ranking — title match → artist fill → rest
 *  - pickTopResult()           : picks the single best hero card item
 *  - detectPrimaryArtist()     : finds the most query-relevant artist in a result set
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

    // ── Core scoring ──────────────────────────────────────────────────────────

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
        val titleLower  = song.title.lowercase().trim()
        val artistNames = song.artists.joinToString(" ") { it.name }.lowercase()
        val queryLower  = query.trim().lowercase()

        if (song.id in likedIds)       score += 100.0
        if (titleLower == queryLower)  score += 200.0
        if (titleLower.startsWith(queryLower)) score += 80.0

        val queryTokens  = queryLower.split(Regex("\\s+")).filter { it.length > 1 }
        val titleTokens  = titleLower.split(Regex("\\W+")).toSet()
        val tokenCoverage = queryTokens.count { it in titleTokens }.toDouble() /
                queryTokens.size.coerceAtLeast(1)
        if (tokenCoverage >= 1.0)   score += 60.0
        else if (tokenCoverage >= 0.75) score += 30.0

        val titleHasPenaltyWord = TITLE_PENALTY_KEYWORDS.any { titleLower.contains(it) }
        val titleHasBracketPenalty = TITLE_BRACKET_PENALTY_KEYWORDS.any { kw ->
            titleLower.contains("($kw)") || titleLower.contains("[$kw]") ||
            titleLower.contains("- $kw)")
        }
        if (!titleHasPenaltyWord && !titleHasBracketPenalty) score += 50.0

        val musicVideoType = song.musicVideoType
        if (musicVideoType == MUSIC_VIDEO_TYPE_ATV || musicVideoType == null) score += 30.0
        if (song.album != null)  score += 20.0
        if (queryLower.isNotBlank() && artistNames.contains(queryLower)) score += 15.0
        if (OFFICIAL_SUFFIXES.any { titleLower.contains(it) }) score += 8.0

        if (titleHasPenaltyWord)    score -= 40.0
        if (titleHasBracketPenalty) score -= 25.0
        if (ARTIST_PENALTY_SUBSTRINGS.any { artistNames.contains(it) }) score -= 30.0
        if (titleLower.contains(" - ") &&
            (titleLower.contains("(lyrics") || titleLower.contains("[lyrics"))) score -= 35.0

        score -= originalIndex * 0.001
        return score
    }

    /**
     * 0..1 relevance score for an artist name vs the query (token overlap / query size).
     */
    fun artistRelevance(artistTitle: String, query: String): Double {
        val artistTokens = artistTitle.lowercase().split(Regex("\\W+")).filter { it.length > 1 }.toSet()
        val queryTokens  = query.lowercase().split(Regex("\\W+")).filter { it.length > 1 }.toSet()
        if (queryTokens.isEmpty() || artistTokens.isEmpty()) return 0.0
        return artistTokens.intersect(queryTokens).size.toDouble() / queryTokens.size.toDouble()
    }

    private fun querySongSignalStrength(query: String): Double {
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }.toSet()
        return (tokens.count { it in SONG_SIGNAL_WORDS } * 0.5).coerceAtMost(1.0)
    }

    // ── Public ranking APIs ───────────────────────────────────────────────────

    /**
     * Legacy re-rank — kept for callers that don't use the enriched search path.
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

        val scoredArtists = artists.mapNotNull { (idx, a) ->
            val rel = artistRelevance(a.title, query)
            if (rel == 0.0) null else Triple(rel, idx, a)
        }.sortedWith(compareByDescending<Triple<Double, Int, ArtistItem>> { it.first }.thenBy { it.second })

        val queryIsArtistSearch = scoredArtists.isNotEmpty() && scoredArtists.first().first >= 0.8
        val sortedArtists = scoredArtists.take(if (queryIsArtistSearch) 2 else 1).map { it.third }
        val sortedSongs   = songs.sortedByDescending { (idx, s) -> scoreSong(s, query, likedIds, idx) }.map { it.second }

        return sortedArtists + sortedSongs + others.map { it.second }
    }

    /**
     * Detects the name of the most query-relevant artist in [items].
     *
     * Used to drive the secondary "fetch more from this artist" request.
     * Returns null if no artist scores above the 0.4 relevance threshold so we
     * don't fire pointless extra API calls for vague one-word queries.
     *
     * Resolution order:
     *  1. Explicit ArtistItem entries (most reliable)
     *  2. Artist metadata embedded in SongItems, weighted by occurrence frequency
     */
    fun detectPrimaryArtist(items: List<YTItem>, query: String): String? {
        // 1. ArtistItem
        val bestExplicit = items
            .filterIsInstance<ArtistItem>()
            .mapNotNull { a -> artistRelevance(a.title, query).takeIf { it >= 0.4 }?.let { it to a.title } }
            .maxByOrNull { it.first }
        if (bestExplicit != null) return bestExplicit.second

        // 2. Artist names from songs — higher frequency = more confidence
        return items
            .filterIsInstance<SongItem>()
            .flatMap { it.artists }
            .groupBy { it.name }
            .mapValues { (name, occurrences) -> artistRelevance(name, query) * occurrences.size }
            .filter { (_, weight) -> weight >= 0.4 }
            .maxByOrNull { (_, weight) -> weight }
            ?.key
    }

    /**
     * Three-tier ranking for the enriched result set produced by [DesktopViewModel.search].
     *
     * **Tier 1 — Title-match songs**: songs whose title substantially matches the query.
     * Sorted by [scoreSong] (official > clean > slowed/reverb).
     *
     * **Tier 2 — Same-artist filler**: other songs from the most relevant artist.
     * Fills the list when the exact title produces very few results.
     * Also sorted by [scoreSong].
     *
     * **Tier 3 — Everything else**: albums, playlists, artist cards, unrelated songs.
     * Artist cards are bubbled to the top of this tier.
     *
     * Example — query `"the marias sienna"`:
     * - Tier 1 → Sienna, Sienna (Official Audio)
     * - Tier 2 → Cariño, Un Millón, I Like It, Calling U Back, …
     * - Tier 3 → CINEMA album, Playlists
     */
    fun rankResultsWithArtistFill(
        items: List<YTItem>,
        query: String,
        likedIds: Set<String>,
    ): List<YTItem> {
        if (items.isEmpty()) return items

        val queryLower  = query.trim().lowercase()
        val queryTokens = queryLower.split(Regex("\\s+")).filter { it.length > 1 }.toSet()

        val primaryArtist       = detectPrimaryArtist(items, query)?.lowercase()
        val primaryArtistParts  = primaryArtist
            ?.split(Regex("\\W+"))
            ?.filter { it.length > 2 }
            ?: emptyList()

        val tier1        = mutableListOf<Pair<Double, SongItem>>()
        val tier2        = mutableListOf<Pair<Double, SongItem>>()
        val tier3Artists = mutableListOf<ArtistItem>()
        val tier3Rest    = mutableListOf<YTItem>()

        items.forEachIndexed { idx, item ->
            when (item) {
                is SongItem -> {
                    val score       = scoreSong(item, query, likedIds, idx)
                    val titleLower  = item.title.lowercase()
                    val titleTokens = titleLower.split(Regex("\\W+")).toSet()

                    val matchedTokens = queryTokens.count { it in titleTokens }
                    val coverage      = if (queryTokens.isEmpty()) 0.0
                                        else matchedTokens.toDouble() / queryTokens.size

                    if (coverage >= 0.5 || titleLower.contains(queryLower)) {
                        tier1.add(score to item)
                    } else {
                        val songArtists = item.artists.joinToString(" ") { it.name }.lowercase()
                        val isFromPrimary = primaryArtist != null && (
                            songArtists.contains(primaryArtist) ||
                            primaryArtistParts.any { part -> songArtists.contains(part) }
                        )
                        if (isFromPrimary) tier2.add(score to item) else tier3Rest.add(item)
                    }
                }
                is ArtistItem -> tier3Artists.add(item)
                else          -> tier3Rest.add(item)
            }
        }

        val sorted1 = tier1.sortedByDescending { it.first }.map { it.second }
        val sorted2 = tier2.sortedByDescending { it.first }.map { it.second }

        // Artist cards first, then albums/playlists/misc
        return sorted1 + sorted2 + tier3Artists + tier3Rest
    }

    // ── Top Result card ───────────────────────────────────────────────────────

    /**
     * Picks the single best item for the hero "Top Result" card.
     *
     * Priority order:
     *  1. Query has song-signal words → always pick best song
     *  2. Best song title is an exact match → always pick that song
     *  3. Song title starts with query → prefer song unless artist relevance ≥ 0.95
     *  4. Artist relevance ≥ 0.9 AND no song title starts with query → pick artist
     *  5. Default → best-scored song
     */
    fun pickTopResult(items: List<YTItem>, query: String, likedIds: Set<String>): YTItem? {
        if (items.isEmpty()) return null

        val queryLower  = query.trim().lowercase()
        val songSignal  = querySongSignalStrength(query)

        val bestArtist = items
            .filterIsInstance<ArtistItem>()
            .mapNotNull { a -> artistRelevance(a.title, query).takeIf { it > 0.0 }?.let { it to a } }
            .maxByOrNull { it.first }
        val artistScore = bestArtist?.first ?: 0.0

        val bestSong = items
            .filterIsInstance<SongItem>()
            .mapIndexed { idx, s -> scoreSong(s, query, likedIds, idx) to s }
            .maxByOrNull { it.first }

        val songTitleLower      = bestSong?.second?.title?.lowercase()?.trim() ?: ""
        val songIsExactMatch    = songTitleLower == queryLower
        val songStartsWithQuery = songTitleLower.startsWith(queryLower)

        return when {
            songSignal > 0.0 && bestSong != null                                -> bestSong.second
            songIsExactMatch && bestSong != null                                 -> bestSong.second
            songStartsWithQuery && bestSong != null && artistScore < 0.95        -> bestSong.second
            artistScore >= 0.9 && !songStartsWithQuery && bestArtist != null     -> bestArtist.second
            bestSong != null                                                      -> bestSong.second
            else                                                                  -> items.firstOrNull()
        }
    }
}
