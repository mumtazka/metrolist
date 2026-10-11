/**
 * Desktop preferences — stores login cookie + settings in ~/.metrolist/config.json
 */

package com.metrolist.desktop.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch


/**
 * A single recent-search entry persisted to disk.
 * Stores just enough to render the dropdown row without a network call.
 */
@Serializable
data class RecentSearchEntry(
    val videoId: String,
    val title: String,
    val artistName: String,
    val thumbnailUrl: String?,
)

@Serializable
data class DesktopConfig(
    val cookie: String = "",
    val visitorData: String = "",
    val dataSyncId: String = "",
    val accountName: String = "",
    val accountEmail: String = "",
    val themeColorArgb: Int? = null,
    val pureBlack: Boolean = true,
    val lyricsPanelHeightDp: Float = 360f,
    val audioCacheSizeMb: Int = 100,
    val dynamicColorFromAlbumArt: Boolean = true,
    val sidebarPlaylistOrder: List<String> = emptyList(),
    /** Up to 10 most-recently played songs from search, newest first. */
    val recentSearches: List<RecentSearchEntry> = emptyList(),
    val listenTogetherServerUrl: String = "",
    val listenTogetherUsername: String = "",
    val listenTogetherSessionToken: String = "",
    val listenTogetherRoomCode: String = "",
    val listenTogetherAutoApproval: Boolean = false,
    val listenTogetherSyncVolume: Boolean = true,
    val listenTogetherAutoApproveSuggestions: Boolean = false,
    val listenTogetherBlockedUsers: Set<String> = emptySet(),
)

object DesktopPreferences {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val configDir = runCatching {
        File(System.getProperty("user.home") ?: ".", ".metrolist").also { it.mkdirs() }
    }.getOrElse {
        File(".metrolist").also { it.mkdirs() }
    }
    private val configFile = File(configDir, "config.json")

    fun load(): DesktopConfig {
        if (!configFile.exists()) return DesktopConfig()
        return try {
            json.decodeFromString<DesktopConfig>(configFile.readText())
        } catch (e: Exception) {
            DesktopConfig()
        }
    }

    fun save(config: DesktopConfig) {
        scope.launch {
            try {
                if (!configDir.exists()) {
                    configDir.mkdirs()
                }
                configFile.writeText(json.encodeToString(config))
            } catch (e: Exception) {
                println("[Preferences] Failed to save config: ${e.message}")
            }
        }
    }

    val isLoggedIn: Boolean
        get() = load().cookie.contains("SAPISID")
}
