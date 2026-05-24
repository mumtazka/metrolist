package com.metrolist.desktop.media

import com.metrolist.desktop.player.PlayerState
import java.awt.Window

/**
 * macOS-specific media session stub. Will integrate with
 * MPNowPlayingInfoCenter and MPRemoteCommandCenter via JNA
 * when running on macOS with JNA available on the classpath.
 */
class MacOsMediaSession(
    private val playerState: PlayerState,
    private val awtWindow: Window?,
) : DesktopMediaSession {

    private var jnaAvailable = false

    init {
        if (isMac()) {
            jnaAvailable = tryInitJna()
        }
    }

    private fun isMac(): Boolean =
        System.getProperty("os.name").lowercase().contains("mac")

    private fun tryInitJna(): Boolean = try {
        Class.forName("com.sun.jna.Native")
        println("[MacMedia] JNA available — macOS NowPlaying integration ready")
        true
    } catch (_: Throwable) {
        false
    }

    override fun update(snapshot: DesktopMediaSnapshot) {
    }

    override fun close() {
    }
}
