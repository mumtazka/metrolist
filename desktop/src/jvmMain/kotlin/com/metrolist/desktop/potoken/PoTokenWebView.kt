package com.metrolist.desktop.potoken

import javafx.application.Platform
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import netscape.javascript.JSObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PoTokenWebView private constructor(
    private val webView: WebView,
    private val engine: WebEngine,
) {
    @Volatile
    var isInitialized: Boolean = false
        private set

    @Volatile
    var isExpired: Boolean = false
        private set

    @Volatile
    private var initException: Throwable? = null

    private val initializationLatch = CountDownLatch(1)
    private val bridge = Bridge()

    init {
        engine.isJavaScriptEnabled = true

        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        engine.userAgent = userAgent

        engine.loadWorker.stateProperty().addListener { _, _, newState ->
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                val jsWindow = engine.executeScript("window") as JSObject
                jsWindow.setMember("PoTokenBridge", bridge)

                try {
                    val input = PoTokenWebView::class.java.getResourceAsStream("/po_token.html")
                    if (input != null) {
                        val reader = InputStreamReader(input, StandardCharsets.UTF_8)
                        val content = reader.readText()
                        engine.loadContent(content)
                    } else {
                        initException = IllegalStateException("po_token.html not found in resources")
                        initializationLatch.countDown()
                    }
                } catch (e: Exception) {
                    initException = e
                    initializationLatch.countDown()
                }
            }
        }
    }

    suspend fun waitForInitialization(timeoutMs: Long = 30_000): Boolean = withContext(Dispatchers.IO) {
        val finished = initializationLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            throw IllegalStateException("Timeout waiting for PoTokenWebView initialization")
        }
        initException?.let { throw it }
        isInitialized = true
        true
    }

    suspend fun generatePoToken(identifier: String): String {
        if (!isInitialized) {
            throw IllegalStateException("PoTokenWebView not initialized")
        }

        return suspendCancellableCoroutine { cont ->
            Platform.runLater {
                try {
                    val js = """obtainPoToken("$identifier").then(function(result) {
                                PoTokenBridge.onPoTokenGenerated(result);
                              }).catch(function(error) {
                                PoTokenBridge.onPoTokenError(error);
                              });"""
                    engine.executeScript(js)
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }

            cont.invokeOnCancellation {
            }
        }
    }

    fun close() {
        Platform.runLater {
            engine.loadContent("about:blank")
            webView.scene?.root = null
        }
    }

    private inner class Bridge {
        @Volatile
        var result: String? = null
        @Volatile
        var error: Throwable? = null
        private val resultLatch = CountDownLatch(1)

        @Suppress("unused")
        fun onPoTokenGenerated(poToken: String) {
            result = poToken
            resultLatch.countDown()
        }

        @Suppress("unused")
        fun onPoTokenError(errorMessage: String) {
            error = IllegalStateException("PoToken generation failed: $errorMessage")
            resultLatch.countDown()
        }

        @Suppress("unused")
        fun onInitializationComplete() {
            isInitialized = true
            initializationLatch.countDown()
        }

        @Suppress("unused")
        fun onInitializationError(errorMessage: String) {
            initException = IllegalStateException("PoTokenWebView initialization failed: $errorMessage")
            initializationLatch.countDown()
        }

        suspend fun awaitResult(timeoutMs: Long = 10_000): String {
            val finished = resultLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                throw IllegalStateException("Timeout waiting for PoToken result")
            }
            error?.let { throw it }
            return result ?: throw IllegalStateException("PoToken result is null")
        }
    }

    companion object {
        suspend fun create(): PoTokenWebView = withContext(Dispatchers.IO) {
            val webView = WebView()
            val engine = webView.engine
            val instance = PoTokenWebView(webView, engine)

            val initJob = CoroutineScope(Dispatchers.IO).launch {
                instance.waitForInitialization()
            }
            initJob.join()

            instance
        }
    }
}
