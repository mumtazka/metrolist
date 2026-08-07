package com.metrolist.desktop.potoken

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

class PoTokenGenerator {
    private val webPoTokenGenLock = Mutex()
    private var webPoTokenGenerator: PoTokenWebView? = null
    private var webPoTokenSessionId: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenExpiration: Instant? = null

    /**
     * Generates a Web PoToken pair for the given videoId using the sessionId.
     *
     * Returns both tokens:
     * - [PoTokenResult.playerRequestPoToken]: sent in the player API request body
     * - [PoTokenResult.streamingDataPoToken]: appended as pot= on the stream URL
     *
     * This follows the same pattern as the Android PoTokenGenerator:
     * - Creates a session-specific generator
     * - Generates a streaming poToken once per session (with sessionId)
     * - Uses that session to generate player poTokens for specific videoIds
     */
    suspend fun getWebClientPoToken(videoId: String, sessionId: String): PoTokenResult? {
        // Ensure generator and streaming token are ready (under lock)
        val generator: PoTokenWebView
        val streamingPoToken: String

        webPoTokenGenLock.withLock {
            val shouldRecreate = webPoTokenGenerator == null ||
                    webPoTokenSessionId != sessionId ||
                    webPoTokenExpiration == null ||
                    Instant.now().isAfter(webPoTokenExpiration!!)

            if (shouldRecreate) {
                webPoTokenGenerator?.let {
                    withContext(Dispatchers.Main) { it.close() }
                }

                webPoTokenGenerator = withContext(Dispatchers.IO) { PoTokenWebView.create() }
                webPoTokenSessionId = sessionId

                // Generate streaming poToken once per session (with sessionId)
                webPoTokenStreamingPot = webPoTokenGenerator!!.generatePoToken(sessionId)
                if (webPoTokenStreamingPot == null) {
                    webPoTokenGenerator = null
                    webPoTokenSessionId = null
                    return null
                }

                webPoTokenExpiration = Instant.now().plus(23, ChronoUnit.HOURS)
            }

            generator = webPoTokenGenerator ?: return null
            streamingPoToken = webPoTokenStreamingPot ?: return null
        }

        // Generate player poToken for this specific videoId
        val playerPot = try {
            generator.generatePoToken(videoId)
        } catch (e: Exception) {
            println("[PoToken] Failed to generate player poToken: ${e.message}")
            null
        }

        if (playerPot == null) return null

        return PoTokenResult(
            playerRequestPoToken = playerPot,
            streamingDataPoToken = streamingPoToken,
        )
    }

    fun isExpired(): Boolean {
        return webPoTokenExpiration == null || Instant.now().isAfter(webPoTokenExpiration!!)
    }

    fun clear() {
        runBlocking {
            webPoTokenGenLock.withLock {
                webPoTokenGenerator?.close()
                webPoTokenGenerator = null
                webPoTokenSessionId = null
                webPoTokenStreamingPot = null
                webPoTokenExpiration = null
            }
        }
    }
}