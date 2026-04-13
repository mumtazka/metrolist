/**
 * Metrolist Desktop — Async Image Loading
 * Downloads album art / thumbnails from YouTube CDN via OkHttp,
 * converts to Compose ImageBitmap and renders them.
 */

package com.metrolist.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Image as SkiaImage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// ─── Simple in-memory LRU-like cache (max 200 entries) ───────────────────────

private val imageCache = ConcurrentHashMap<String, ImageBitmap>(200)

private val httpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()

private suspend fun loadImageFromUrl(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    // Return cached bitmap immediately
    imageCache[url]?.let { return@withContext it }

    try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return@withContext null

        val bytes = response.body?.bytes() ?: return@withContext null
        val bitmap = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()

        // Cache it (trim cache if too large)
        if (imageCache.size >= 200) {
            imageCache.keys.take(50).forEach { imageCache.remove(it) }
        }
        imageCache[url] = bitmap
        bitmap
    } catch (_: Exception) {
        null
    }
}

// ─── The composable ───────────────────────────────────────────────────────────

/**
 * Asynchronously loads and displays an image from a URL.
 * Shows [placeholder] while loading or if the load fails.
 */
@Composable
fun AsyncImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: @Composable () -> Unit = {
        Box(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        )
    },
) {
    if (url.isNullOrBlank()) {
        placeholder()
        return
    }

    // Start with cached value to avoid flicker on recomposition
    var bitmap by remember(url) { mutableStateOf(imageCache[url]) }

    LaunchedEffect(url) {
        if (bitmap == null) {
            bitmap = loadImageFromUrl(url)
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        } else {
            placeholder()
        }
    }
}
