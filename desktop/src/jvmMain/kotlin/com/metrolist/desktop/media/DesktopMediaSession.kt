package com.metrolist.desktop.media

import androidx.compose.runtime.Stable
import com.metrolist.desktop.player.PlayerState
import com.metrolist.desktop.player.PlayerState.RepeatMode
import java.awt.Window

@Stable
data class DesktopMediaSnapshot(
    val songId: String?,
    val title: String?,
    val artist: String?,
    val albumArt: String?,
    val durationMs: Long,
    val positionMs: Long,
    val isPlaying: Boolean,
    val isShuffled: Boolean,
    val repeatMode: RepeatMode,
    val volume: Float,
    val canGoNext: Boolean,
    val canGoPrevious: Boolean,
) {
    val mediaTitle: String?
        get() = when {
            title.isNullOrBlank() && artist.isNullOrBlank() -> null
            artist.isNullOrBlank() -> title
            title.isNullOrBlank() -> artist
            else -> "$title - $artist"
        }
}

fun PlayerState.toMediaSnapshot(): DesktopMediaSnapshot = DesktopMediaSnapshot(
    songId = currentSong?.id,
    title = currentSong?.title,
    artist = currentSong?.artist,
    albumArt = currentSong?.albumArt,
    durationMs = duration,
    positionMs = currentPosition,
    isPlaying = isPlaying,
    isShuffled = isShuffled,
    repeatMode = repeatMode,
    volume = volume,
    canGoNext = queue.isNotEmpty() && queueIndex < queue.size - 1,
    canGoPrevious = currentPosition > 3000 || (queue.isNotEmpty() && queueIndex > 0),
)

interface DesktopMediaSession : AutoCloseable {
    fun update(snapshot: DesktopMediaSnapshot)

    override fun close()
}

fun createDesktopMediaSession(
    playerState: PlayerState,
    awtWindow: Window? = null,
    onShowWindow: () -> Unit = {},
    onExit: () -> Unit = {},
): DesktopMediaSession {
    val sessions = mutableListOf<DesktopMediaSession>()

    // Platform-specific media session
    val osName = System.getProperty("os.name").lowercase()
    when {
        osName.contains("linux") -> {
            sessions.add(LinuxMprisMediaSession(playerState))
        }
        osName.contains("win") -> {
            sessions.add(WindowsMediaSession(playerState, awtWindow))
        }
        osName.contains("mac") -> {
            sessions.add(MacOsMediaSession(playerState, awtWindow))
        }
    }

    // Cross-platform system tray with notifications
    sessions.add(SystemTraySession(playerState, onShowWindow, onExit))

    return CompositeMediaSession(sessions)
}

private class CompositeMediaSession(
    private val sessions: List<DesktopMediaSession>,
) : DesktopMediaSession {
    override fun update(snapshot: DesktopMediaSnapshot) {
        for (session in sessions) {
            try {
                session.update(snapshot)
            } catch (t: Exception) {
                println("[MediaSession] Session error: ${t.message}")
            }
        }
    }

    override fun close() {
        for (session in sessions.reversed()) {
            try {
                session.close()
            } catch (_: Exception) {
            }
        }
    }
}
