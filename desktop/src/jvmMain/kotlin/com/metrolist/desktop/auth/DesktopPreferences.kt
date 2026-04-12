/**
 * Desktop preferences — stores login cookie + settings in ~/.metrolist/config.json
 */

package com.metrolist.desktop.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class DesktopConfig(
    val cookie: String = "",
    val visitorData: String = "",
    val dataSyncId: String = "",
    val accountName: String = "",
    val accountEmail: String = "",
    val themeColorArgb: Int? = null,
    val pureBlack: Boolean = true,
)

object DesktopPreferences {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val configDir = File(System.getProperty("user.home"), ".metrolist")
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
        configDir.mkdirs()
        configFile.writeText(json.encodeToString(config))
    }

    val isLoggedIn: Boolean
        get() = load().cookie.contains("SAPISID")
}
