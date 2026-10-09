package com.metrolist.desktop.player

import com.metrolist.innertube.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/** Single-track mpv launcher. A failed launch is always reported to PlayerState. */
class MpvAudioPlayer {
    private var mpvProcess: Process? = null
    private var ipcSocket = ""
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val positionRegex = Regex("""POS:(\d+\.?\d*)""")

    var onTrackEnd: ((success: Boolean) -> Unit)? = null
    var onPlaybackStarted: (() -> Unit)? = null
    var onPositionUpdate: ((Long) -> Unit)? = null

    @Volatile private var isStopping = false
    @Volatile private var playbackStartedFired = false

    fun play(url: String, clientName: String = "WEB_REMIX", mediaTitle: String? = null) {
        stop()
        isStopping = false
        playbackStartedFired = false

        val executable = MpvSetupHelper.getMpvExecutable()
        if (executable == null) {
            println("[mpv] ERROR: mpv not found")
            onTrackEnd?.invoke(false)
            return
        }

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        val socketName = "metrolist-mpv-${System.currentTimeMillis()}"
        ipcSocket = when {
            isWindows -> "\\\\.\\pipe\\$socketName"
            isMac -> "/tmp/$socketName"
            else -> "${System.getProperty("java.io.tmpdir")}/$socketName"
        }

        val userAgent = playbackUserAgent(clientName)
        val headers = mutableListOf("User-Agent: $userAgent")
        if (clientName.contains("WEB")) {
            headers += "Referer: https://music.youtube.com/"
            headers += "Origin: https://music.youtube.com"
        }
        YouTube.cookie?.takeIf { it.isNotBlank() }?.let { headers += "Cookie: ${it.replace(";", "\\;")}" }
        val escapedHeaders = headers.map { it.replace("\\", "\\\\").replace(";", "\\;") }

        val command = mutableListOf(
            executable,
            "--no-video",
            "--ytdl=no",
            "--input-ipc-server=$ipcSocket",
            "--volume=70",
            "--http-header-fields=${escapedHeaders.joinToString(";")}",
            "--cache=yes",
            "--cache-pause=yes",
            "--cache-pause-initial=no",
            "--demuxer-max-bytes=32MiB",
            "--demuxer-readahead-secs=15",
            "--cache-secs=60",
            "--network-timeout=15",
            "--stream-buffer-size=1MiB",
            "--term-status-msg=POS:" + "\${=time-pos}",
            url,
        )
        if (isWindows) command.add(5, "--media-controls=yes")
        mediaTitle?.takeIf { it.isNotBlank() }?.let {
            command.add(1, "--title=$it")
            command.add(2, "--force-media-title=$it")
        }

        try {
            println("[mpv] Starting playback (client=$clientName)")
            mpvProcess = ProcessBuilder(command).redirectErrorStream(true).start()
            val process = mpvProcess ?: return
            scope.launch { readOutput(process) }
            scope.launch {
                val exitCode = process.waitFor()
                println("[mpv] Process exited with code $exitCode")
                if (!isStopping && process == mpvProcess) onTrackEnd?.invoke(exitCode == 0 || exitCode == 4)
            }
        } catch (error: Exception) {
            println("[mpv] Failed to start: ${error.message}")
            onTrackEnd?.invoke(false)
        }
    }

    fun pause() = sendCommand("cycle", "pause")
    fun resume() = sendCommand("set_property", "pause", "false")
    fun togglePause() = sendCommand("cycle", "pause")
    fun seekTo(seconds: Double) = sendCommand("seek", seconds.toString(), "absolute")
    fun setVolume(volume: Int) = sendCommand("set_property", "volume", volume.coerceIn(0, 100).toString())

    fun stop() {
        isStopping = true
        try {
            mpvProcess?.destroyForcibly()
            mpvProcess = null
            if (ipcSocket.isNotBlank()) File(ipcSocket).delete()
        } catch (_: Exception) {}
    }

    val isRunning: Boolean get() = mpvProcess?.isAlive == true

    private fun readOutput(process: Process) {
        try {
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                lines.forEach { line ->
                    val match = positionRegex.find(line)
                    if (match != null) {
                        val positionMs = ((match.groupValues[1].toDoubleOrNull() ?: 0.0) * 1000).toLong()
                        if (!playbackStartedFired) {
                            playbackStartedFired = true
                            onPlaybackStarted?.invoke()
                        }
                        onPositionUpdate?.invoke(positionMs)
                    } else if (line.isNotBlank()) println("[mpv/out] $line")
                }
            }
        } catch (_: Exception) {}
    }

    private fun sendCommand(vararg args: String) {
        if (ipcSocket.isBlank()) return
        scope.launch {
            try {
                val json = "{\"command\":[${args.joinToString(",") { "\"$it\"" }}]}\n"
                if (System.getProperty("os.name").lowercase().contains("win")) {
                    java.io.RandomAccessFile(ipcSocket, "rw").use { it.writeBytes(json) }
                } else {
                    val socketFile = File(ipcSocket)
                    if (!socketFile.exists()) return@launch
                    val channel = java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX)
                    channel.connect(java.net.UnixDomainSocketAddress.of(ipcSocket))
                    channel.write(java.nio.ByteBuffer.wrap(json.toByteArray()))
                    channel.close()
                }
            } catch (error: Exception) {
                println("[mpv] IPC error: ${error.message}")
            }
        }
    }

    private fun playbackUserAgent(clientName: String) = when {
        clientName.contains("ANDROID") || clientName.contains("VR") -> "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip"
        clientName.contains("IOS") || clientName.contains("IPAD") -> "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)"
        else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    }
}
