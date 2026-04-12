/**
 * Metrolist Desktop — mpv Audio Player
 * Plays YouTube Music streams via mpv subprocess
 */

package com.metrolist.desktop.player

import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Controls mpv for audio playback.
 * Uses IPC socket for pause/seek/volume commands.
 */
class MpvAudioPlayer {
    private var mpvProcess: Process? = null
    private var ipcSocket: String = ""
    var onTrackEnd: ((success: Boolean) -> Unit)? = null

    /** When true, suppress onTrackEnd callbacks (we are deliberately killing the process). */
    @Volatile
    private var isStopping = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Play an audio URL via mpv.
     * Kills any existing mpv process first.
     */
    fun play(url: String, clientName: String = "WEB_REMIX") {
        stop()
        isStopping = false  // Reset after stop

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val isMac = System.getProperty("os.name").lowercase().contains("mac")

        val socketName = "metrolist-mpv-${System.currentTimeMillis()}"
        ipcSocket = if (isWindows) {
            """\\.\pipe\$socketName"""
        } else if (isMac) {
            "/tmp/$socketName"
        } else {
            val tmpDir = System.getProperty("java.io.tmpdir")
            "$tmpDir/$socketName"
        }

        // YouTube CDN validates User-Agent matches the client that requested the stream
        val userAgent = when {
            clientName.contains("ANDROID") || clientName.contains("VR") ->
                "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip"
            clientName.contains("IOS") || clientName.contains("IPAD") ->
                "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)"
            else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
        }

        val headers = mutableListOf("User-Agent: ${userAgent.replace(",", "\\\\,")}")
        if (clientName.contains("WEB")) {
            headers.add("Referer: https://music.youtube.com/")
            headers.add("Origin: https://music.youtube.com")
        }

        val cmd = mutableListOf(
            "mpv",
            "--no-video",
            "--ytdl=no",
            "--input-ipc-server=$ipcSocket",
            "--volume=70",
            "--http-header-fields=${headers.joinToString(",")}",
            url
        )

        try {
            println("[mpv] Starting playback...")
            mpvProcess = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()

            val currentProcess = mpvProcess

            // Read mpv output for debugging
            scope.launch {
                try {
                    val reader = BufferedReader(InputStreamReader(currentProcess!!.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        println("[mpv] $line")
                    }
                } catch (_: Exception) {}
            }

            // Monitor process exit
            scope.launch {
                val exitCode = currentProcess?.waitFor() ?: -1
                println("[mpv] Process exited with code: $exitCode")
                // Only fire callback if this wasn't a deliberate stop
                if (!isStopping && currentProcess == mpvProcess) {
                    val success = exitCode == 0 || exitCode == 4
                    onTrackEnd?.invoke(success)
                }
            }
        } catch (e: Exception) {
            println("[mpv] Failed to start: ${e.message}")
        }
    }

    fun pause() = sendCommand("cycle", "pause")

    fun resume() = sendCommand("set_property", "pause", "false")

    fun togglePause() = sendCommand("cycle", "pause")

    fun seekTo(seconds: Double) = sendCommand("seek", seconds.toString(), "absolute")

    fun setVolume(volume: Int) = sendCommand("set_property", "volume", volume.toString())

    fun stop() {
        isStopping = true
        try {
            mpvProcess?.destroyForcibly()
            mpvProcess = null
            if (ipcSocket.isNotBlank()) {
                File(ipcSocket).delete()
            }
        } catch (_: Exception) {}
    }

    val isRunning: Boolean
        get() = mpvProcess?.isAlive == true

    /**
     * Send a command to mpv via IPC socket.
     * Uses OS-native IPC protocols (Named Pipes on Win, Unix Domain Sockets on Linux/Mac)
     */
    private fun sendCommand(vararg args: String) {
        if (ipcSocket.isBlank()) return
        scope.launch {
            try {
                val json = """{"command": [${args.joinToString(",") { "\"$it\"" }}]}"""
                val isWindows = System.getProperty("os.name").lowercase().contains("win")

                if (isWindows) {
                    // Windows uses Named Pipes for mpv IPC
                    val file = java.io.RandomAccessFile(ipcSocket, "rw")
                    file.writeBytes("$json\n")
                    file.close()
                } else {
                    // Linux and Mac use Unix Domain Sockets
                    val socketFile = File(ipcSocket)
                    if (!socketFile.exists()) return@launch

                    val address = java.net.UnixDomainSocketAddress.of(ipcSocket)
                    val channel = java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX)
                    channel.connect(address)
                    val bytes = "$json\n".toByteArray()
                    channel.write(java.nio.ByteBuffer.wrap(bytes))
                    channel.close()
                }
            } catch (e: Exception) {
                println("[mpv] IPC error: ${e.message}")
            }
        }
    }
}
