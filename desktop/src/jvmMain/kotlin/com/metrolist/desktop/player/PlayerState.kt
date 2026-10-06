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
import com.metrolist.desktop.cipher.DesktopCipherDeobfuscator
import com.metrolist.desktop.cipher.FunctionNameExtractor
import com.metrolist.desktop.cipher.PlayerJsFetcher
import com.metrolist.desktop.potoken.PoTokenGenerator
import com.metrolist.desktop.potoken.PoTokenResult
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections

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
    var showRightPanel by mutableStateOf(true)
    var lyricsLoading by mutableStateOf(false)
    /**
     * How many ms to advance lyrics AHEAD of the raw mpv position.
     * Compensates for audio output chain latency (Bluetooth codec, DAC, etc).
     * 0 = no compensation, positive = show lyrics earlier.
     */
    var lyricsOffsetMs by mutableStateOf(350L)
    private var lyricsJob: Job? = null

    private var positionJob: Job? = null
    private var resolveJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val poTokenGenerator = PoTokenGenerator()
    private val mpv = MpvAudioPlayer()
    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()

    enum class RepeatMode { OFF, ALL, ONE }

    /**
     * Clients to try for stream resolution, ordered by reliability for desktop.
     * Non-cipher clients first, then cipher clients last.
     */
    private val streamClients = listOf(
        YouTubeClient.IOS,
        YouTubeClient.ANDROID_VR_NO_AUTH,
        YouTubeClient.ANDROID_NO_SDK,
        YouTubeClient.IPADOS,
        YouTubeClient.ANDROID_VR_1_43_32,
        YouTubeClient.WEB_REMIX,
        YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
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
                println("[Player] onTrackEnd fired while loading stream (success=$success) — reporting failure")
                isLoadingStream = false
                isPlaying = false
                if (!success) {
                    streamError = "Stream playback failed"
                }
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

        scope.launch(Dispatchers.IO) {
            try {
                com.metrolist.desktop.cipher.DesktopCipherDeobfuscator.ensureInitialized()
            } catch (e: Exception) {
                println("[Player] Cipher deobfuscator init failed: ${e.message}")
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

        // Priority 1: permanent download (user-initiated)
        val localPath = com.metrolist.desktop.data.DownloadManager.getLocalPath(song.id)
            // Priority 2: stream cache (automatic LRU)
            ?: com.metrolist.desktop.data.StreamCache.getCachedPath(song.id)

        // Resolve stream URL and play via mpv
        resolveJob = scope.launch(Dispatchers.IO) {
            val mediaTitle = song.mediaTitle()
            if (localPath != null) {
                println("[Player] Playing from local file: $localPath")
                mpv.play(localPath, "LOCAL", mediaTitle)
                mpv.setVolume((volume * 100).toInt())
                // isPlaying / isLoadingStream set by onPlaybackStarted
            } else {
                resolveAndPlay(song.id, mediaTitle)
            }
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
    private suspend fun resolveAndPlay(videoId: String, mediaTitle: String?) {
        println("[Player] Resolving stream for videoId=$videoId (parallel, ${streamClients.size} clients)")
        println("[Player] Cipher deobfuscator initialized=${DesktopCipherDeobfuscator.isInitialized()}")

        // Generate PoToken lazily so it never blocks direct URL clients.
        val poTokenDeferred = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            getPoToken(videoId)
        }

        data class StreamResult(val url: String, val clientName: String, val durationMs: Long?)

        val resultChannel = kotlinx.coroutines.channels.Channel<StreamResult>(1)
        val failureReasons = Collections.synchronizedList(mutableListOf<String>())

        // Launch all clients concurrently
        val jobs = streamClients.mapIndexed { index, client ->
            scope.launch(Dispatchers.IO) {
                // Skip clients that require login when we have no cookie.
                if (client.loginRequired && com.metrolist.innertube.YouTube.cookie.isNullOrBlank()) {
                    val reason = "${client.clientName}: skipped (login required, not signed in)"
                    println("[Player] $reason")
                    failureReasons.add(reason)
                    return@launch
                }
                try {
                    println("[Player] Client ${index + 1}: ${client.clientName}")
                    // Get signature timestamp and poToken if required by the client
                    val signatureTimestamp = getSignatureTimestamp(client, videoId)
                    val playerPoToken = if (client.useWebPoTokens) poTokenDeferred.await()?.playerRequestPoToken else null
                    val result = YouTube.player(
                        videoId = videoId,
                        client = client,
                        signatureTimestamp = signatureTimestamp,
                        poToken = if (client.useWebPoTokens) playerPoToken else null
                    )

                    if (result.isFailure) {
                        val msg = result.exceptionOrNull()?.message ?: "unknown API error"
                        println("[Player] ${client.clientName} API failure: $msg")
                        failureReasons.add("${client.clientName}: API failure: $msg")
                        return@launch
                    }

                    val response = result.getOrNull() ?: return@launch
                    val pbStatus = response.playabilityStatus.status
                    val pbReason = response.playabilityStatus.reason
                    println("[Player] ${client.clientName} playability=$pbStatus reason=${pbReason ?: "(none)"}")
                    if (pbStatus != "OK") {
                        failureReasons.add("${client.clientName}: playability=$pbStatus reason=${pbReason ?: "(none)"}")
                        return@launch
                    }

                    // Deobfuscate cipher URLs
                    val deobfuscated = try {
                        YouTube.newPipePlayer(videoId, response) ?: response
                    } catch (_: Exception) { response }

                    val audioFormats = deobfuscated.streamingData?.adaptiveFormats
                        ?.filter { it.isAudio }
                    val directCount = audioFormats?.count { it.url != null } ?: 0
                    val cipherCount = (audioFormats?.size ?: 0) - directCount
                    println("[Player] ${client.clientName} audio formats: ${audioFormats?.size ?: 0} (direct=$directCount, cipher=$cipherCount)")
                    if (audioFormats.isNullOrEmpty()) {
                        failureReasons.add("${client.clientName}: no audio formats")
                        return@launch
                    }

                    suspend fun validateCandidate(candidate: String): String? {
                        return if (validateStreamUrl(candidate, client.clientName)) candidate else null
                    }

                    // Pick best direct URL first
                    var streamUrl: String? = null
                    val directUrl = audioFormats
                        .filter { it.url != null }
                        .maxByOrNull { it.bitrate }?.url
                    if (directUrl != null) {
                        streamUrl = validateCandidate(directUrl)
                    }

                    // Desktop fallback 1: NewPipe per-format deobfuscation
                    if (streamUrl == null) {
                        for (fmt in audioFormats.sortedByDescending { it.bitrate }) {
                            val candidate = try { NewPipeExtractor.getStreamUrl(fmt, videoId) } catch (_: Exception) { null }
                            if (candidate != null) {
                                streamUrl = validateCandidate(candidate)
                            }
                            if (streamUrl != null) {
                                break
                            }
                        }
                    }

                    // Desktop fallback 1b: global NewPipe stream list (match by itag)
                    if (streamUrl == null) {
                        try {
                            val npUrls = YouTube.getNewPipeStreamUrls(videoId)
                            if (npUrls.isNotEmpty()) {
                                for (fmt in audioFormats) {
                                    val candidate = npUrls.find { it.first == fmt.itag }?.second
                                    if (candidate != null) {
                                        streamUrl = validateCandidate(candidate)
                                        if (streamUrl != null) break
                                    }
                                }
                                if (streamUrl == null) {
                                    for (pair in npUrls) {
                                        streamUrl = validateCandidate(pair.second)
                                        if (streamUrl != null) break
                                    }
                                }
                                if (streamUrl != null) println("[Player] NewPipe global fallback provided a stream URL")
                            }
                        } catch (e: Exception) {
                            println("[Player] getNewPipeStreamUrls failed: ${e.message}")
                        }
                    }

                    // Desktop fallback 2: desktop WebView-based cipher + n-transform
                    if (streamUrl == null) {
                        for (fmt in audioFormats.sortedByDescending { it.bitrate }) {
                            if (fmt.signatureCipher == null) continue
                            val deobfuscated = try {
                                DesktopCipherDeobfuscator.deobfuscateFormat(fmt, videoId)
                            } catch (_: Exception) { null }
                            if (deobfuscated != null) {
                                streamUrl = validateCandidate(deobfuscated)
                            }
                            if (streamUrl != null) {
                                break
                            }
                        }
                    }

                    if (streamUrl == null) {
                        println("[Player] ${client.clientName} yielded no playable stream URL")
                        failureReasons.add("${client.clientName}: no playable stream URL")
                        return@launch
                    }

                    val durMs = response.videoDetails?.lengthSeconds?.toLongOrNull()?.let { it * 1000L }
                    println("[Player] ✓ ${client.clientName} resolved first!")
                    resultChannel.trySend(StreamResult(streamUrl, client.clientName, durMs))
                } catch (e: Exception) {
                    val msg = e.message ?: e::class.simpleName ?: "exception"
                    println("[Player] ${client.clientName} exception: $msg")
                    failureReasons.add("${client.clientName}: exception: $msg")
                }
            }
        }

        // Wait for first success or all failures
        val winner = withTimeoutOrNull(15_000) {
            resultChannel.receive()
        }

        // Cancel all remaining in-flight requests
        jobs.forEach { it.cancel() }
        resultChannel.close()

        if (winner == null) {
            println("[Player] All clients failed for videoId=$videoId")
            isLoadingStream = false
            streamError = buildString {
                append("Could not get audio stream")
                if (failureReasons.isNotEmpty()) {
                    append(": ")
                    append(failureReasons.joinToString("; "))
                }
            }
            isPlaying = false
            return
        }

        // Apply n-transform to the stream URL to avoid throttling.
        // Any URL containing an 'n' parameter needs this transform, regardless of client type.
        var finalUrl = winner.url
        val isWebClient = winner.clientName in listOf("WEB", "WEB_REMIX", "WEB_CREATOR", "TVHTML5", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
        if (Regex("[?&]n=[^&]+").containsMatchIn(finalUrl)) {
            try {
                println("[Player] Applying n-transform to stream URL (client=${winner.clientName})...")
                finalUrl = DesktopCipherDeobfuscator.transformN(finalUrl)
            } catch (e: Exception) {
                println("[Player] N-transform failed: ${e.message}")
            }
        }

        // Append pot= parameter with streaming data PoToken (web clients only)
        if (isWebClient) {
            val poTokenResult = try {
                poTokenDeferred.await()
            } catch (e: Exception) {
                println("[Player] PoToken unavailable for stream URL: ${e.message}")
                null
            }

            val streamingDataPoToken = poTokenResult?.streamingDataPoToken
            if (streamingDataPoToken != null) {
                val separator = if ("?" in finalUrl) "&" else "?"
                finalUrl = "${finalUrl}${separator}pot=${java.net.URLEncoder.encode(streamingDataPoToken, "UTF-8")}"
                println("[Player] Appended streaming PoToken to URL")
            }
        }

        winner.durationMs?.let { duration = it }
        println("[Player] Playing URL with client=${winner.clientName} (length=${finalUrl.take(80)}...)")
        mpv.play(finalUrl, winner.clientName, mediaTitle)
        mpv.setVolume((volume * 100).toInt())
        // isPlaying and isLoadingStream will be set by mpv.onPlaybackStarted
        // once mpv actually starts outputting audio

        // Safety timeout: if mpv doesn't start within 30s, report error
        scope.launch {
            kotlinx.coroutines.delay(30_000)
            if (isLoadingStream) {
                println("[Player] Playback timeout: mpv did not start within 30s")
                mpv.stop()
                isLoadingStream = false
                isPlaying = false
                streamError = "Playback timed out — stream may be unavailable"
            }
        }

        // Background-cache the stream so next play is instant / offline
        currentSong?.let { song ->
            com.metrolist.desktop.data.StreamCache.cacheInBackground(
                videoId   = videoId,
                title     = song.title,
                artist    = song.artist,
                albumArt  = song.albumArt,
                durationMs = song.durationMs,
                streamUrl  = winner.url,
                clientName = winner.clientName,
            )
        }

        if (poTokenDeferred.isActive) {
            poTokenDeferred.cancel()
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

    fun stop() {
        mpv.stop()
        isPlaying = false
        currentPosition = 0L
        duration = 0L
        isLoadingStream = false
        streamError = null
        stopPositionUpdates()
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
    /**
     * Attempts to get a signature timestamp for the given videoId if the client requires it.
     * Returns null if timestamp cannot be obtained or client doesn't require it.
     */
    private suspend fun getSignatureTimestamp(client: YouTubeClient, videoId: String): Int? {
        if (!client.useSignatureTimestamp) {
            return null
        }
        
        // Try to get from cipher deobfuscator if initialized
        val cipher = com.metrolist.desktop.cipher.DesktopCipherDeobfuscator
        if (cipher.isInitialized()) {
            // The cipher deobfuscator already analyzed player.js during initialization
            // We could expose the signature timestamp from it, but for now let's extract it fresh
            // to avoid modifying the cipher deobfuscator interface
        }
        
        // Fallback: extract from player.js directly
        return try {
            val playerJs = com.metrolist.desktop.cipher.PlayerJsFetcher.getPlayerJs(forceRefresh = false)?.first
                    ?: com.metrolist.desktop.cipher.PlayerJsFetcher.getPlayerJs(forceRefresh = true)?.first
                    ?: return null
            val hash = com.metrolist.desktop.cipher.PlayerJsFetcher.getPlayerJs(forceRefresh = false)?.second
                    ?: com.metrolist.desktop.cipher.PlayerJsFetcher.getPlayerJs(forceRefresh = true)?.second
                    ?: return null
            com.metrolist.desktop.cipher.FunctionNameExtractor
                .analyzePlayerJs(playerJs, knownHash = hash)
                    .signatureTimestamp
        } catch (e: Exception) {
            println("[Player] Failed to extract signature timestamp: ${e.message}")
            null
        }
    }

    /**
     * Attempts to get both player and streaming poTokens for the given videoId.
     * Returns [PoTokenResult] with both tokens, or null if generation fails.
     */
    private suspend fun getPoToken(videoId: String): PoTokenResult? {
        val sessionId = com.metrolist.innertube.YouTube.dataSyncId
            ?: com.metrolist.innertube.YouTube.visitorData
            ?: run {
                println("[Player] No session ID available for PoToken generation")
                return null
            }
        return try {
            poTokenGenerator.getWebClientPoToken(videoId, sessionId)
        } catch (e: Exception) {
            println("[Player] Failed to get poToken: ${e.message}")
            null
        }
    }

    private suspend fun validateStreamUrl(url: String, clientName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val requestBuilder = Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", playbackUserAgent(clientName))

                if (clientName.contains("WEB")) {
                    requestBuilder.header("Referer", "https://music.youtube.com/")
                    requestBuilder.header("Origin", "https://music.youtube.com")
                }

                val cookie = com.metrolist.innertube.YouTube.cookie
                if (!cookie.isNullOrBlank()) {
                    requestBuilder.header("Cookie", cookie)
                }

                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    val ok = response.isSuccessful
                    if (!ok) {
                        println("[Player] URL validation failed for $clientName (${response.code})")
                    }
                    ok
                }
            } catch (e: Exception) {
                println("[Player] URL validation exception for $clientName: ${e.message}")
                false
            }
        }
    }

    private fun playbackUserAgent(clientName: String): String = when {
        clientName.contains("ANDROID") || clientName.contains("VR") ->
            "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip"
        clientName.contains("IOS") || clientName.contains("IPAD") ->
            "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)"
        else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    fun cleanup() {
        resolveJob?.cancel()
        lyricsJob?.cancel()
        stop()
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

private fun PlayerSong.mediaTitle(): String? = when {
    title.isBlank() && artist.isBlank() -> null
    artist.isBlank() -> title
    title.isBlank() -> artist
    else -> "$title - $artist"
}
