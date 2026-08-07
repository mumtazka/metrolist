package com.metrolist.desktop.cipher

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.response.PlayerResponse
import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

object DesktopCipherDeobfuscator {
    private const val TAG = "Metrolist_DesktopCipher"

    @Volatile
    private var webView: DesktopCipherWebView? = null
    @Volatile
    private var playerJsHash: String? = null
    @Volatile
    private var initFailed: Boolean = false

    suspend fun ensureInitialized() {
        if (webView != null || initFailed) return
        withContext(Dispatchers.IO) {
            try {
                println("[Cipher] Initializing desktop cipher deobfuscator...")
                val cached = PlayerJsFetcher.getPlayerJs(forceRefresh = false)
                val (playerJs, hash) = when {
                    cached != null -> cached
                    else -> {
                        println("[Cipher] Cache miss, fetching fresh player.js...")
                        PlayerJsFetcher.getPlayerJs(forceRefresh = true) ?: run {
                            println("[Cipher] Failed to fetch player.js")
                            initFailed = true
                            return@withContext
                        }
                    }
                }
                playerJsHash = hash

                val analysis = FunctionNameExtractor.analyzePlayerJs(playerJs, knownHash = hash)
                println("[Cipher] Analysis: sig=${analysis.sigInfo?.name}(${analysis.sigInfo?.constantArgs}), n=${analysis.nFuncInfo?.name}[${analysis.nFuncInfo?.arrayIndex}]")

                if (analysis.sigInfo == null) {
                    println("[Cipher] WARNING: signature function not found, cipher deobfuscation unavailable")
                    return@withContext
                }

                withContext(Dispatchers.Main) {
                    webView = DesktopCipherWebView.create(
                        playerJs = playerJs,
                        sigInfo = analysis.sigInfo,
                        nFuncInfo = analysis.nFuncInfo
                    )
                }
                println("[Cipher] Initialized: sig=${webView?.sigFunctionAvailable}, n=${webView?.nFunctionAvailable}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("[Cipher] Init failed: ${e.message}")
                e.printStackTrace()
                initFailed = true
                webView = null
                playerJsHash = null
            }
        }
    }

    suspend fun deobfuscateFormat(
        format: PlayerResponse.StreamingData.Format,
        videoId: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val signatureCipher = format.signatureCipher ?: return@withContext null
            val params = parseQueryString(signatureCipher)
            val obfuscatedSig = params["s"] ?: return@withContext null
            val sigParam = params["sp"] ?: "signature"
            val baseUrl = params["url"] ?: return@withContext null

            println("[Cipher] Deobfuscating itag=${format.itag}, sigLen=${obfuscatedSig.length}")

            val wv = webView ?: run {
                ensureInitialized()
                webView
            } ?: run {
                println("[Cipher] WebView not available, cannot deobfuscate")
                return@withContext null
            }

            val deobfuscatedSig = withContext(Dispatchers.Main) {
                wv.deobfuscateSignature(obfuscatedSig)
            }

            val url = URLBuilder(baseUrl)
            url.parameters[sigParam] = deobfuscatedSig
            url.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("[Cipher] Format deobfuscation failed: ${e.message}")
            null
        }
    }

    suspend fun transformN(url: String): String {
        val wv = webView ?: return url
        if (!wv.nFunctionAvailable) return url

        return try {
            val nValue = extractNValue(url)
            if (nValue.isEmpty()) return url

            withContext(Dispatchers.Main) {
                wv.transformN(nValue)
            }.let { transformedN ->
                url.replaceFirst(Regex("([?&])n=[^&]+"), "$1n=$transformedN")
            }
        } catch (e: Exception) {
            println("[Cipher] N-transform failed: ${e.message}")
            url
        }
    }

    suspend fun tryDeobfuscateAllFormats(
        formats: List<PlayerResponse.StreamingData.Format>,
        videoId: String
    ): List<PlayerResponse.StreamingData.Format> = withContext(Dispatchers.IO) {
        val result = mutableListOf<PlayerResponse.StreamingData.Format>()
        for (format in formats) {
            if (format.signatureCipher == null) {
                result.add(format)
                continue
            }
            val deobfuscatedUrl = deobfuscateFormat(format, videoId)
            if (deobfuscatedUrl != null) {
                result.add(format.copy(url = deobfuscatedUrl, signatureCipher = null))
            }
        }
        result
    }

    fun invalidateCache() {
        PlayerJsFetcher.invalidateCache()
        kotlinx.coroutines.runBlocking(Dispatchers.Main) {
            webView?.close()
        }
        webView = null
        playerJsHash = null
        initFailed = false
        println("[Cipher] Cache invalidated")
    }

    fun isInitialized(): Boolean = webView != null

    private fun parseQueryString(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                result[pair.substring(0, idx)] = pair.substring(idx + 1)
            }
        }
        return result
    }

    private fun extractNValue(url: String): String {
        val regex = Regex("[?&]n=([^&]+)")
        return regex.find(url)?.groupValues?.get(1) ?: ""
    }
}
