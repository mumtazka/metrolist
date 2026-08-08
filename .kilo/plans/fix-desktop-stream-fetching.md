# Fix Desktop App Stream Fetching Issue

## Problem Analysis

The desktop app cannot stream music. Investigation reveals the root cause is in the stream URL resolution pipeline in `PlayerState.kt`.

### Current Flow (BROKEN)

1. `PlayerState.resolveAndPlay()` calls `YouTube.player()` with multiple clients in parallel
2. For each client, it tries to get audio formats from `streamingData.adaptiveFormats.filter { it.isAudio }`
3. If `format.url` is null (cipher URLs), it falls back to `NewPipeExtractor.getStreamUrl()`
4. `NewPipeExtractor.newPipePlayer()` calls NewPipe's `StreamInfo.getInfo()` which fetches stream info directly from YouTube
5. **The problem**: NewPipe's `StreamInfo.getInfo()` returns URLs that may still be cipher-encrypted, and the current `NewPipeExtractor.getStreamUrl()` in `NewPipe.kt` doesn't properly handle the cipher deobfuscation for all cases

### Key Differences from Mobile App

The mobile app (`app/src/...`) has a sophisticated cipher deobfuscation system:
- Uses `CipherDeobfuscator.deobfuscateStreamUrl()` which:
  1. Fetches and caches YouTube's player.js
  2. Uses a WebView to execute JavaScript for signature deobfuscation
  3. Handles both signatureCipher and n-parameter transformation
- Has `PlayerJsFetcher` to fetch player.js
- Has `FunctionNameExtractor` to analyze and extract cipher function names

The desktop app relies on NewPipe's built-in deobfuscation which:
- Uses NewPipe's own JavaScript engine implementation
- May be outdated or incomplete compared to YouTube's current cipher

## Root Causes

1. **NewPipe version (v0.26.0)**: The `newpipeextractor` library version may not have the latest cipher deobfuscation logic
2. **Missing fallback**: Desktop doesn't have the WebView-based cipher deobfuscator that mobile uses
3. **Client selection**: The order and selection of YouTube clients may need adjustment
4. **No n-transform**: Desktop doesn't transform the 'n' parameter which YouTube uses for throttling

## Solution Plan

### Option 1: Add WebView-based Cipher Deobfuscation (Recommended)

Add a desktop-compatible cipher deobfuscator similar to the Android implementation:

1. Create `desktop/src/jvmMain/kotlin/com/metrolist/desktop/utils/CipherDeobfuscator.kt`
   - Use JavaFX WebView (already a dependency) for JavaScript execution
   - Implement `deobfuscateSignature()` and `transformN()` methods

2. Create `desktop/src/jvmMain/kotlin/com/metrolist/desktop/utils/PlayerJsFetcher.kt`
   - Fetch and cache player.js from YouTube
   - Handle player updates

3. Update `NewPipe.kt` to use the new deobfuscator as fallback

### Option 2: Improve NewPipe Integration

1. Update `NewPipe.kt` to handle more edge cases:
   - Better error handling for cipher deobfuscation failures
   - Add more clients to try
   - Implement n-parameter transformation

2. Update `PlayerState.kt` resolve logic:
   - Add retry with different clients when cipher fails
   - Better logging for debugging

### Option 3: Use Alternative Stream Sources

1. Use `NewPipeExtractor.newPipePlayer()` more aggressively
2. Try extracting streams from `ytmusic` NewPipe service (service ID 10)
3. Add fallback to generic YouTube services

## Implementation Steps

### Step 1: Create Cipher Deobfuscator for Desktop

**File: `desktop/src/jvmMain/kotlin/com/metrolist/desktop/utils/CipherDeobfuscator.kt`**

```kotlin
package com.metrolist.desktop.utils

import javafx.webkit.WebEngine
import javafx.scene.web.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.thread
import kotlin.js.ExperimentalJs
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object CipherDeobfuscator {
    private var webView: WebView? = null
    private var playerJsHash: String? = null
    
    suspend fun deobfuscateSignature(signatureCipher: String, videoId: String): String? {
        // Parse signatureCipher to get: s, sp, url params
        // Use WebView to execute JavaScript deobfuscation
        // Return deobfuscated URL
    }
    
    suspend fun transformN(url: String): String {
        // Extract n parameter
        // Transform using player.js
        // Return transformed URL
    }
}
```

### Step 2: Update PlayerState.kt

Modify `resolveAndPlay()` to:
1. Try direct URLs first (clients that don't use cipher)
2. Fall back to NewPipe extraction
3. If NewPipe fails, use WebView-based deobfuscation

### Step 3: Update YouTubeClient configurations

Review and potentially update client definitions in `YouTubeClient.kt`:
- Ensure client versions are current
- Consider adding more reliable clients

### Step 4: Add Better Error Handling and Logging

Add comprehensive logging to understand where failures occur:
- Log each client's response status
- Log cipher vs direct URL detection
- Log deobfuscation success/failure

## Testing Strategy

1. Build and run desktop app
2. Try playing various types of songs:
   - Regular songs
   - Age-restricted songs
   - High-bitrate streams
3. Verify logging output shows which client/URL resolution method succeeds
4. Test offline mode (stream cache)

## Files to Modify

1. `desktop/src/jvmMain/kotlin/com/metrolist/desktop/player/PlayerState.kt`
2. `desktop/src/jvmMain/kotlin/com/metrolist/desktop/utils/CipherDeobfuscator.kt` (NEW)
3. `desktop/src/jvmMain/kotlin/com/metrolist/desktop/utils/PlayerJsFetcher.kt` (NEW)
4. `innertube/src/main/kotlin/com/metrolist/innertube/pages/NewPipe.kt`
5. `innertube/src/main/kotlin/com/metrolist/innertube/models/YouTubeClient.kt`