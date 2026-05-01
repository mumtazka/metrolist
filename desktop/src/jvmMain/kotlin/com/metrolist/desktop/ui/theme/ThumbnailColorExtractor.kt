/**
 * Metrolist Desktop — Thumbnail Vibrant Color Extractor
 *
 * Extracts the most vibrant/saturated colour from an album art URL.
 * No extra dependencies — uses JDK's ImageIO + java.awt.Color HSB conversion.
 *
 * Algorithm:
 *   1. Download JPEG/PNG from CDN (HttpURLConnection)
 *   2. Decode with ImageIO → scale to 32×32
 *   3. Score each pixel: score = saturation² × brightness
 *   4. Return the highest-scoring colour
 *   5. LRU cache (30 entries) for instant re-plays
 */

package com.metrolist.desktop.ui.theme

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.awt.Image

object ThumbnailColorExtractor {

    // LRU-style cache: url → extracted Color
    private val cache = object : LinkedHashMap<String, Color>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Color>) = size > 30
    }

    /**
     * Returns the most vibrant colour found in [imageUrl], or null on failure.
     * Must be called from a coroutine; suspends on IO.
     */
    suspend fun extractVibrantColor(imageUrl: String): Color? = withContext(Dispatchers.IO) {
        // Cache hit
        cache[imageUrl]?.let { return@withContext it }

        try {
            // Download image
            val connection = URL(imageUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout    = 10_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.connect()

            val original: BufferedImage = connection.inputStream.use { ImageIO.read(it) }
                ?: return@withContext null
            connection.disconnect()

            // Downsample to 32×32 for speed
            val small = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
            val g = small.createGraphics()
            g.drawImage(original.getScaledInstance(32, 32, Image.SCALE_FAST), 0, 0, null)
            g.dispose()

            // Find the most vibrant pixel
            var bestScore = -1f
            var bestR = 0f; var bestG = 0f; var bestB = 0f

            val hsb = FloatArray(3)
            for (y in 0 until 32) {
                for (x in 0 until 32) {
                    val rgb = small.getRGB(x, y)
                    val r = (rgb shr 16) and 0xFF
                    val g2 = (rgb shr 8) and 0xFF
                    val b = rgb and 0xFF

                    java.awt.Color.RGBtoHSB(r, g2, b, hsb)
                    val sat   = hsb[1]
                    val bri   = hsb[2]

                    // Skip near-black, near-white, and near-grey
                    if (sat < 0.25f || bri < 0.2f || bri > 0.96f) continue

                    val score = sat * sat * bri
                    if (score > bestScore) {
                        bestScore = score
                        bestR = r / 255f; bestG = g2 / 255f; bestB = b / 255f
                    }
                }
            }

            if (bestScore < 0f) return@withContext null // no vibrant pixel found

            val color = Color(bestR, bestG, bestB)
            cache[imageUrl] = color
            color
        } catch (e: Exception) {
            println("[DynamicColor] Failed to extract from $imageUrl: ${e.message}")
            null
        }
    }

    fun clearCache() = cache.clear()
}
