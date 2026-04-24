/**
 * Desktop lyrics provider — fetches synced/plain lyrics from LrcLib.net API
 */

package com.metrolist.desktop.player

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LrcLibTrack(
    val id: Long = 0,
    val name: String = "",
    @SerialName("trackName") val trackName: String? = null,
    @SerialName("artistName") val artistName: String? = null,
    @SerialName("albumName") val albumName: String? = null,
    val duration: Double = 0.0,
    val instrumental: Boolean = false,
    @SerialName("plainLyrics") val plainLyrics: String? = null,
    @SerialName("syncedLyrics") val syncedLyrics: String? = null,
)

/** A single timed line of lyrics */
data class LyricLine(
    val timeMs: Long,
    val text: String,
)

object LyricsProvider {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
    }

    /**
     * Fetch lyrics from lrclib.net for a given song.
     * Returns synced lyrics lines if available, otherwise plain lyrics split by lines.
     */
    suspend fun fetchLyrics(
        title: String,
        artist: String,
        durationSeconds: Int,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        try {
            val tracks: List<LrcLibTrack> = client.get("https://lrclib.net/api/search") {
                parameter("track_name", title)
                parameter("artist_name", artist)
                header("User-Agent", "Metrolist Desktop/1.0.0 (https://github.com/mumtazka/metrolist)")
            }.body()

            // Find best match by duration (within ±5 seconds)
            val match = tracks.firstOrNull { track ->
                !track.instrumental && kotlin.math.abs(track.duration - durationSeconds) <= 5
            } ?: tracks.firstOrNull { track -> !track.instrumental }

            if (match == null) return@withContext null

            // Prefer synced lyrics
            val synced = match.syncedLyrics
            if (!synced.isNullOrBlank()) {
                return@withContext parseSyncedLyrics(synced)
            }

            // Fall back to plain lyrics
            val plain = match.plainLyrics
            if (!plain.isNullOrBlank()) {
                return@withContext plain.lines()
                    .filter { it.isNotBlank() }
                    .mapIndexed { i, text -> LyricLine(timeMs = -1, text = text) }
            }

            null
        } catch (e: Exception) {
            println("[Lyrics] Error fetching: ${e.message}")
            null
        }
    }

    /** Parse LRC format: [mm:ss.xx] text */
    private fun parseSyncedLyrics(lrc: String): List<LyricLine> {
        val regex = Regex("""\[(\d+):(\d+)\.(\d+)](.*)""")
        return lrc.lines().mapNotNull { line ->
            regex.find(line)?.let { match ->
                val min = match.groupValues[1].toLongOrNull() ?: 0
                val sec = match.groupValues[2].toLongOrNull() ?: 0
                val ms = match.groupValues[3].toLongOrNull()?.let {
                    // Handle both .xx (centiseconds) and .xxx (milliseconds)
                    if (it < 100) it * 10 else it
                } ?: 0
                val text = match.groupValues[4].trim()
                LyricLine(timeMs = min * 60_000 + sec * 1000 + ms, text = text)
            }
        }.sortedBy { it.timeMs }
    }
}
