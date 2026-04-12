/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * AuthManager - Platform-agnostic authentication interface.
 * Android: WebView-based Google login (existing LoginScreen.kt).
 * Desktop: Browser redirect + localhost Ktor server to capture cookies.
 */

package com.metrolist.shared.auth

import kotlinx.coroutines.flow.StateFlow

/**
 * Account information retrieved after successful login.
 */
data class AccountInfo(
    val name: String,
    val email: String,
    val channelHandle: String = "",
    val cookie: String = "",
    val visitorData: String = "",
    val dataSyncId: String = "",
)

/**
 * Shared interface for authentication across platforms.
 */
interface AuthManager {
    /** Whether the user is currently logged in with a valid session. */
    val isLoggedIn: StateFlow<Boolean>

    /** The current account info, or null if not logged in. */
    val accountInfo: StateFlow<AccountInfo?>

    /** The raw InnerTube cookie used for authenticated API requests. */
    val cookie: StateFlow<String>

    /**
     * Returns a SHA-256 hash of the account email.
     * Used as the "Room ID" for the sync relay server,
     * so devices logged into the same account auto-pair.
     */
    fun getAccountHash(): String

    /**
     * Initiate the login flow.
     * On Android: opens a WebView to accounts.google.com
     * On Desktop: opens default browser + local redirect server
     */
    suspend fun startLogin()

    /** Clear all credentials and log out. */
    suspend fun logout()
}
