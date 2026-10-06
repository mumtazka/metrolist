package com.metrolist.desktop.importer

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

class PlaylistMatcher {
    suspend fun match(tracks: List<ExternalTrack>): List<MatchedTrack> = withContext(Dispatchers.IO) {
        tracks.map { source ->
            val candidates = YouTube.searchSummary("${source.title} ${source.artist}")
                .getOrNull()?.summaries.orEmpty().flatMap { it.items }.filterIsInstance<SongItem>()
            val ranked = candidates.map { candidate ->
                val titleScore = similarity(source.title, candidate.title)
                val artistScore = similarity(source.artist, candidate.artists.joinToString { it.name })
                val durationScore = source.durationMs?.let { duration ->
                    1.0 - (abs(duration - (candidate.duration ?: 210) * 1000L) / 30_000.0).coerceAtMost(1.0)
                } ?: 0.5
                candidate to (titleScore * 0.55 + artistScore * 0.3 + durationScore * 0.15)
            }.sortedByDescending { it.second }
            val best = ranked.firstOrNull()
            val secondScore = ranked.getOrNull(1)?.second ?: 0.0
            val ambiguous = best != null && (best.second < 0.62 || best.second - secondScore < 0.08)
            delay(50)
            MatchedTrack(
                source = source,
                youtubeId = best?.first?.id,
                youtubeTitle = best?.first?.title,
                youtubeArtist = best?.first?.artists?.joinToString { it.name },
                albumArt = best?.first?.thumbnail,
                durationMs = ((best?.first?.duration ?: 210) * 1000L),
                ambiguous = ambiguous,
            )
        }
    }

    private fun similarity(left: String, right: String): Double {
        val a = normalize(left)
        val b = normalize(right)
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0
        val common = a.split(' ').intersect(b.split(' ').toSet()).size
        return (common.toDouble() / maxOf(a.split(' ').size, b.split(' ').size)).coerceIn(0.0, 1.0)
    }

    private fun normalize(value: String) = value.lowercase()
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
