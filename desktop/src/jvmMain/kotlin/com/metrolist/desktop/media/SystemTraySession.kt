package com.metrolist.desktop.media

import com.metrolist.desktop.player.PlayerState
import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

class SystemTraySession(
    private val playerState: PlayerState,
    private val onShowWindow: () -> Unit,
    private val onExit: () -> Unit,
) : DesktopMediaSession {

    private var trayIcon: TrayIcon? = null
    private var lastSnapshot = playerState.toMediaSnapshot()
    private val isSupported: Boolean = SystemTray.isSupported()

    private var playPauseItem: MenuItem? = null
    private var songInfoLabel: MenuItem? = null
    private var volumeLabel: MenuItem? = null

    init {
        if (isSupported) {
            setup()
        }
    }

    private fun setup() {
        SwingUtilities.invokeLater {
            try {
                val tray = SystemTray.getSystemTray()
                val image = createTrayImage()
                val popup = PopupMenu()

                songInfoLabel = MenuItem("Metrolist").apply {
                    isEnabled = false
                }

                playPauseItem = MenuItem("Play").apply {
                    addActionListener { playerState.togglePlayPause() }
                }

                val nextItem = MenuItem("Next").apply {
                    addActionListener { playerState.skipNext() }
                }
                val prevItem = MenuItem("Previous").apply {
                    addActionListener { playerState.skipPrevious() }
                }
                val stopItem = MenuItem("Stop").apply {
                    addActionListener { playerState.stop() }
                }

                volumeLabel = MenuItem("Volume: 70%").apply {
                    isEnabled = false
                }

                val showItem = MenuItem("Show Metrolist").apply {
                    addActionListener { onShowWindow() }
                }

                val quitItem = MenuItem("Quit").apply {
                    addActionListener { onExit() }
                }

                popup.add(songInfoLabel)
                popup.addSeparator()
                popup.add(playPauseItem)
                popup.add(nextItem)
                popup.add(prevItem)
                popup.add(stopItem)
                popup.addSeparator()
                popup.add(volumeLabel)
                popup.addSeparator()
                popup.add(showItem)
                popup.add(quitItem)

                val icon = TrayIcon(image, "Metrolist", popup).apply {
                    isImageAutoSize = true
                    addActionListener { playerState.togglePlayPause() }
                }

                tray.add(icon)
                trayIcon = icon
            } catch (t: Throwable) {
                println("[Tray] Failed to setup: ${t.message}")
            }
        }
    }

    override fun update(snapshot: DesktopMediaSnapshot) {
        if (!isSupported) return
        val previous = lastSnapshot
        lastSnapshot = snapshot

        SwingUtilities.invokeLater {
            updateTooltip(snapshot)
            playPauseItem?.label = if (snapshot.isPlaying) "Pause" else "Play"

            val volPercent = (snapshot.volume * 100).toInt()
            volumeLabel?.label = "Volume: $volPercent%"

            val title = snapshot.title ?: ""
            val artist = snapshot.artist ?: ""
            songInfoLabel?.label = when {
                title.isNotBlank() && artist.isNotBlank() -> "$title \u2014 $artist"
                title.isNotBlank() -> title
                artist.isNotBlank() -> artist
                else -> "Metrolist"
            }
        }

        if (snapshot.songId != null && previous.songId != snapshot.songId) {
            showNotification(snapshot)
        }
    }

    private fun showNotification(snapshot: DesktopMediaSnapshot) {
        val title = snapshot.title ?: return
        val artist = snapshot.artist ?: ""
        val text = if (artist.isNotBlank()) "$title \u2014 $artist" else title
        SwingUtilities.invokeLater {
            try {
                trayIcon?.displayMessage(
                    "Now Playing",
                    text,
                    TrayIcon.MessageType.INFO,
                )
            } catch (_: Throwable) {
            }
        }
    }

    private fun updateTooltip(snapshot: DesktopMediaSnapshot) {
        val title = snapshot.title ?: ""
        val artist = snapshot.artist ?: ""
        val status = if (snapshot.isPlaying) "\u25B6 Playing" else "\u23F8 Paused"
        val parts = listOfNotNull(
            status,
            title.takeIf { it.isNotBlank() },
            artist.takeIf { it.isNotBlank() },
        )
        trayIcon?.toolTip = parts.joinToString(" \u2014 ").ifBlank { "Metrolist" }
    }

    private fun createTrayImage(): Image {
        val size = 32
        val buf = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = buf.createGraphics()
        g.setRenderingHint(
            RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_ON,
        )
        g.color = Color(0xFF6750A4.toInt())
        g.fillRoundRect(2, 2, size - 4, size - 4, 8, 8)
        g.color = Color.WHITE
        g.font = Font("SansSerif", Font.BOLD, 20)
        val fm = g.fontMetrics
        val letter = "M"
        val x = (size - fm.stringWidth(letter)) / 2
        val y = ((size - fm.height) / 2) + fm.ascent
        g.drawString(letter, x, y)
        g.dispose()
        return buf
    }

    override fun close() {
        SwingUtilities.invokeLater {
            try {
                trayIcon?.let { SystemTray.getSystemTray().remove(it) }
            } catch (_: Throwable) {
            }
            trayIcon = null
        }
    }
}
