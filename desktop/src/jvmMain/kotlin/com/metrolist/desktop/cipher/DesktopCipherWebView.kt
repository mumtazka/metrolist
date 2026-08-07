package com.metrolist.desktop.cipher

import javafx.application.Platform
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import netscape.javascript.JSObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DesktopCipherWebView private constructor(
    private val webView: WebView,
    private val engine: WebEngine,
    private val playerJs: String,
    private val sigInfo: FunctionNameExtractor.SigFunctionInfo?,
    private val nFuncInfo: FunctionNameExtractor.NFunctionInfo?,
) {
    @Volatile
    var nFunctionAvailable: Boolean = false
        private set

    @Volatile
    var sigFunctionAvailable: Boolean = false
        private set

    @Volatile
    var discoveredNFuncName: String? = null
        private set

    @Volatile
    var usingHardcodedMode: Boolean = false
        private set

    private val bridge = Bridge()

    init {
        val isHardcoded = sigInfo?.isHardcoded == true || nFuncInfo?.isHardcoded == true
        usingHardcodedMode = isHardcoded
        engine.loadWorker.stateProperty().addListener { _, _, newState ->
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                val jsWindow = engine.executeScript("window") as JSObject
                jsWindow.setMember("CipherBridge", bridge)
            }
        }
        loadPlayerJs()
    }

    private fun loadPlayerJs() {
        val exports = buildExports()
        val modifiedJs = if (exports.isNotEmpty()) {
            val exportCode = "; $exports"
            val needle = "})(_yt_player);"
            val idx = playerJs.lastIndexOf(needle)
            if (idx >= 0) {
                playerJs.substring(0, idx) + exportCode + needle + playerJs.substring(idx + needle.length)
            } else {
                playerJs + "\n" + exportCode
            }
        } else playerJs

        val cacheDir = File(System.getProperty("java.io.tmpdir"), "metrolist-cipher")
        cacheDir.mkdirs()
        File(cacheDir, "player.js").writeText(modifiedJs)

        val html = buildDiscoveryHtml()
        Platform.runLater { engine.loadContent(html) }
    }

    private fun buildExports(): String = buildString {
        val sigFuncName = sigInfo?.name
        val nFuncName = nFuncInfo?.name
        val nArrayIdx = nFuncInfo?.arrayIndex

        if (sigFuncName != null) {
            val constArgs = sigInfo?.constantArgs
            val preprocessFunc = sigInfo?.preprocessFunc
            val preprocessArgs = sigInfo?.preprocessArgs

            val expr = when {
                !constArgs.isNullOrEmpty() && preprocessFunc != null && !preprocessArgs.isNullOrEmpty() ->
                    "$sigFuncName(${constArgs.joinToString(", ")}, $preprocessFunc(${preprocessArgs.joinToString(", ")}, sig))"
                !constArgs.isNullOrEmpty() ->
                    "$sigFuncName(${constArgs.joinToString(", ")}, sig)"
                else -> sigFuncName
            }
            appendLine("window._cipherSigFunc = function(sig) { return $expr; };")
        }

        if (nFuncName != null) {
            val nConstArgs = nFuncInfo?.constantArgs
            val expr = when {
                !nConstArgs.isNullOrEmpty() ->
                    "function(n) { return $nFuncName(${nConstArgs.joinToString(", ")}, n); }"
                nArrayIdx != null -> "$nFuncName[$nArrayIdx]"
                else -> nFuncName
            }
            appendLine("window._nTransformFunc = $expr;")
        }
    }

    private fun buildDiscoveryHtml(): String {
        val playerJsPath = "player.js"
        return """
            <!DOCTYPE html><html><head><script>
            function discoverAndInit() {
                try {
                    var sigAvailable = typeof window._cipherSigFunc === 'function';
                    var nAvailable = typeof window._nTransformFunc === 'function';
                    var nFuncName = '';
                    if (nAvailable) {
                        try {
                            var testInput = 'T2Xw3pWQ_Wk0xbOg';
                            var testResult = window._nTransformFunc(testInput);
                            if (typeof testResult === 'string' && testResult !== testInput && testResult.length >= 5 && /^[a-zA-Z0-9_-]+$/.test(testResult)) {
                                nFuncName = 'exported';
                            } else {
                                nAvailable = false;
                                window._nTransformFunc = null;
                            }
                        } catch(e) {
                            nAvailable = false;
                            window._nTransformFunc = null;
                        }
                    }
                    CipherBridge.onDiscovery(sigAvailable, nAvailable, nFuncName);
                } catch(e) {
                    CipherBridge.onError('Discovery failed: ' + e);
                }
            }
            </script>
            <script src="$playerJsPath"
                onload="discoverAndInit()"
                onerror="CipherBridge.onError('Failed to load player.js')"></script>
            </head><body></body></html>
        """.trimIndent()
    }

    suspend fun initAndWait(maxMs: Long = 10_000) {
        val finished = bridge.latch.await(maxMs, TimeUnit.MILLISECONDS)
        if (!finished) throw CipherException("Timeout waiting for player.js load")
        bridge.error?.let { throw it }

        check(bridge.sigAvailable) { "Signature function not discovered" }
        if (!bridge.nAvailable) {
            println("[Cipher] Warning: N-transform function not discovered, n-transform will be disabled")
        }
        sigFunctionAvailable = bridge.sigAvailable
        nFunctionAvailable = bridge.nAvailable
        discoveredNFuncName = if (bridge.nAvailable) "exported" else null
    }

    suspend fun deobfuscateSignature(obfuscatedSig: String): String = platformRun {
        engine.executeScript("typeof window._cipherSigFunc === 'function'").let { isFunction ->
            if (isFunction !is Boolean || !isFunction) throw CipherException("sig function lost after discovery")
        }
        val constArg = sigInfo?.constantArg
        val js = if (constArg != null) {
            "window._cipherSigFunc($constArg, '$obfuscatedSig')"
        } else {
            "window._cipherSigFunc('$obfuscatedSig')"
        }
        val result = engine.executeScript(js)
            ?: throw CipherException("Sig returned null")
        result.toString()
    }

    suspend fun transformN(nValue: String): String = platformRun {
        engine.executeScript("typeof window._nTransformFunc === 'function'").let { isFunction ->
            if (isFunction !is Boolean || !isFunction) throw CipherException("N-transform function not available")
        }
        val result = engine.executeScript("window._nTransformFunc('$nValue')")
            ?: throw CipherException("N-transform returned null")
        result.toString()
    }

    fun close() {
        Platform.runLater {
            engine.loadContent("about:blank")
            webView.scene?.root = null
        }
    }

    private suspend fun <T> platformRun(block: () -> T): T {
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        Platform.runLater {
            result = runCatching { block() }
            latch.countDown()
        }
        latch.await()
        return result!!.getOrThrow()
    }

    private inner class Bridge {
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        var sigAvailable = false
        var nAvailable = false
        var discoveryComplete = false

        @Suppress("unused")
        fun onDiscovery(sigAvailable: Boolean, nAvailable: Boolean, nFuncName: String) {
            this.sigAvailable = sigAvailable
            this.nAvailable = nAvailable
            discoveredNFuncName = nFuncName.ifEmpty { null }
            discoveryComplete = true
            latch.countDown()
        }

        @Suppress("unused")
        fun onError(message: String) {
            error = CipherException(message)
            latch.countDown()
        }
    }

    companion object {
        suspend fun create(
            playerJs: String,
            sigInfo: FunctionNameExtractor.SigFunctionInfo?,
            nFuncInfo: FunctionNameExtractor.NFunctionInfo? = null,
        ): DesktopCipherWebView {
            // Compose Desktop already starts JavaFX — no need to call Platform.startup again

            val webView = WebView()
            val engine = webView.engine
            val instance = DesktopCipherWebView(webView, engine, playerJs, sigInfo, nFuncInfo)
            instance.initAndWait()
            return instance
        }
    }
}

class CipherException(message: String) : Exception(message)
