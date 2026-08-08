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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess

/** Version of the currently running desktop build. Keep this aligned with desktop/build.gradle.kts. */
const val DESKTOP_APP_VERSION = "1.0.0"

private const val GITHUB_RELEASES_API =
    "https://api.github.com/repos/mumtazka/metrolist/releases/latest"
private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
private const val EXIT_AFTER_INSTALLER_LAUNCH_MS = 2_000L
private const val UPDATE_HELPER_FLAG = "--metrolist-apply-update"

enum class UpdateDownloadState { IDLE, DOWNLOADING, DONE, APPLYING, ERROR }

enum class UpdatePackageKind {
    PORTABLE_ZIP,
    INSTALLER,
}

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

private data class SelectedUpdateAsset(
    val asset: GhAsset,
    val kind: UpdatePackageKind,
)

private data class UpdateHelperArgs(
    val archivePath: File,
    val installRoot: File,
    val parentPid: Long?,
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
    private var selectedAssetKind: UpdatePackageKind? = null

    fun handleCommandLineArguments(args: Array<String>): Boolean {
        val helperArgs = parseUpdateHelperArgs(args) ?: return false

        runPortableUpdateHelper(helperArgs)
        return true
    }

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
        val selection = findMatchingAsset(release.assets)

        if (previousVersion != null && previousVersion != normalizedLatestVersion) {
            resetDownloadState()
        }

        latestVersion = normalizedLatestVersion
        releaseNotes = release.body?.trim()?.takeIf { it.isNotBlank() }
        selectedAsset = selection?.asset
        selectedAssetKind = selection?.kind
        updateAvailable = selection != null && isNewer(normalizedLatestVersion, DESKTOP_APP_VERSION)

        if (!updateAvailable && downloadState !in setOf(UpdateDownloadState.DOWNLOADING, UpdateDownloadState.APPLYING)) {
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
                val kind = selectedAssetKind ?: UpdatePackageKind.INSTALLER
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

                applyUpdate(kind)
            } catch (e: Exception) {
                destination?.delete()
                downloadedInstallerPath = null
                downloadProgress = 0f
                downloadState = UpdateDownloadState.ERROR
                println("[Updater] Failed to download update: ${e.message}")
            }
        }
    }

    fun applyUpdate(kind: UpdatePackageKind = selectedAssetKind ?: UpdatePackageKind.INSTALLER) {
        val installerPath = downloadedInstallerPath ?: return
        val installer = File(installerPath)

        if (!installer.exists()) {
            downloadState = UpdateDownloadState.ERROR
            println("[Updater] Installer not found at $installerPath")
            return
        }

        downloadState = UpdateDownloadState.APPLYING

        try {
            when (kind) {
                UpdatePackageKind.PORTABLE_ZIP -> {
                    if (!launchPortableUpdateHelper(installer)) {
                        downloadState = UpdateDownloadState.ERROR
                        return
                    }
                }

                UpdatePackageKind.INSTALLER -> {
                    openWithSystemHandler(installer)
                }
            }

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

    private fun launchPortableUpdateHelper(archive: File): Boolean {
        val installRoot = resolvePortableInstallRoot() ?: run {
            println("[Updater] Portable install root not found; falling back to installer flow.")
            return false
        }
        val launcher = launcherPath(installRoot)
        if (!launcher.exists()) {
            println("[Updater] Portable launcher not found at ${launcher.absolutePath}")
            return false
        }

        val helperArgs = buildList {
            add(UPDATE_HELPER_FLAG)
            add(archive.absolutePath)
            add(installRoot.absolutePath)
            add(ProcessHandle.current().pid().toString())
        }

        ProcessBuilder(launcher.absolutePath, *helperArgs.toTypedArray())
            .directory(installRoot)
            .start()
        return true
    }

    private suspend fun fetchLatestRelease(): GhRelease {
        val response = client.get(GITHUB_RELEASES_API) {
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header(HttpHeaders.UserAgent, "Metrolist-Desktop-Updater")
        }
        return json.decodeFromString<GhRelease>(response.bodyAsText())
    }

    private fun findMatchingAsset(assets: List<GhAsset>): SelectedUpdateAsset? {
        if (assets.isEmpty()) return null

        if (canUsePortableUpdate()) {
            assets.firstOrNull(::matchesPortableUpdateAsset)?.let {
                return SelectedUpdateAsset(it, UpdatePackageKind.PORTABLE_ZIP)
            }
        }

        assets.firstOrNull(::matchesInstallerAsset)?.let {
            return SelectedUpdateAsset(it, UpdatePackageKind.INSTALLER)
        }

        return null
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

    private fun runPortableUpdateHelper(args: UpdateHelperArgs) {
        if (args.parentPid != null) {
            waitForProcessExit(args.parentPid)
        }

        val archive = args.archivePath
        val installRoot = args.installRoot
        if (!archive.exists()) {
            println("[Updater] Portable update archive not found at ${archive.absolutePath}")
            return
        }

        val stagingDir = Files.createTempDirectory("metrolist-update-").toFile()
        try {
            extractZipArchive(archive, stagingDir)
            val payloadRoot = resolvePayloadRoot(stagingDir)
            syncDirectory(payloadRoot, installRoot)
            launchInstalledApp(installRoot)
        } catch (e: Exception) {
            println("[Updater] Failed to apply portable update: ${e.message}")
        } finally {
            stagingDir.deleteRecursively()
            archive.delete()
        }
    }

    private fun waitForProcessExit(pid: Long) {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return

        while (handle.isAlive) {
            Thread.sleep(250)
        }
    }

    private fun extractZipArchive(archive: File, destination: File) {
        destination.mkdirs()
        val destinationRoot = destination.toPath().toAbsolutePath().normalize()

        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = destinationRoot.resolve(entry.name).normalize()

                require(target.startsWith(destinationRoot)) {
                    "Blocked suspicious zip entry: ${entry.name}"
                }

                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
                }

                zip.closeEntry()
            }
        }
    }

    private fun resolvePayloadRoot(stagingDir: File): File {
        val children = stagingDir.listFiles().orEmpty().filter { it.exists() }
        return if (children.size == 1 && children[0].isDirectory) {
            children[0]
        } else {
            stagingDir
        }
    }

    private fun syncDirectory(sourceRoot: File, destinationRoot: File) {
        require(sourceRoot.exists()) { "Source directory does not exist: ${sourceRoot.absolutePath}" }
        destinationRoot.mkdirs()

        destinationRoot.listFiles()?.forEach { child ->
            child.deleteRecursively()
        }

        val sourcePath = sourceRoot.toPath().toAbsolutePath().normalize()
        val destinationPath = destinationRoot.toPath().toAbsolutePath().normalize()

        sourceRoot.walkTopDown().forEach { source ->
            val relative = sourcePath.relativize(source.toPath().toAbsolutePath().normalize())
            val target = destinationPath.resolve(relative.toString())

            if (source.isDirectory) {
                Files.createDirectories(target)
            } else {
                Files.createDirectories(target.parent)
                Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
                if (relative.toString().startsWith("bin/") || relative.toString().startsWith("bin\\")) {
                    target.toFile().setExecutable(true, false)
                }
            }
        }
    }

    private fun launchInstalledApp(installRoot: File) {
        val launcher = launcherPath(installRoot)
        if (!launcher.exists()) {
            println("[Updater] Updated launcher not found at ${launcher.absolutePath}")
            return
        }

        ProcessBuilder(launcher.absolutePath).directory(installRoot).start()
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

    private fun canUsePortableUpdate(): Boolean =
        resolvePortableInstallRoot()?.let(::launcherPath)?.exists() == true

    private fun resolvePortableInstallRoot(): File? {
        val location = runCatching {
            AppUpdater::class.java.protectionDomain.codeSource?.location?.toURI()
        }.getOrNull() ?: return null
        val jarFile = runCatching { Path.of(location).toFile() }.getOrNull() ?: return null
        if (!jarFile.isFile || !jarFile.name.endsWith(".jar", ignoreCase = true)) return null

        val libDir = jarFile.parentFile ?: return null
        val installRoot = libDir.parentFile ?: return null
        if (!File(installRoot, "bin").isDirectory || !File(installRoot, "lib").isDirectory) return null

        return installRoot
    }
}

private fun matchesPortableUpdateAsset(asset: GhAsset): Boolean {
    val osToken = currentOsToken() ?: return false
    return asset.name.contains(osToken, ignoreCase = true) && asset.name.endsWith(".zip", ignoreCase = true)
}

private fun matchesInstallerAsset(asset: GhAsset): Boolean {
    val osName = currentOsToken() ?: return false
    return when {
        osName == "linux" -> asset.name.endsWith(".deb", ignoreCase = true)
        osName == "windows" -> asset.name.endsWith(".exe", ignoreCase = true) ||
            asset.name.endsWith(".msi", ignoreCase = true)
        osName == "mac" -> asset.name.endsWith(".dmg", ignoreCase = true)
        else -> false
    }
}

private fun currentOsToken(): String? {
    val osName = System.getProperty("os.name").lowercase()
    return when {
        osName.contains("linux") -> "linux"
        osName.contains("windows") -> "windows"
        osName.contains("mac") -> "mac"
        else -> null
    }
}

private fun launcherPath(installRoot: File): File =
    if (isWindows()) {
        File(installRoot, "bin/Metrolist.exe")
    } else {
        File(installRoot, "bin/Metrolist")
    }

private fun parseUpdateHelperArgs(args: Array<String>): UpdateHelperArgs? {
    if (args.getOrNull(0) != UPDATE_HELPER_FLAG) return null

    val archivePath = args.getOrNull(1)?.let(::File) ?: return null
    val installRoot = args.getOrNull(2)?.let(::File) ?: return null
    val parentPid = args.getOrNull(3)?.toLongOrNull()

    return UpdateHelperArgs(
        archivePath = archivePath,
        installRoot = installRoot,
        parentPid = parentPid,
    )
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
