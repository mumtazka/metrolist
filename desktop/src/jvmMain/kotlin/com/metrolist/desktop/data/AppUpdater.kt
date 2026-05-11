package com.metrolist.desktop.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.system.exitProcess

/** Version of the currently running desktop build. Keep this aligned with desktop/build.gradle.kts. */
const val DESKTOP_APP_VERSION = "1.0.0"

private const val GITHUB_RELEASES_API =
    "https://api.github.com/repos/mumtazka/metrolist/releases/latest"
private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
private const val EXIT_AFTER_INSTALLER_LAUNCH_MS = 2_000L

enum class UpdateDownloadState { IDLE, DOWNLOADING, DONE, ERROR }

@Serializable
data class GhRelease(
    @SerialName("tag_name") val tagName: String,
    val body: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
data class GhAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
)

object AppUpdater {

    var updateAvailable by mutableStateOf(false)
        private set
    var latestVersion by mutableStateOf<String?>(null)
        private set
    var releaseNotes by mutableStateOf<String?>(null)
        private set
    var downloadState by mutableStateOf(UpdateDownloadState.IDLE)
        private set
    var downloadProgress by mutableStateOf(0f)
        private set
    var downloadedInstallerPath by mutableStateOf<String?>(null)
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = HttpClient(OkHttp)
    private val json = Json { ignoreUnknownKeys = true }

    private var periodicJob: Job? = null
    private var selectedAsset: GhAsset? = null

    fun startPeriodicCheck() {
        if (periodicJob != null) return

        periodicJob = scope.launch {
            while (isActive) {
                try {
                    checkForUpdate()
                } catch (e: Exception) {
                    println("[Updater] Failed to check for updates: ${e.message}")
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    suspend fun checkForUpdate() {
        val previousVersion = latestVersion
        val release = fetchLatestRelease()
        val normalizedLatestVersion = normalizeVersion(release.tagName)
        val asset = findMatchingAsset(release.assets)

        if (previousVersion != null && previousVersion != normalizedLatestVersion) {
            resetDownloadState()
        }

        latestVersion = normalizedLatestVersion
        releaseNotes = release.body?.trim()?.takeIf { it.isNotBlank() }
        selectedAsset = asset
        updateAvailable = asset != null && isNewer(normalizedLatestVersion, DESKTOP_APP_VERSION)

        if (!updateAvailable && downloadState != UpdateDownloadState.DOWNLOADING) {
            resetDownloadState()
        }
    }

    fun downloadUpdate() {
        if (downloadState == UpdateDownloadState.DOWNLOADING) return

        scope.launch {
            var destination: File? = null

            try {
                if (selectedAsset == null) {
                    checkForUpdate()
                }

                val asset = selectedAsset
                    ?.takeIf { updateAvailable }
                    ?: error("No compatible desktop installer found for this release.")
                val version = latestVersion ?: normalizeVersion(asset.name)
                val extension = asset.name.substringAfterLast('.', "")
                    .takeIf { it.isNotBlank() }
                    ?.let { ".$it" }
                    .orEmpty()

                destination = File(
                    System.getProperty("java.io.tmpdir"),
                    "metrolist-update-$version$extension",
                )

                downloadState = UpdateDownloadState.DOWNLOADING
                downloadProgress = 0f
                downloadedInstallerPath = null

                downloadFile(asset.downloadUrl, destination) { fraction ->
                    downloadProgress = fraction.coerceIn(0f, 1f)
                }

                downloadProgress = 1f
                downloadedInstallerPath = destination.absolutePath
                downloadState = UpdateDownloadState.DONE

                applyUpdate()
            } catch (e: Exception) {
                destination?.delete()
                downloadedInstallerPath = null
                downloadProgress = 0f
                downloadState = UpdateDownloadState.ERROR
                println("[Updater] Failed to download update: ${e.message}")
            }
        }
    }

    fun applyUpdate() {
        val installerPath = downloadedInstallerPath ?: return
        val installer = File(installerPath)

        if (!installer.exists()) {
            downloadState = UpdateDownloadState.ERROR
            println("[Updater] Installer not found at $installerPath")
            return
        }

        try {
            openWithSystemHandler(installer)
            scope.launch {
                delay(EXIT_AFTER_INSTALLER_LAUNCH_MS)
                exitProcess(0)
            }
        } catch (e: Exception) {
            downloadState = UpdateDownloadState.ERROR
            println("[Updater] Failed to launch installer: ${e.message}")
        }
    }

    fun openDownloadedInstallerLocation() {
        val installerPath = downloadedInstallerPath ?: return
        val installer = File(installerPath)
        val folder = installer.parentFile?.takeIf { it.exists() } ?: return

        try {
            openWithSystemHandler(folder)
        } catch (e: Exception) {
            println("[Updater] Failed to open installer folder: ${e.message}")
        }
    }

    private suspend fun fetchLatestRelease(): GhRelease {
        val response = client.get(GITHUB_RELEASES_API) {
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header(HttpHeaders.UserAgent, "Metrolist-Desktop-Updater")
        }
        return json.decodeFromString<GhRelease>(response.bodyAsText())
    }

    private fun findMatchingAsset(assets: List<GhAsset>): GhAsset? {
        val keywords = currentOsAssetKeywords()
        if (keywords.isEmpty()) return null

        return assets.firstOrNull { asset ->
            keywords.any { keyword -> asset.name.endsWith(keyword, ignoreCase = true) }
        }
    }

    private suspend fun downloadFile(url: String, dest: File, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.connect()

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L
            val buffer = ByteArray(64 * 1024)

            dest.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            onProgress(downloadedBytes.toFloat() / totalBytes)
                        }
                    }
                }
            }
            connection.disconnect()
        }
    }

    private fun openWithSystemHandler(target: File) {
        runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(target)
                return
            }
        }

        when {
            isWindows() -> ProcessBuilder("cmd", "/c", "start", "", target.absolutePath).start()
            isMac() -> ProcessBuilder("open", target.absolutePath).start()
            else -> ProcessBuilder("xdg-open", target.absolutePath).start()
        }
    }

    private fun resetDownloadState() {
        downloadState = UpdateDownloadState.IDLE
        downloadProgress = 0f
        downloadedInstallerPath = null
    }
}

private fun currentOsAssetKeywords(): List<String> {
    val osName = System.getProperty("os.name").lowercase()
    return when {
        osName.contains("linux") -> listOf(".deb")
        osName.contains("windows") -> listOf(".exe", ".msi")
        osName.contains("mac") -> listOf(".dmg")
        else -> emptyList()
    }
}

private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("windows")

private fun isMac(): Boolean = System.getProperty("os.name").lowercase().contains("mac")

private fun normalizeVersion(version: String): String = version.trim().removePrefix("v").removePrefix("V")

private fun isNewer(latest: String, current: String): Boolean {
    val latestParts = normalizeVersion(latest).split(".").map { it.toIntOrNull() ?: 0 }
    val currentParts = normalizeVersion(current).split(".").map { it.toIntOrNull() ?: 0 }

    for (index in 0..2) {
        val latestValue = latestParts.getOrElse(index) { 0 }
        val currentValue = currentParts.getOrElse(index) { 0 }
        if (latestValue > currentValue) return true
        if (latestValue < currentValue) return false
    }

    return false
}
