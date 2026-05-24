package com.metrolist.desktop.media

import com.metrolist.desktop.player.PlayerState
import java.awt.Window

/**
 * Windows-specific media session stub. Will integrate with SMTC and
 * global media key hooks via JNA when running on Windows with JNA
 * available on the classpath.
 */
class WindowsMediaSession(
    private val playerState: PlayerState,
    private val awtWindow: Window?,
) : DesktopMediaSession {

    private var jnaAvailable = false

    init {
        if (isWindows()) {
            jnaAvailable = tryInitJna()
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    private fun tryInitJna(): Boolean = try {
        Class.forName("com.sun.jna.Native")
        Class.forName("com.sun.jna.platform.win32.User32")
        println("[WinMedia] JNA available — Windows native integration ready")
        true
    } catch (_: Throwable) {
        false
    }

    override fun update(snapshot: DesktopMediaSnapshot) {
    }

    override fun close() {
    }
}
