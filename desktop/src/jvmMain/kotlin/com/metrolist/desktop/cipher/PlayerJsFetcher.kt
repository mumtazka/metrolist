package com.metrolist.desktop.cipher

import com.metrolist.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

object PlayerJsFetcher {
    private const val TAG = "Metrolist_CipherFetcher"
    private const val IFRAME_API_URL = "https://www.youtube.com/iframe_api"
    private const val PLAYER_JS_URL_TEMPLATE = "https://www.youtube.com/s/player/%s/player_ias.vflset/en_GB/base.js"
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()

    // iframe_api embeds the active player path as /s/player/<hash>/...
    private val PLAYER_HASH_REGEX = Regex("""/s/player/([a-zA-Z0-9_-]+)/""")

    private fun getCacheDir(): File = File(System.getProperty("user.home"), ".cache/metrolist/cipher")
    private fun getCacheFile(hash: String): File = File(getCacheDir(), "player_$hash.js")
    private fun getHashFile(): File = File(getCacheDir(), "current_hash.txt")

    suspend fun getPlayerJs(forceRefresh: Boolean = false): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val cacheDir = getCacheDir()
            if (!cacheDir.exists()) cacheDir.mkdirs()

            if (!forceRefresh) {
                val cached = readFromCache(allowExpired = false)
                if (cached != null) return@withContext cached
            }

            val hash = fetchPlayerHash()
            if (hash == null) {
                val staleCached = readFromCache(allowExpired = true)
                if (staleCached != null) return@withContext staleCached
                return@withContext null
            }
            val playerJs = downloadPlayerJs(hash)
            if (playerJs == null) {
                val staleCached = readFromCache(allowExpired = true)
                if (staleCached != null) return@withContext staleCached
                return@withContext null
            }

            writeToCache(hash, playerJs)
            Pair(playerJs, hash)
        } catch (e: Exception) {
            println("[Cipher] PlayerJsFetcher error: ${e.message}")
            null
        }
    }

    fun invalidateCache() {
        try {
            val cacheDir = getCacheDir()
            if (cacheDir.exists()) {
                cacheDir.listFiles()?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            println("[Cipher] Cache invalidation failed: ${e.message}")
        }
    }

    private fun readFromCache(allowExpired: Boolean): Pair<String, String>? {
        return try {
            val hashFile = getHashFile()
            if (!hashFile.exists()) return null

            val lines = hashFile.readText().split("\n")
            if (lines.size < 2) return null

            val hash = lines[0]
            val timestamp = lines[1].toLongOrNull() ?: return null
            if (!allowExpired && System.currentTimeMillis() - timestamp > CACHE_TTL_MS) return null

            val cacheFile = getCacheFile(hash)
            if (!cacheFile.exists()) return null

            val playerJs = cacheFile.readText()
            if (playerJs.isEmpty()) return null

            Pair(playerJs, hash)
        } catch (e: Exception) {
            null
        }
    }

    private fun writeToCache(hash: String, playerJs: String) {
        try {
            val cacheDir = getCacheDir()
            cacheDir.listFiles()?.filter { it.name.startsWith("player_") }?.forEach { it.delete() }
            getCacheFile(hash).writeText(playerJs)
            getHashFile().writeText("$hash\n${System.currentTimeMillis()}")
        } catch (e: Exception) {
            println("[Cipher] Cache write failed: ${e.message}")
        }
    }

    private fun fetchPlayerHash(): String? {
        val request = Request.Builder()
            .url(IFRAME_API_URL)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return null

        val body = response.body?.string() ?: return null
        return PLAYER_HASH_REGEX.find(body)?.groupValues?.get(1)
    }

    private fun downloadPlayerJs(hash: String): String? {
        val url = PLAYER_JS_URL_TEMPLATE.format(hash)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return null

        return response.body?.string()
    }
}
