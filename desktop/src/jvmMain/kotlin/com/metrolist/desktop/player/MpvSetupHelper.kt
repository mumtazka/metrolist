/**
 * Metrolist Desktop — mpv Setup Helper (Windows)
 *
 * Resolves the path to the mpv executable on the current platform:
 *  1. App install directory (bundled mpv.exe — future: zip-bundled release)
 *  2. System PATH (Linux/macOS users who installed mpv via package manager)
 *  3. Auto-download cache: %LOCALAPPDATA%\Metrolist\mpv\mpv.exe  (Windows only)
 *
 * If mpv cannot be found, [getMpvExecutable] returns null.
 * The caller ([PlayerState] / [MpvAudioPlayer]) should surface a setup dialog.
 */

package com.metrolist.desktop.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object MpvSetupHelper {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /**
     * The auto-download directory on Windows.
     * Uses %LOCALAPPDATA%\Metrolist\mpv — no admin rights required.
     */
    private val windowsMpvDir: File by lazy {
        val localAppData = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
        File(localAppData, "Metrolist${File.separator}mpv")
    }

    /** mpv.exe bundled alongside the app .exe (set by the installer via app resources dir) */
    private val bundledMpv: File by lazy {
        val jarDir = runCatching {
            File(MpvSetupHelper::class.java.protectionDomain.codeSource.location.toURI()).parentFile
        }.getOrNull()
        File(jarDir, "mpv.exe")
    }

    /**
     * Returns the path to a working mpv executable, or null if not found.
     *
     * Resolution order on Windows:
     *  1. Bundled mpv.exe next to the app launcher
     *  2. mpv.exe in the auto-download cache (%LOCALAPPDATA%\Metrolist\mpv\mpv.exe)
     *  3. mpv.exe on PATH
     *
     * On Linux/macOS only PATH is checked (package manager installs go there).
     */
    fun getMpvExecutable(): String? {
        if (isWindows) {
            // 1. Bundled alongside the installer
            if (bundledMpv.exists()) {
                println("[MpvSetup] Using bundled mpv: ${bundledMpv.absolutePath}")
                return bundledMpv.absolutePath
            }

            // 2. Previously auto-downloaded
            val cached = File(windowsMpvDir, "mpv.exe")
            if (cached.exists()) {
                println("[MpvSetup] Using cached mpv: ${cached.absolutePath}")
                return cached.absolutePath
            }
        }

        // 3. PATH lookup (works on all platforms)
        val fromPath = findOnPath(if (isWindows) "mpv.exe" else "mpv")
        if (fromPath != null) {
            println("[MpvSetup] Found mpv on PATH: $fromPath")
            return fromPath
        }

        println("[MpvSetup] mpv not found (bundled=${bundledMpv.absolutePath}, cached=${if (isWindows) File(windowsMpvDir, "mpv.exe").absolutePath else "N/A"})")
        return null
    }

    /** Returns true if mpv is available without needing to download anything. */
    fun isMpvAvailable(): Boolean = getMpvExecutable() != null

    /**
     * Downloads a portable mpv build for Windows into %LOCALAPPDATA%\Metrolist\mpv\.
     *
     * The download URL points to the official mpv.io Windows build (x86-64, ~30 MB zip).
     * Progress is reported via [onProgress] (0.0–1.0).
     *
     * @throws Exception if download or extraction fails.
     */
    suspend fun downloadMpvForWindows(onProgress: (Float) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            check(isWindows) { "downloadMpvForWindows() is only supported on Windows" }

            // Official mpv portable build — architecture: x86-64 (covers all modern Windows)
            val downloadUrl = "https://nightly.link/shinchiro/mpv-winbuild-cmake/workflows/daily/master/mpv-x86_64-v3-20240101.zip"
            // Fallback mirror (SourceForge) if the above is slow:
            // val downloadUrl = "https://sourceforge.net/projects/mpv-player-windows/files/latest/download"

            windowsMpvDir.mkdirs()
            val zipFile = File(windowsMpvDir, "mpv-download.zip")

            println("[MpvSetup] Downloading mpv from: $downloadUrl")
            onProgress(0.0f)

            // Download with progress
            val connection = URL(downloadUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("User-Agent", "Metrolist-Desktop/1.0")
            connection.connect()

            val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            var downloadedBytes = 0L

            FileOutputStream(zipFile).use { out ->
                val buffer = ByteArray(64 * 1024)
                connection.inputStream.use { input ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            onProgress(0.05f + (downloadedBytes.toFloat() / totalBytes) * 0.80f)
                        }
                    }
                }
            }
            connection.disconnect()
            println("[MpvSetup] Download complete (${downloadedBytes / 1024} KB), extracting...")
            onProgress(0.85f)

            // Extract mpv.exe (and optionally its DLLs) from the zip
            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    // Extract mpv.exe and all .dll files at the root of the zip
                    if (!entry.isDirectory && (name.endsWith("mpv.exe") || name.endsWith(".dll"))) {
                        val outFile = File(windowsMpvDir, File(name).name)
                        FileOutputStream(outFile).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var read: Int
                            while (zis.read(buffer).also { read = it } != -1) {
                                out.write(buffer, 0, read)
                            }
                        }
                        println("[MpvSetup] Extracted: ${outFile.name}")
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // Cleanup zip
            zipFile.delete()
            onProgress(1.0f)

            val mpvExe = File(windowsMpvDir, "mpv.exe")
            check(mpvExe.exists()) { "mpv.exe was not found in the downloaded archive" }
            println("[MpvSetup] mpv ready at: ${mpvExe.absolutePath}")
            mpvExe.absolutePath
        }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun findOnPath(executable: String): String? {
        val pathEnv = System.getenv("PATH") ?: return null
        val dirs = pathEnv.split(File.pathSeparator)
        for (dir in dirs) {
            val candidate = File(dir, executable)
            if (candidate.exists() && candidate.canExecute()) {
                return candidate.absolutePath
            }
        }
        return null
    }
}
