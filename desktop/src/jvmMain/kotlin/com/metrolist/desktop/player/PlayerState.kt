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
    /**
     * How many ms to advance lyrics AHEAD of the raw mpv position.
     * Compensates for audio output chain latency (Bluetooth codec, DAC, etc).
     * 0 = no compensation, positive = show lyrics earlier.
     */
    var lyricsOffsetMs by mutableStateOf(500L)
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
        // Real position from mpv stdout A: lines
        mpv.onPositionUpdate = { posMs ->
            if (isPlaying) {
                currentPosition = posMs
                updateCurrentLyric()
            }
        }

        // Fired when mpv first reports a real position — audio truly started
        mpv.onPlaybackStarted = {
            isPlaying = true
            isLoadingStream = false
            streamError = null
        }

        mpv.onTrackEnd = { success ->
            if (isLoadingStream) {
                println("[Player] onTrackEnd fired while loading stream — ignoring")
            } else {
                isPlaying = false
                if (!success && currentPosition < 1000) {
                    streamError = "Playback failed"
                    isLoadingStream = false
                } else {
                    when (repeatMode) {
                        RepeatMode.ONE -> currentSong?.let { playSong(it) }
                        RepeatMode.ALL -> skipNext()
                        RepeatMode.OFF -> {
                            if (queueIndex < queue.size - 1) skipNext()
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
     * All clients are launched IN PARALLEL — the first one that returns a valid URL wins.
     * This dramatically reduces start-up latency from ~5s (sequential) to ~1s.
     */
    private suspend fun resolveAndPlay(videoId: String) {
        println("[Player] Resolving stream for videoId=$videoId (parallel, ${streamClients.size} clients)")

        data class StreamResult(val url: String, val clientName: String, val durationMs: Long?)

        val resultChannel = kotlinx.coroutines.channels.Channel<StreamResult>(1)

        // Launch all clients concurrently
        val jobs = streamClients.mapIndexed { index, client ->
            scope.launch(Dispatchers.IO) {
                try {
                    println("[Player] Client ${index + 1}: ${client.clientName}")
                    val result = YouTube.player(videoId, client = client)
                    if (result.isFailure) return@launch

                    val response = result.getOrNull() ?: return@launch
                    if (response.playabilityStatus.status != "OK") return@launch

                    // Deobfuscate cipher URLs
                    val deobfuscated = try {
                        YouTube.newPipePlayer(videoId, response) ?: response
                    } catch (_: Exception) { response }

                    val audioFormats = deobfuscated.streamingData?.adaptiveFormats
                        ?.filter { it.isAudio }
                    if (audioFormats.isNullOrEmpty()) return@launch

                    // Pick best direct URL
                    var streamUrl = audioFormats
                        .filter { it.url != null }
                        .maxByOrNull { it.bitrate }?.url

                    // Fallback: per-format NewPipe deobfuscation
                    if (streamUrl == null) {
                        for (fmt in audioFormats.sortedByDescending { it.bitrate }) {
                            streamUrl = try { NewPipeExtractor.getStreamUrl(fmt, videoId) } catch (_: Exception) { null }
                            if (streamUrl != null) break
                        }
                    }

                    if (streamUrl == null) return@launch

                    val durMs = response.videoDetails?.lengthSeconds?.toLongOrNull()?.let { it * 1000L }
                    println("[Player] ✓ ${client.clientName} resolved first!")
                    resultChannel.trySend(StreamResult(streamUrl, client.clientName, durMs))
                } catch (e: Exception) {
                    println("[Player] ${client.clientName} exception: ${e.message}")
                }
            }
        }

        // Wait for first success or all failures
        val winner = withTimeoutOrNull(12_000) {
            resultChannel.receive()
        }

        // Cancel all remaining in-flight requests
        jobs.forEach { it.cancel() }
        resultChannel.close()

        if (winner == null) {
            println("[Player] All clients failed for videoId=$videoId")
            isLoadingStream = false
            streamError = "Could not get audio stream"
            isPlaying = false
            return
        }

        winner.durationMs?.let { duration = it }
        mpv.play(winner.url, winner.clientName)
        mpv.setVolume((volume * 100).toInt())
        // isPlaying and isLoadingStream will be set by mpv.onPlaybackStarted
        // once mpv actually starts outputting audio
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

    /**
     * Append a song to the end of the queue without interrupting playback.
     * If nothing is playing yet, the song is simply queued for when play is triggered.
     */
    fun addToQueue(song: PlayerSong) {
        queue = queue + song
    }

    /** Convenience overload for adding from a [SongItem] directly. */
    fun addToQueue(item: com.metrolist.innertube.models.SongItem) {
        addToQueue(PlayerSong(
            id        = item.id,
            title     = item.title,
            artist    = item.artists.joinToString { it.name },
            albumArt  = item.thumbnail,
            durationMs = (item.duration ?: 210) * 1000L,
        ))
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
        // Position is updated by mpv.onPositionUpdate — nothing to poll
    }

    /** Find the lyric line matching current playback position, with offset compensation */
    private fun updateCurrentLyric() {
        if (lyrics.isEmpty()) return
        val synced = lyrics.any { it.timeMs >= 0 }
        if (synced) {
            // Advance lookup position by lyricsOffsetMs to show lyrics slightly early,
            // compensating for Bluetooth / speaker audio output chain latency.
            val lookupPos = currentPosition + lyricsOffsetMs
            val idx = lyrics.indexOfLast { it.timeMs in 0..lookupPos }
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

