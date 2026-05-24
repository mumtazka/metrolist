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
