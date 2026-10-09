/**
 * Metrolist Desktop — Audio Stream Cache
 *
 * Automatically caches songs to disk while they stream.
 * Next play: serves from cache (instant, offline).
 * Evicts least-recently-used songs when over the size limit.
 *
 * Cache dir  : ~/.cache/metrolist/audio/
 * Index file : ~/.cache/metrolist/index.json
 */

package com.metrolist.desktop.data

import androidx.compose.runtime.*
import com.metrolist.innertube.NewPipeExtractor
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ── Cache size options ────────────────────────────────────────────────────────

enum class CacheSize(val label: String, val bytes: Long) {
    DISABLED("Disabled",  0L),
    MB_50   ("50 MB",     50L  * 1024 * 1024),
    MB_100  ("100 MB",    100L * 1024 * 1024),
    MB_200  ("200 MB",    200L * 1024 * 1024),
    MB_500  ("500 MB",    500L * 1024 * 1024);

    companion object {
        fun fromMb(mb: Int): CacheSize = entries.firstOrNull { it.bytes == mb * 1024 * 1024L }
            ?: if (mb == 0) DISABLED else MB_100
    }
}

// ── Persisted metadata for a single cached song ────────────────────────────────

@Serializable
data class CacheEntry(
    val id: String,
    val title: String,
    val artist: String,
    val albumArt: String?,
    val durationMs: Long,
    val localPath: String,
    val sizeBytes: Long,
    var lastAccessedMs: Long,
)

// ── Manager ────────────────────────────────────────────────────────────────────

object StreamCache {

    // ─ Compose-observable state ───────────────────────────────────────────────
    var maxCacheSize by mutableStateOf(CacheSize.MB_100)
    val entries = mutableStateListOf<CacheEntry>()

    /** Derived: total bytes used by all cache entries. */
    val usedBytes: Long get() = entries.sumOf { it.sizeBytes }

    /** Derived: how full the cache is, 0.0–1.0 (0 when disabled). */
    val fillFraction: Float
        get() = if (maxCacheSize == CacheSize.DISABLED || maxCacheSize.bytes == 0L) 0f
                else (usedBytes.toFloat() / maxCacheSize.bytes).coerceIn(0f, 1f)

    // ─ Storage paths ─────────────────────────────────────────────────────────
    private val cacheDir  = File(System.getProperty("user.home"), ".cache/metrolist/audio").also { it.mkdirs() }
    private val indexFile = File(System.getProperty("user.home"), ".cache/metrolist/index.json")

    private val json  = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** IDs currently being downloaded to cache (avoid double-download). */
    private val inProgress = mutableSetOf<String>()
    private val pending = LinkedHashMap<String, CacheRequest>()
    private var activeCacheJob: Job? = null
    @Volatile private var playbackActive = false

    private data class CacheRequest(
        val videoId: String,
        val title: String,
        val artist: String,
        val albumArt: String?,
        val durationMs: Long,
        val streamUrl: String,
        val clientName: String,
    )

    // ─ Clients for background stream resolution ───────────────────────────────
    private val streamClients = listOf(
        YouTubeClient.IOS,
        YouTubeClient.ANDROID_VR_NO_AUTH,
        YouTubeClient.ANDROID_NO_SDK,
        YouTubeClient.IPADOS,
        YouTubeClient.ANDROID_VR_1_43_32,
    )

    // ─ Init ───────────────────────────────────────────────────────────────────
    init {
        loadIndex()
    }

    private fun loadIndex() {
        if (!indexFile.exists()) return
        try {
            val list = json.decodeFromString<List<CacheEntry>>(indexFile.readText())
            // Prune entries whose files no longer exist
            val valid = list.filter { File(it.localPath).exists() }
            entries.addAll(valid)
            println("[Cache] Loaded ${valid.size} entries (${usedBytes / 1024 / 1024} MB)")
        } catch (e: Exception) {
            println("[Cache] Failed to load index: ${e.message}")
        }
    }

    private fun saveIndex() {
        try {
            indexFile.parentFile?.mkdirs()
            indexFile.writeText(json.encodeToString(entries.toList()))
        } catch (e: Exception) {
            println("[Cache] Failed to save index: ${e.message}")
        }
    }

    // ─ Public API ──────────────────────────────────────────────────────────────

    /**
     * Returns the local file path for [videoId] if it is cached, and
     * bumps its lastAccessedMs so it stays fresh in the LRU order.
     */
    fun getCachedPath(videoId: String): String? {
        if (maxCacheSize == CacheSize.DISABLED) return null
        val entry = entries.find { it.id == videoId } ?: return null
        val file = File(entry.localPath)
        if (!file.exists()) {
            // Stale — remove from index
            entries.removeIf { it.id == videoId }
            saveIndex()
            return null
        }
        // Touch LRU timestamp
        val idx = entries.indexOfFirst { it.id == videoId }
        if (idx >= 0) {
            entries[idx] = entries[idx].copy(lastAccessedMs = System.currentTimeMillis())
            saveIndex()
        }
        return entry.localPath
    }

    /**
     * Fire-and-forget: caches [streamUrl] for [videoId] in the background.
     * Does nothing if caching is disabled, already cached, or already in progress.
     */
    fun cacheInBackground(
        videoId: String,
        title: String,
        artist: String,
        albumArt: String?,
        durationMs: Long,
        streamUrl: String,
        clientName: String,
    ) {
        if (maxCacheSize == CacheSize.DISABLED) return
        synchronized(pending) {
            if (entries.any { it.id == videoId } || inProgress.contains(videoId)) return
            pending[videoId] = CacheRequest(videoId, title, artist, albumArt, durationMs, streamUrl, clientName)
        }
        drainPending()
    }

    /** Playback always wins over opportunistic cache traffic. */
    fun setPlaybackActive(active: Boolean) {
        playbackActive = active
        if (active) activeCacheJob?.cancel()
        else drainPending()
    }

    private fun drainPending() {
        if (playbackActive || maxCacheSize == CacheSize.DISABLED) return
        val request = synchronized(pending) {
            val next = pending.entries.firstOrNull()?.value ?: return
            pending.remove(next.videoId)
            if (!inProgress.add(next.videoId)) return
            next
        }

        activeCacheJob = scope.launch {
            try {
                val safeTitle = request.title.replace(Regex("[/\\\\:*?\"<>|]"), "_")
                val outFile = File(cacheDir, "${safeTitle}-${request.videoId}.opus")

                println("[Cache] Caching '${request.title}' in background...")
                downloadFile(request.streamUrl, outFile, request.clientName)

                val entry = CacheEntry(
                    id             = request.videoId,
                    title          = request.title,
                    artist         = request.artist,
                    albumArt       = request.albumArt,
                    durationMs     = request.durationMs,
                    localPath      = outFile.absolutePath,
                    sizeBytes      = outFile.length(),
                    lastAccessedMs = System.currentTimeMillis(),
                )
                entries.add(0, entry)
                saveIndex()
                println("[Cache] ✓ Cached '${request.title}' (${outFile.length() / 1024} KB)")

                // Evict if over limit
                evictToLimit()
            } catch (e: Exception) {
                println("[Cache] ✗ Failed to cache '${request.title}': ${e.message}")
            } finally {
                synchronized(pending) { inProgress.remove(request.videoId) }
                activeCacheJob = null
                drainPending()
            }
        }
    }

    /** Evict least-recently-used songs until total size is under [maxCacheSize]. */
    fun evictToLimit() {
        if (maxCacheSize == CacheSize.DISABLED) return
        val limit = maxCacheSize.bytes
        if (usedBytes <= limit) return

        // Sort by oldest access time — those go first
        val sorted = entries.sortedBy { it.lastAccessedMs }.toMutableList()
        while (usedBytes > limit && sorted.isNotEmpty()) {
            val victim = sorted.removeFirst()
            try { File(victim.localPath).delete() } catch (_: Exception) {}
            entries.removeIf { it.id == victim.id }
            println("[Cache] Evicted '${victim.title}' (LRU, freed ${victim.sizeBytes / 1024} KB)")
        }
        saveIndex()
    }

    /** Clear the entire cache: delete all files and reset the index. */
    fun clearAll() {
        entries.toList().forEach { entry ->
            try { File(entry.localPath).delete() } catch (_: Exception) {}
        }
        entries.clear()
        saveIndex()
        println("[Cache] Cleared all cached audio")
    }

    /** Apply a new cache size limit (evicts immediately if new limit is smaller). */
    fun applyLimit(newSize: CacheSize) {
        maxCacheSize = newSize
        evictToLimit()
    }

    // ─ Private helpers ────────────────────────────────────────────────────────

    private suspend fun downloadFile(url: String, dest: File, clientName: String = "IOS") {
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout    = 120_000
            // Use a UA that matches the client which produced the stream URL so YouTube doesn't 403 us.
            val userAgent = when {
                clientName.contains("ANDROID") || clientName.contains("VR") ->
                    "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip"
                clientName.contains("IOS") || clientName.contains("IPAD") ->
                    "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)"
                else -> YouTubeClient.USER_AGENT_WEB
            }
            connection.setRequestProperty("User-Agent", userAgent)
            connection.connect()

            val temporary = File(dest.parentFile, "${dest.name}.part")
            try {
                val buffer = ByteArray(64 * 1024)
                dest.parentFile?.mkdirs()
                connection.inputStream.use { input ->
                    temporary.outputStream().use { output ->
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            currentCoroutineContext().ensureActive()
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (!temporary.renameTo(dest)) {
                    throw IllegalStateException("Could not publish completed cache file")
                }
            } finally {
                connection.disconnect()
                if (temporary.exists() && !dest.exists()) temporary.delete()
            }
        }
    }
}
