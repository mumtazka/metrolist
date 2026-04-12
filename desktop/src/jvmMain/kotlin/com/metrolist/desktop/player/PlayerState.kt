/**
 * Metrolist Desktop — Player State Manager
 * Manages playback state, resolves stream URLs via YouTube API,
 * and plays audio through mpv.
 */

package com.metrolist.desktop.player

import androidx.compose.runtime.*
import com.metrolist.innertube.NewPipeExtractor
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YouTubeClient
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

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
        // Cancel any previous resolve job
        resolveJob?.cancel()

        currentSong = song
        duration = song.durationMs
        currentPosition = 0L
        isPlaying = false
        streamError = null
        isLoadingStream = true

        // Resolve stream URL and play via mpv
        resolveJob = scope.launch(Dispatchers.IO) {
            resolveAndPlay(song.id)
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

                // Validate the URL with an HTTP HEAD request before passing to mpv
                if (!validateStreamUrl(streamUrl, client)) {
                    println("[Player]   Stream URL validation failed (likely 403)")
                    continue
                }

                println("[Player]   ✓ Stream validated! Playing with client=$usedClientName")

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


    /**
     * Validate a stream URL via HTTP HEAD request.
     * Returns true if the server responds with 2xx.
     */
    private fun validateStreamUrl(url: String, client: YouTubeClient): Boolean {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            // Match the User-Agent that mpv will use
            val userAgent = when {
                client.clientName.contains("ANDROID") || client.clientName.contains("VR") ->
                    "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip"
                client.clientName.contains("IOS") || client.clientName.contains("IPAD") ->
                    "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)"
                else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
            }
            connection.setRequestProperty("User-Agent", userAgent)

            if (client.clientName.contains("WEB")) {
                connection.setRequestProperty("Referer", "https://music.youtube.com/")
                connection.setRequestProperty("Origin", "https://music.youtube.com")
            }

            val code = connection.responseCode
            connection.disconnect()
            println("[Player]   HTTP HEAD response: $code")
            code in 200..299
        } catch (e: Exception) {
            println("[Player]   HTTP HEAD failed: ${e.message}")
            false
        }
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

    fun toggleShuffle() { isShuffled = !isShuffled }
    fun cycleRepeat() {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
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
                }
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    fun cleanup() {
        resolveJob?.cancel()
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

