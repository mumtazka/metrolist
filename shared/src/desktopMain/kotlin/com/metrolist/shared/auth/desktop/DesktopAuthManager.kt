/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * DesktopAuthManager - Desktop implementation of AuthManager.
 * Opens the user's default browser for Google login and captures
 * the authentication cookies via a temporary localhost HTTP server.
 */

package com.metrolist.shared.auth.desktop

import com.metrolist.shared.auth.AccountInfo
import com.metrolist.shared.auth.AuthManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * Desktop authentication via browser-based Google login.
 *
 * Flow:
 * 1. Start a temporary Ktor HTTP server on localhost:8484
 * 2. Open the user's browser to the Google Login page
 * 3. After login, YouTube Music redirects to our localhost callback
 * 4. We extract the cookies from the redirect and save them
 * 5. Shut down the temporary server
 */
class DesktopAuthManager : AuthManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val credentialsFile = File(System.getProperty("user.home"), ".metrolist/credentials.json")

    // --- State ---

    private val _isLoggedIn = MutableStateFlow(false)
    override val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _accountInfo = MutableStateFlow<AccountInfo?>(null)
    override val accountInfo: StateFlow<AccountInfo?> = _accountInfo.asStateFlow()

    private val _cookie = MutableStateFlow("")
    override val cookie: StateFlow<String> = _cookie.asStateFlow()

    init {
        // Try to load saved credentials on startup
        loadSavedCredentials()
    }

    // --- AuthManager Interface ---

    override fun getAccountHash(): String {
        val email = _accountInfo.value?.email ?: return ""
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(email.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }.take(16)
    }

    override suspend fun startLogin() {
        withContext(Dispatchers.IO) {
            val completableDeferred = CompletableDeferred<String>()

            // Start temporary HTTP server to capture the login redirect
            val server = embeddedServer(Netty, port = 8484) {
                routing {
                    get("/callback") {
                        val cookieHeader = call.request.headers["Cookie"] ?: ""
                        val queryParams = call.request.queryParameters

                        // Build response page
                        call.respondText(
                            contentType = ContentType.Text.Html,
                            text = """
                                <!DOCTYPE html>
                                <html>
                                <head><title>Metrolist Login</title></head>
                                <body style="background:#1a1a2e;color:#eee;font-family:sans-serif;
                                             display:flex;justify-content:center;align-items:center;
                                             height:100vh;margin:0;">
                                    <div style="text-align:center;">
                                        <h1>✅ Login Successful!</h1>
                                        <p>You can close this tab and return to Metrolist Desktop.</p>
                                    </div>
                                </body>
                                </html>
                            """.trimIndent()
                        )

                        completableDeferred.complete(cookieHeader)
                    }

                    // Health check
                    get("/") {
                        call.respondText("Metrolist Desktop Login Server")
                    }
                }
            }.start(wait = false)

            // Open the browser to Google login
            // The redirect URL points to our local server
            val loginUrl = "https://accounts.google.com/ServiceLogin?" +
                    "continue=http://localhost:8484/callback"

            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(URI(loginUrl))
                } else {
                    // Fallback for Linux without desktop integration
                    Runtime.getRuntime().exec(arrayOf("xdg-open", loginUrl))
                }
            } catch (e: Exception) {
                // Last resort: print URL for user to copy
                println("Please open this URL in your browser: $loginUrl")
            }

            // Wait for the callback (with timeout)
            try {
                val receivedCookie = withTimeout(300_000) { // 5 minute timeout
                    completableDeferred.await()
                }

                if (receivedCookie.isNotBlank()) {
                    _cookie.value = receivedCookie
                    _isLoggedIn.value = true

                    // Extract account info from cookie
                    // In a real implementation, we'd call YouTube.accountInfo()
                    _accountInfo.value = AccountInfo(
                        name = "Desktop User",
                        email = extractEmailFromCookie(receivedCookie),
                        cookie = receivedCookie,
                    )

                    saveCredentials()
                }
            } catch (e: TimeoutCancellationException) {
                // Login timed out
            } finally {
                server.stop(1000, 2000)
            }
        }
    }

    override suspend fun logout() {
        _isLoggedIn.value = false
        _accountInfo.value = null
        _cookie.value = ""
        credentialsFile.delete()
    }

    // --- Persistence ---

    private fun saveCredentials() {
        try {
            credentialsFile.parentFile?.mkdirs()
            val info = _accountInfo.value ?: return
            credentialsFile.writeText(
                """
                {
                    "name": "${info.name}",
                    "email": "${info.email}",
                    "cookie": "${info.cookie}",
                    "channelHandle": "${info.channelHandle}",
                    "visitorData": "${info.visitorData}",
                    "dataSyncId": "${info.dataSyncId}"
                }
                """.trimIndent()
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadSavedCredentials() {
        try {
            if (credentialsFile.exists()) {
                val content = credentialsFile.readText()
                // Simple JSON parsing (in production, use kotlinx.serialization)
                val name = extractJsonField(content, "name")
                val email = extractJsonField(content, "email")
                val cookie = extractJsonField(content, "cookie")

                if (cookie.isNotBlank()) {
                    _cookie.value = cookie
                    _isLoggedIn.value = true
                    _accountInfo.value = AccountInfo(
                        name = name,
                        email = email,
                        cookie = cookie,
                        channelHandle = extractJsonField(content, "channelHandle"),
                        visitorData = extractJsonField(content, "visitorData"),
                        dataSyncId = extractJsonField(content, "dataSyncId"),
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun extractJsonField(json: String, field: String): String {
        val regex = """"$field"\s*:\s*"([^"]*)"""".toRegex()
        return regex.find(json)?.groupValues?.getOrNull(1) ?: ""
    }

    private fun extractEmailFromCookie(cookie: String): String {
        // The email isn't directly in cookies; this is a placeholder.
        // In the real implementation, you'd call YouTube.accountInfo() 
        // using the cookie to fetch the actual email.
        return "user@gmail.com"
    }
}
