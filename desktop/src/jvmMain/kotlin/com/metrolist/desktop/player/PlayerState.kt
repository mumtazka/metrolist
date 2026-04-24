/**
 * Metrolist Desktop — Player State Manager
 * Manages playback state, resolves stream URLs via YouTube API,
 * plays audio through mpv, and fetches lyrics.
 */

package com.metrolist.desktop.player

import androidx.compose.runtime.*
import com.metrolist.innertube.NewPipeExtractor
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YouTubeClient
import kotlinx.coroutines.*

data class PlayerSong(
    val id: String,
    val title: String,
    val artist: String,
    val albumArt: String?,
    val durationMs: Long,
)

class PlayerState {
    var currentSong by mutableStateOf<PlayerSong?>(null)
    var isPlaying by mutableStateOf(false)
    var currentPosition by mutableStateOf(0L)
    var duration by mutableStateOf(0L)
    
    private var _volume by mutableStateOf(0.7f)
    var volume: Float
        get() = _volume
        set(value) {
            _volume = value
            mpv.setVolume((value * 100).toInt())
        }

    var queue by mutableStateOf(listOf<PlayerSong>())
    var queueIndex by mutableStateOf(0)
    var isShuffled by mutableStateOf(false)
    var repeatMode by mutableStateOf(RepeatMode.OFF)
    var streamError by mutableStateOf<String?>(null)
    var isLoadingStream by mutableStateOf(false)

    // Lyrics
    var lyrics by mutableStateOf<List<LyricLine>>(emptyList())
    var currentLyricIndex by mutableStateOf(-1)
    var showLyrics by mutableStateOf(false)
    var showQueue by mutableStateOf(false)
    var lyricsLoading by mutableStateOf(false)
    private var lyricsJob: Job? = null

    private var positionJob: Job? = null
    private var resolveJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mpv = MpvAudioPlayer()

    enum class RepeatMode { OFF, ALL, ONE }

    /**
     * Clients to try for stream resolution, ordered by reliability for desktop.
     * IOS and VR clients return direct URLs (no cipher) and don't need PoTokens.
     */
    private val streamClients = listOf(
        YouTubeClient.IOS,
        YouTubeClient.ANDROID_VR_NO_AUTH,
        YouTubeClient.ANDROID_NO_SDK,
        YouTubeClient.IPADOS,
        YouTubeClient.WEB_REMIX,
    )

    init {
        mpv.onTrackEnd = { success ->
            // Guard: don't skip if we're currently loading a new stream
            if (isLoadingStream) {
                println("[Player] onTrackEnd fired while loading stream — ignoring")
            } else {
                isPlaying = false
                if (!success && currentPosition < 1000) {
                    // Failed immediately upon starting — show error, don't loop
                    streamError = "Playback failed"
                    isLoadingStream = false
                    stopPositionUpdates()
                } else {
                    // Track ended naturally or after playing a while
                    when (repeatMode) {
                        RepeatMode.ONE -> currentSong?.let { playSong(it) }
                        RepeatMode.ALL -> skipNext()
                        RepeatMode.OFF -> {
                            if (queueIndex < queue.size - 1) skipNext()
                            else stopPositionUpdates()
                        }
                    }
                }
            }
        }

    }

    fun playSongItem(item: SongItem) {
        val song = PlayerSong(
            id = item.id,
            title = item.title,
            artist = item.artists.joinToString { it.name },
            albumArt = item.thumbnail,
            durationMs = (item.duration ?: 210) * 1000L,
        )
        playSong(song)
    }

    fun playSong(song: PlayerSong) {
        // Cancel any previous resolve/lyrics jobs
        resolveJob?.cancel()
        lyricsJob?.cancel()

        currentSong = song
        duration = song.durationMs
        currentPosition = 0L
        isPlaying = false
        streamError = null
        isLoadingStream = true
        lyrics = emptyList()
        currentLyricIndex = -1

        // Resolve stream URL and play via mpv
        resolveJob = scope.launch(Dispatchers.IO) {
            resolveAndPlay(song.id)
        }

        // Fetch lyrics in parallel (don't block playback)
        lyricsJob = scope.launch(Dispatchers.IO) {
            lyricsLoading = true
            val durationSec = (song.durationMs / 1000).toInt()
            val result = LyricsProvider.fetchLyrics(song.title, song.artist, durationSec)
            if (result != null) {
                lyrics = result
                println("[Lyrics] Loaded ${result.size} lines for '${song.title}'")
            } else {
                println("[Lyrics] No lyrics found for '${song.title}'")
            }
            lyricsLoading = false
        }
    }

    /**
     * Try multiple YouTube API clients to find a working audio stream URL.
     * Uses NewPipe cipher deobfuscation for streams without direct URLs.
     * Validates each URL with an HTTP HEAD request before passing to mpv.
     */
    private suspend fun resolveAndPlay(videoId: String) {
        println("[Player] Resolving stream for videoId=$videoId")

        for ((index, client) in streamClients.withIndex()) {
            try {
                println("[Player] Trying client ${index + 1}/${streamClients.size}: ${client.clientName}")
                val result = YouTube.player(videoId, client = client)

                if (result.isFailure) {
                    println("[Player]   FAILED: ${result.exceptionOrNull()?.message}")
                    continue
                }

                val response = result.getOrNull() ?: continue
                if (response.playabilityStatus.status != "OK") {
                    println("[Player]   Status: ${response.playabilityStatus.status} — ${response.playabilityStatus.reason}")
                    continue
                }

                // Step 1: Try to use NewPipe to deobfuscate all cipher URLs
                val deobfuscatedResponse = try {
                    YouTube.newPipePlayer(videoId, response) ?: response
                } catch (e: Exception) {
                    println("[Player]   NewPipe deobfuscation failed: ${e.message}")
                    response
                }

                // Step 2: Find audio formats — prefer ones with direct URLs
                val allAudioFormats = deobfuscatedResponse.streamingData?.adaptiveFormats
                    ?.filter { it.isAudio }

                if (allAudioFormats.isNullOrEmpty()) {
                    println("[Player]   No audio formats found")
                    continue
                }

                // Try formats with direct URLs first (post-deobfuscation)
                var streamUrl: String? = null
                var usedClientName = client.clientName

                val formatsWithUrl = allAudioFormats.filter { it.url != null }
                if (formatsWithUrl.isNotEmpty()) {
                    val bestFormat = formatsWithUrl.maxByOrNull { it.bitrate }
                    streamUrl = bestFormat?.url
                    println("[Player]   Found direct URL: bitrate=${bestFormat?.bitrate}")
                }

                // Step 3: If no direct URL, try per-format deobfuscation via NewPipe
                if (streamUrl == null) {
                    println("[Player]   No direct URLs, trying per-format NewPipe deobfuscation...")
                    for (format in allAudioFormats.sortedByDescending { it.bitrate }) {
                        val deobfuscatedUrl = try {
                            NewPipeExtractor.getStreamUrl(format, videoId)
                        } catch (e: Exception) {
                            null
                        }
                        if (deobfuscatedUrl != null) {
                            streamUrl = deobfuscatedUrl
                            println("[Player]   Deobfuscated URL for itag=${format.itag}, bitrate=${format.bitrate}")
                            break
                        }
                    }
                }

                // Step 4: Last resort — get URLs from NewPipe's StreamInfo directly
                if (streamUrl == null) {
                    println("[Player]   Trying NewPipe StreamInfo fallback...")
                    try {
                        val streamUrls = YouTube.getNewPipeStreamUrls(videoId)
                        if (streamUrls.isNotEmpty()) {
                            // Match by itag to find an audio stream
                            for (format in allAudioFormats.sortedByDescending { it.bitrate }) {
                                val matchedUrl = streamUrls.find { it.first == format.itag }?.second
                                if (matchedUrl != null) {
                                    streamUrl = matchedUrl
                                    usedClientName = "WEB" // NewPipe uses WEB client
                                    println("[Player]   Got URL from StreamInfo for itag=${format.itag}")
                                    break
                                }
                            }
                            // If no itag match, just use the first audio URL
                            if (streamUrl == null && streamUrls.isNotEmpty()) {
                                streamUrl = streamUrls.first().second
                                usedClientName = "WEB"
                                println("[Player]   Using first StreamInfo URL (itag=${streamUrls.first().first})")
                            }
                        }
                    } catch (e: Exception) {
                        println("[Player]   StreamInfo fallback failed: ${e.message}")
                    }
                }

                if (streamUrl == null) {
                    println("[Player]   No playable URL found for client ${client.clientName}")
                    continue
                }

                // Skip HEAD validation — mpv handles retries and the validation
                // adds 2-5 seconds of latency. If the URL fails, mpv will report it
                // and onTrackEnd will fire, letting us try the next approach.
                println("[Player]   ✓ Stream found! Playing with client=$usedClientName")

                // Update duration from API response
                response.videoDetails?.lengthSeconds?.toLongOrNull()?.let {
                    duration = it * 1000L
                }

                // Play via mpv
                mpv.play(streamUrl, usedClientName)
                mpv.setVolume((volume * 100).toInt())
                isPlaying = true
                isLoadingStream = false
                streamError = null
                startPositionUpdates()
                return

            } catch (e: Exception) {
                println("[Player]   Exception: ${e.message}")
                continue
            }
        }

        // All clients failed
        println("[Player] All clients failed for videoId=$videoId")
        isLoadingStream = false
        streamError = "Could not get audio stream"
        isPlaying = false
    }

    fun playQueue(songs: List<PlayerSong>, startIndex: Int = 0) {
        queue = songs
        queueIndex = startIndex
        playSong(songs[startIndex])
    }

    fun togglePlayPause() {
        if (isPlaying) {
            mpv.togglePause()
            isPlaying = false
            stopPositionUpdates()
        } else {
            mpv.togglePause()
            isPlaying = true
            startPositionUpdates()
        }
    }

    fun seekTo(position: Long) {
        currentPosition = position.coerceIn(0L, duration)
        mpv.seekTo(position / 1000.0)
    }

    fun skipNext() {
        if (queue.isNotEmpty() && queueIndex < queue.size - 1) {
            queueIndex++
            playSong(queue[queueIndex])
        }
    }

    fun skipPrevious() {
        if (currentPosition > 3000) {
            seekTo(0)
        } else if (queue.isNotEmpty() && queueIndex > 0) {
            queueIndex--
            playSong(queue[queueIndex])
        }
    }

    fun resume() {
        if (!isPlaying) {
            mpv.togglePause()
            isPlaying = true
            startPositionUpdates()
        }
    }

    fun pause() {
        if (isPlaying) {
            mpv.togglePause()
            isPlaying = false
            stopPositionUpdates()
        }
    }

    /** Play a song by its YouTube video ID (creates a minimal PlayerSong). */
    fun playSongById(videoId: String) {
        val song = PlayerSong(
            id        = videoId,
            title     = "Loading…",
            artist    = "",
            albumArt  = null,
            durationMs = 0L,
        )
        playSong(song)
    }

    fun toggleShuffle() { isShuffled = !isShuffled }

    fun cycleRepeat() {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    /** Move a queue item from [from] to [to] index and keep queueIndex tracking correct. */
    fun reorderQueue(from: Int, to: Int) {
        if (from == to) return
        val list = queue.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        queue = list
        // Keep queueIndex pointing at the same song
        queueIndex = when {
            from == queueIndex -> to
            from < queueIndex && to >= queueIndex -> queueIndex - 1
            from > queueIndex && to <= queueIndex -> queueIndex + 1
            else -> queueIndex
        }
    }

    /** Remove a song at [index] from the queue. */
    fun removeFromQueue(index: Int) {
        if (index < 0 || index >= queue.size) return
        val list = queue.toMutableList()
        list.removeAt(index)
        queue = list
        when {
            index < queueIndex -> queueIndex = (queueIndex - 1).coerceAtLeast(0)
            index == queueIndex && list.isNotEmpty() -> playSong(list[queueIndex.coerceAtMost(list.size - 1)])
            else -> {}
        }
    }

    val progressFraction: Float
        get() = if (duration > 0) (currentPosition.toFloat() / duration) else 0f

    val currentTimeFormatted: String
        get() = formatTime(currentPosition)

    val durationFormatted: String
        get() = formatTime(duration)

    private fun startPositionUpdates() {
        stopPositionUpdates()
        positionJob = scope.launch {
            while (isActive) {
                delay(250)
                if (isPlaying && mpv.isRunning && currentPosition < duration) {
                    currentPosition += 250
                    // Update current lyric line based on position
                    updateCurrentLyric()
                }
            }
        }
    }

    /** Find the lyric line matching current playback position */
    private fun updateCurrentLyric() {
        if (lyrics.isEmpty()) return
        // For synced lyrics (timeMs >= 0), find the latest line that started
        val synced = lyrics.any { it.timeMs >= 0 }
        if (synced) {
            val pos = currentPosition
            val idx = lyrics.indexOfLast { it.timeMs in 0..pos }
            if (idx != currentLyricIndex) {
                currentLyricIndex = idx
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    fun cleanup() {
        resolveJob?.cancel()
        lyricsJob?.cancel()
        mpv.stop()
        scope.cancel()
    }

    companion object {
        fun formatTime(ms: Long): String {
            val totalSeconds = ms / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }
    }
}

