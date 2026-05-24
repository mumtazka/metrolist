package com.metrolist.desktop.auth

import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView
import javafx.concurrent.Worker
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import java.awt.Dimension
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.awt.Desktop as AwtDesktop
import com.metrolist.desktop.viewmodel.DesktopViewModel

fun showGoogleLoginDialog(viewModel: DesktopViewModel, onComplete: () -> Unit = {}) {
    println("[GoogleLoginDialog] Preparing Google login window...")
    SwingUtilities.invokeLater {
        try {
            val dialog = JDialog(null as JFrame?, "Sign In with Google", false)
            dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
            dialog.size = Dimension(750, 800)
            dialog.setLocationRelativeTo(null)
            dialog.isAlwaysOnTop = true

            val jfxPanel = JFXPanel()
            dialog.add(jfxPanel)
            Platform.setImplicitExit(false)

            // Ensure global CookieManager is initialized and clean
            val manager = CookieHandler.getDefault() as? CookieManager ?: CookieManager(null, CookiePolicy.ACCEPT_ALL).also {
                CookieHandler.setDefault(it)
            }
            try {
                manager.cookieStore.removeAll()
                println("[GoogleLoginDialog] Cleaned previous cookies.")
            } catch (e: Exception) {
                println("[GoogleLoginDialog] Error cleaning cookies: ${e.message}")
            }

            Platform.runLater {
                val webView = WebView()
                val webEngine = webView.engine

                // Use a macOS Safari User-Agent which perfectly aligns with the underlying WebKit engine, bypassing the secure browser check
                webEngine.userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"

                // Wrap in StackPane to guarantee proper layout and resizing
                val root = javafx.scene.layout.StackPane(webView)
                jfxPanel.scene = Scene(root, 750.0, 800.0)

                var isCompletingLogin = false

                webEngine.loadWorker.stateProperty().addListener { _, oldState, newState ->
                    val currentUrl = webEngine.location
                    println("[GoogleLoginDialog] Load state transitioned: $oldState -> $newState")
                    println("[GoogleLoginDialog] Current URL: $currentUrl")

                    if (
                        newState == Worker.State.SUCCEEDED &&
                        currentUrl != null &&
                        currentUrl.startsWith("https://music.youtube.com") &&
                        !isCompletingLogin
                    ) {
                        println("[GoogleLoginDialog] Successfully reached YouTube Music. Extracting session context...")

                        val visitorData = readPageConfigValue(webEngine, "VISITOR_DATA")
                        val dataSyncId = readPageConfigValue(webEngine, "DATASYNC_ID").substringBefore("||")
                        val cookieString = collectSessionCookies(manager)

                        if (!cookieString.contains("SAPISID")) {
                            println("[GoogleLoginDialog] SAPISID cookie not available yet.")
                            return@addListener
                        }

                        isCompletingLogin = true
                        SwingUtilities.invokeLater {
                            dialog.title = "Finishing Google Sign-In..."
                        }
                        println("[GoogleLoginDialog] Validating Google session with InnerTube...")

                        viewModel.loginWithCookie(
                            cookie = cookieString,
                            visitorData = visitorData,
                            dataSyncId = dataSyncId,
                            onSuccess = {
                                SwingUtilities.invokeLater {
                                    dialog.dispose()
                                    onComplete()
                                }
                            },
                            onFailure = { message ->
                                isCompletingLogin = false
                                SwingUtilities.invokeLater {
                                    dialog.title = "Sign In with Google"
                                    JOptionPane.showMessageDialog(
                                        dialog,
                                        message,
                                        "Google Sign-In Failed",
                                        JOptionPane.ERROR_MESSAGE,
                                    )
                                }
                            },
                        )
                    }
                }

                webEngine.loadWorker.exceptionProperty().addListener { _, _, newValue ->
                    if (newValue != null) {
                        println("[GoogleLoginDialog] Loader Exception: ${newValue.message}")
                        newValue.printStackTrace()
                        SwingUtilities.invokeLater {
                            dialog.dispose()
                            showGoogleLoginFallback(newValue)
                        }
                    }
                }

                // Defer showing the JDialog to Swing thread ONLY after JavaFX scene is prepared
                SwingUtilities.invokeLater {
                    println("[GoogleLoginDialog] Showing login dialog...")
                    dialog.isVisible = true
                    dialog.toFront()
                    dialog.requestFocus()
                }

                println("[GoogleLoginDialog] Loading Google ServiceLogin...")
                webEngine.load("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
            }
        } catch (e: Exception) {
            println("[GoogleLoginDialog] Failed to start embedded login window: ${e.message}")
            e.printStackTrace()
            showGoogleLoginFallback(e)
        }
    }
}

private fun showGoogleLoginFallback(error: Throwable) {
    val loginUrl = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
    runCatching {
        if (AwtDesktop.isDesktopSupported()) {
            AwtDesktop.getDesktop().browse(URI(loginUrl))
        }
    }.onFailure {
        println("[GoogleLoginDialog] Browser fallback failed: ${it.message}")
    }

    JOptionPane.showMessageDialog(
        null,
        "Embedded Google sign-in could not be opened.\n\n" +
            "A browser login page was opened instead.\n" +
            "If the embedded window keeps failing, sign in there and use 'Paste cookie' in Settings.\n\n" +
            "Technical detail: ${error.javaClass.simpleName}: ${error.message.orEmpty()}",
        "Google Sign-In Unavailable",
        JOptionPane.WARNING_MESSAGE,
    )
}

private fun readPageConfigValue(webEngine: javafx.scene.web.WebEngine, key: String): String {
    return try {
        webEngine.executeScript(
            "(function() { try { return (window.yt && window.yt.config_ && window.yt.config_['$key']) || ''; } catch (e) { return ''; } })()"
        ) as? String ?: ""
    } catch (e: Exception) {
        println("[GoogleLoginDialog] JavaScript error extracting $key: ${e.message}")
        ""
    }
}

private fun collectSessionCookies(manager: CookieManager): String {
    val collected = linkedSetOf<HttpCookie>()
    val targetUris = listOf(
        URI("https://music.youtube.com"),
        URI("https://www.youtube.com"),
        URI("https://youtube.com"),
        URI("https://accounts.google.com"),
        URI("https://google.com"),
    )

    targetUris.forEach { uri ->
        runCatching { manager.cookieStore.get(uri) }
            .onSuccess { collected.addAll(it) }
    }

    runCatching {
        manager.cookieStore.cookies.filter { cookie ->
            val domain = cookie.domain?.lowercase().orEmpty()
            domain.contains("youtube") || domain.contains("google")
        }
    }.onSuccess { collected.addAll(it) }

    return collected
        .groupBy { it.name }
        .values
        .mapNotNull { cookies -> cookies.maxByOrNull { it.domain?.length ?: 0 } }
        .joinToString("; ") { "${it.name}=${it.value}" }
}
