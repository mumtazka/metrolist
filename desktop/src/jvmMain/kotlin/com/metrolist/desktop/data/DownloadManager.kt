/**
 * Metrolist Desktop — Download Manager
 * Resolves stream URLs and downloads audio to ~/Music/Metrolist/.
 * Persists download metadata to ~/.config/metrolist/downloads.json.
 */

package com.metrolist.desktop.data

import androidx.compose.runtime.*
import com.metrolist.desktop.player.PlayerSong
import com.metrolist.innertube.NewPipeExtractor
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

enum class DownloadState { QUEUED, DOWNLOADING, DONE, ERROR }

object DownloadManager {

    // ── Compose-observable state ───────────────────────────────────────────────
    val downloads      = mutableStateMapOf<String, DownloadState>()
    val progress       = mutableStateMapOf<String, Float>()          // 0.0–1.0
    val downloadedSongs = mutableStateListOf<DownloadedSong>()

    // ── Storage paths ─────────────────────────────────────────────────────────
    private val musicDir  = File(System.getProperty("user.home"), "Music/Metrolist").also { it.mkdirs() }
    private val configDir = File(System.getProperty("user.home"), ".config/metrolist").also { it.mkdirs() }
    private val metaFile  = File(configDir, "downloads.json")

    private val json  = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Clients to try for stream URL resolution (same order as PlayerState)
    private val streamClients = listOf(
        YouTubeClient.IOS,
        YouTubeClient.ANDROID_VR_NO_AUTH,
        YouTubeClient.ANDROID_NO_SDK,
        YouTubeClient.IPADOS,
        YouTubeClient.WEB_REMIX,
        YouTubeClient.ANDROID_VR_1_43_32,
        YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
    )

    // ── Init: load persisted metadata ─────────────────────────────────────────
    init {
        loadMetadata()
    }

    private fun loadMetadata() {
        if (!metaFile.exists()) return
        try {
            val list = json.decodeFromString<List<DownloadedSong>>(metaFile.readText())
            // Only keep entries whose files still exist on disk
            val valid = list.filter { File(it.localPath).exists() }
            downloadedSongs.addAll(valid)
            valid.forEach { downloads[it.id] = DownloadState.DONE }
        } catch (e: Exception) {
            println("[DL] Failed to load metadata: ${e.message}")
        }
    }

    private fun saveMetadata() {
        try {
            metaFile.writeText(json.encodeToString(downloadedSongs.toList()))
        } catch (e: Exception) {
            println("[DL] Failed to save metadata: ${e.message}")
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    fun isDownloaded(videoId: String) = downloads[videoId] == DownloadState.DONE
    fun getLocalPath(videoId: String) = downloadedSongs.find { it.id == videoId }?.localPath

    fun downloadSong(song: PlayerSong) {
        if (downloads[song.id] == DownloadState.DOWNLOADING ||
            downloads[song.id] == DownloadState.DONE) return

        downloads[song.id] = DownloadState.QUEUED
        progress[song.id]  = 0f

        scope.launch {
            try {
                downloads[song.id] = DownloadState.DOWNLOADING

                // 1. Resolve stream URL
                val url = resolveStreamUrl(song.id)
                    ?: throw Exception("Could not resolve stream URL")

                // 2. Sanitize filename and pick output path
                val safeTitle = song.title.replace(Regex("[/\\\\:*?\"<>|]"), "_")
                val outFile   = File(musicDir, "${safeTitle}-${song.id}.opus")

                // 3. Download with progress tracking
                downloadFile(url, outFile) { fraction ->
                    progress[song.id] = fraction
                }

                // 4. Persist metadata
                val record = DownloadedSong(
                    id           = song.id,
                    title        = song.title,
                    artist       = song.artist,
                    albumArt     = song.albumArt,
                    durationMs   = song.durationMs,
                    localPath    = outFile.absolutePath,
                    downloadedAt = System.currentTimeMillis(),
                )
                downloadedSongs.removeIf { it.id == song.id }
                downloadedSongs.add(0, record)
                saveMetadata()

                downloads[song.id] = DownloadState.DONE
                progress[song.id]  = 1f
                println("[DL] ✓ Downloaded '${song.title}' → ${outFile.absolutePath}")

            } catch (e: Exception) {
                println("[DL] ✗ Failed '${song.title}': ${e.message}")
                downloads[song.id] = DownloadState.ERROR
                progress[song.id]  = 0f
            }
        }
    }

    fun downloadSongs(songs: List<PlayerSong>) {
        songs.forEach(::downloadSong)
    }

    fun deleteSong(videoId: String) {
        val record = downloadedSongs.find { it.id == videoId } ?: return
        try { File(record.localPath).delete() } catch (_: Exception) {}
        downloadedSongs.removeIf { it.id == videoId }
        downloads.remove(videoId)
        progress.remove(videoId)
        saveMetadata()
        println("[DL] Deleted '${record.title}'")
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private suspend fun resolveStreamUrl(videoId: String): String? {
        data class Hit(val url: String)
        val channel = kotlinx.coroutines.channels.Channel<Hit>(1)

        val jobs = streamClients.map { client ->
            scope.launch {
                try {
                    val result = YouTube.player(videoId, client = client)
                    if (result.isFailure) return@launch
                    val response = result.getOrNull() ?: return@launch
                    if (response.playabilityStatus.status != "OK") return@launch

                    val deobfuscated = try {
                        YouTube.newPipePlayer(videoId, response) ?: response
                    } catch (_: Exception) { response }

                    val audioFormats = deobfuscated.streamingData?.adaptiveFormats?.filter { it.isAudio }
                    if (audioFormats.isNullOrEmpty()) return@launch

                    var url = audioFormats.filter { it.url != null }.maxByOrNull { it.bitrate }?.url
                    if (url == null) {
                        for (fmt in audioFormats.sortedByDescending { it.bitrate }) {
                            url = try { NewPipeExtractor.getStreamUrl(fmt, videoId) } catch (_: Exception) { null }
                            if (url != null) break
                        }
                    }
                    if (url != null) channel.trySend(Hit(url))
                } catch (e: Exception) {
                    println("[DL] ${client.clientName} error: ${e.message}")
                }
            }
        }

        val result = withTimeoutOrNull(15_000) { channel.receive() }
        jobs.forEach { it.cancel() }
        channel.close()
        return result?.url
    }

    private suspend fun downloadFile(url: String, dest: File, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout    = 60_000
            connection.connect()

            val totalBytes = connection.contentLengthLong
            var downloaded = 0L
            val buffer     = ByteArray(64 * 1024) // 64 KB chunks

            dest.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            onProgress(downloaded.toFloat() / totalBytes)
                        }
                    }
                }
            }
            connection.disconnect()
        }
    }
}
