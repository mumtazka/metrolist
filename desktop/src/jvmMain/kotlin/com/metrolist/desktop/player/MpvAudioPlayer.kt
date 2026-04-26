/**
 * Metrolist Desktop — mpv Audio Player
 * Plays YouTube Music streams via mpv subprocess.
 *
 * Key improvements:
 *  - Fast-start flags: mpv begins playback with minimal buffering
 *  - Real position: parsed from mpv stdout A: lines (no fake timer)
 *  - onPlaybackStarted: fires when mpv first reports a position (audio is actually playing)
 *  - onPositionUpdate: delivers real position in ms from mpv to PlayerState
 */

package com.metrolist.desktop.player

import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class MpvAudioPlayer {
    private var mpvProcess: Process? = null
    private var ipcSocket: String = ""

    var onTrackEnd: ((success: Boolean) -> Unit)? = null

    /** Fired once when mpv reports the first real position (audio actually started). */
    var onPlaybackStarted: (() -> Unit)? = null

    /** Fired ~4x/sec with real playback position in milliseconds. */
    var onPositionUpdate: ((Long) -> Unit)? = null

    @Volatile private var isStopping = false
    @Volatile private var playbackStartedFired = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Matches our custom status line: "POS:123.456"
    private val positionRegex = Regex("""POS:(\d+\.?\d*)""")

    fun play(url: String, clientName: String = "WEB_REMIX") {
        stop()
        isStopping = false
        playbackStartedFired = false

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val isMac = System.getProperty("os.name").lowercase().contains("mac")

        val socketName = "metrolist-mpv-${System.currentTimeMillis()}"
        ipcSocket = when {
            isWindows -> """\\.\\pipe\\$socketName"""
            isMac     -> "/tmp/$socketName"
            else      -> "${System.getProperty("java.io.tmpdir")}/$socketName"
        }

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

            // ── Fast-start: begin playing after minimal buffering ──
            "--cache=yes",
            "--cache-pause=no",            // never pause to fill cache — just play
            "--demuxer-max-bytes=10MiB",   // max 10 MB in the demuxer buffer
            "--demuxer-readahead-secs=5",  // only read 5s ahead
            "--cache-secs=20",             // keep 20s of audio in back-buffer for seeks

            // ── Millisecond-precision position output ──
            // Outputs "POS:123.456" so we get float seconds, not HH:MM:SS
            // Use concatenation to prevent Kotlin from processing the ${...} as interpolation
            "--term-status-msg=POS:" + "\${=time-pos}",

            url
        )

        try {
            println("[mpv] Starting playback...")
            mpvProcess = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()

            val currentProcess = mpvProcess

            // Read stdout — parse real position from A: lines
            scope.launch {
                try {
                    val reader = BufferedReader(InputStreamReader(currentProcess!!.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: continue

                        // Parse: "POS:123.456" → milliseconds
                        val match = positionRegex.find(l)
                        if (match != null) {
                            val posMs = (match.groupValues[1].toDoubleOrNull() ?: 0.0) * 1000.0
                            onPositionUpdate?.invoke(posMs.toLong())

                            // Fire playback-started once on first real position report
                            if (!playbackStartedFired) {
                                playbackStartedFired = true
                                onPlaybackStarted?.invoke()
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // Monitor process exit
            scope.launch {
                val exitCode = currentProcess?.waitFor() ?: -1
                println("[mpv] Process exited with code: $exitCode")
                if (!isStopping && currentProcess == mpvProcess) {
                    val success = exitCode == 0 || exitCode == 4
                    onTrackEnd?.invoke(success)
                }
            }
        } catch (e: Exception) {
            println("[mpv] Failed to start: ${e.message}")
        }
    }

    fun pause()       = sendCommand("cycle", "pause")
    fun resume()      = sendCommand("set_property", "pause", "false")
    fun togglePause() = sendCommand("cycle", "pause")
    fun seekTo(seconds: Double) = sendCommand("seek", seconds.toString(), "absolute")
    fun setVolume(volume: Int)  = sendCommand("set_property", "volume", volume.toString())

    fun stop() {
        isStopping = true
        try {
            mpvProcess?.destroyForcibly()
            mpvProcess = null
            if (ipcSocket.isNotBlank()) File(ipcSocket).delete()
        } catch (_: Exception) {}
    }

    val isRunning: Boolean
        get() = mpvProcess?.isAlive == true

    private fun sendCommand(vararg args: String) {
        if (ipcSocket.isBlank()) return
        scope.launch {
            try {
                val json = """{"command": [${args.joinToString(",") { "\"$it\"" }}]}"""
                val isWindows = System.getProperty("os.name").lowercase().contains("win")
                if (isWindows) {
                    val file = java.io.RandomAccessFile(ipcSocket, "rw")
                    file.writeBytes("$json\n")
                    file.close()
                } else {
                    val socketFile = File(ipcSocket)
                    if (!socketFile.exists()) return@launch
                    val address = java.net.UnixDomainSocketAddress.of(ipcSocket)
                    val channel = java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX)
                    channel.connect(address)
                    channel.write(java.nio.ByteBuffer.wrap("$json\n".toByteArray()))
                    channel.close()
                }
            } catch (e: Exception) {
                println("[mpv] IPC error: ${e.message}")
            }
        }
    }
}
