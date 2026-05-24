package com.metrolist.desktop.media

import com.metrolist.desktop.player.PlayerState
import com.metrolist.desktop.player.PlayerState.RepeatMode
import org.freedesktop.dbus.Marshalling
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.connections.IDisconnectCallback
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.interfaces.DBusInterface
import java.util.LinkedHashMap
import java.util.Locale

private const val MprisBusName = "org.mpris.MediaPlayer2.metrolist"
private const val MprisObjectPath = "/org/mpris/MediaPlayer2"

@DBusInterfaceName("org.mpris.MediaPlayer2")
private interface MprisRoot : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
private interface MprisPlayer : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)
}

@DBusInterfaceName("org.freedesktop.DBus.Properties")
@DBusMemberName("PropertiesChanged")
private class PropertiesChangedSignal(
    objectPath: String,
    private val interfaceName: String,
    private val changedProperties: Map<String, Variant<*>>,
    private val invalidatedProperties: List<String>,
) : DBusSignal(objectPath, interfaceName, changedProperties, invalidatedProperties)

class LinuxMprisMediaSession(
    private val playerState: PlayerState,
) : DesktopMediaSession {

    private val connection: DBusConnection?
    private val service: MprisService?
    private var lastSnapshot = playerState.toMediaSnapshot()

    init {
        println("[MPRIS] Initializing MPRIS D-Bus session...")

        val dbusAddress = System.getenv("DBUS_SESSION_BUS_ADDRESS")
        println("[MPRIS] DBUS_SESSION_BUS_ADDRESS = ${dbusAddress ?: "NOT SET"}")

        val xdgRuntime = System.getenv("XDG_RUNTIME_DIR")
        println("[MPRIS] XDG_RUNTIME_DIR = ${xdgRuntime ?: "NOT SET"}")

        val establishedConnection = try {
            DBusConnectionBuilder.forSessionBus().build().also {
                println("[MPRIS] Connected to session bus")
                it.disconnectCallback = object : IDisconnectCallback {
                    override fun clientDisconnect() {
                        println("[MPRIS] D-Bus client disconnect")
                    }

                    override fun requestedDisconnect(exitCode: Int?) {
                        println("[MPRIS] D-Bus requested disconnect: code=$exitCode")
                    }

                    override fun disconnectOnError(error: java.io.IOException) {
                        println("[MPRIS] D-Bus disconnect on error: ${error.message}")
                    }

                    override fun exceptionOnTerminate(error: java.io.IOException) {
                        println("[MPRIS] D-Bus exception on terminate: ${error.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            println("[MPRIS] Session bus connection failed: ${t.message}")
            try {
                // Fallback: try connecting via standard address
                val fallback = System.getenv("DBUS_SESSION_BUS_ADDRESS")
                if (fallback != null) {
                    println("[MPRIS] Retrying with explicit address: $fallback")
                    DBusConnectionBuilder.forAddress(fallback).build().also {
                        println("[MPRIS] Connected via explicit address")
                    }
                } else {
                    null
                }
            } catch (t2: Throwable) {
                println("[MPRIS] Fallback connection also failed: ${t2.message}")
                null
            }
        }

        val establishedService = if (establishedConnection != null) {
            try {
                establishedConnection.requestBusName(MprisBusName)
                println("[MPRIS] Acquired bus name: $MprisBusName")
                val exported = MprisService(playerState, establishedConnection)
                establishedConnection.exportObject(MprisObjectPath, exported)
                println("[MPRIS] Exported object at $MprisObjectPath")
                println("[MPRIS] Metrolist now visible to Quickshell and other MPRIS clients")
                exported
            } catch (t: Throwable) {
                println("[MPRIS] Failed to register: ${t.message}")
                try {
                    establishedConnection.close()
                } catch (_: Throwable) {
                }
                null
            }
        } else {
            null
        }

        connection = if (establishedService != null) establishedConnection else null
        service = establishedService

        if (service != null) {
            service.updateState(lastSnapshot, emitChanges = false)
        }
    }

    override fun update(snapshot: DesktopMediaSnapshot) {
        val currentService = service ?: return
        if (snapshot == lastSnapshot) return
        val previous = lastSnapshot
        lastSnapshot = snapshot
        currentService.updateState(snapshot, emitChanges = true, previous = previous)
    }

    override fun close() {
        try {
            connection?.disconnect()
        } catch (_: Throwable) {
        }
    }

    private class MprisService(
        private val playerState: PlayerState,
        private val connection: DBusConnection,
    ) : DBusInterface, Properties, MprisRoot, MprisPlayer {

        @Volatile private var snapshot: DesktopMediaSnapshot = playerState.toMediaSnapshot()

        override fun getObjectPath(): String = MprisObjectPath
        override fun isRemote(): Boolean = false

        fun updateState(
            newSnapshot: DesktopMediaSnapshot,
            emitChanges: Boolean,
            previous: DesktopMediaSnapshot = snapshot,
        ) {
            snapshot = newSnapshot
            if (!emitChanges) return

            val changed = buildPlayerChangedProperties(previous, newSnapshot)
            if (changed.isNotEmpty()) {
                emitPropertiesChanged("org.mpris.MediaPlayer2.Player", changed)
            }
        }

        override fun Raise() = Unit

        override fun Quit() {
            playerState.stop()
        }

        override fun Next() = playerState.skipNext()

        override fun Previous() = playerState.skipPrevious()

        override fun Pause() = playerState.pause()

        override fun PlayPause() = playerState.togglePlayPause()

        override fun Stop() = playerState.stop()

        override fun Play() = playerState.resume()

        override fun Seek(offset: Long) {
            val nextPositionMs = snapshot.positionMs + (offset / 1000L)
            playerState.seekTo(nextPositionMs.coerceAtLeast(0L))
        }

        override fun SetPosition(trackId: DBusPath, position: Long) {
            val currentTrack = snapshot.songId?.let(::trackObjectPath) ?: return
            if (trackId.toString() != currentTrack) return
            playerState.seekTo((position / 1000L).coerceAtLeast(0L))
        }

        override fun OpenUri(uri: String) {
            val videoId = extractYouTubeVideoId(uri) ?: return
            playerState.playSongById(videoId)
        }

        override fun <A> Get(interface_name: String, property_name: String): A {
            @Suppress("UNCHECKED_CAST")
            return when (interface_name) {
                "org.mpris.MediaPlayer2" -> when (property_name) {
                    "CanQuit" -> false
                    "Fullscreen" -> false
                    "CanSetFullscreen" -> false
                    "CanRaise" -> false
                    "HasTrackList" -> false
                    "Identity" -> "Metrolist"
                    "DesktopEntry" -> "metrolist"
                    "SupportedUriSchemes" -> listOf("http", "https", "file")
                    "SupportedMimeTypes" -> listOf("audio/webm", "audio/ogg", "audio/mpeg", "audio/mp4")
                    else -> null
                }

                "org.mpris.MediaPlayer2.Player" -> when (property_name) {
                    "PlaybackStatus" -> playbackStatus()
                    "LoopStatus" -> loopStatus()
                    "Rate" -> 1.0
                    "Shuffle" -> snapshot.isShuffled
                    "Metadata" -> buildMetadata()
                    "Volume" -> snapshot.volume.toDouble()
                    "Position" -> snapshot.positionMs * 1000L
                    "MinimumRate" -> 1.0
                    "MaximumRate" -> 1.0
                    "CanGoNext" -> snapshot.canGoNext
                    "CanGoPrevious" -> snapshot.canGoPrevious
                    "CanPlay" -> snapshot.songId != null
                    "CanPause" -> snapshot.songId != null
                    "CanSeek" -> snapshot.songId != null
                    "CanControl" -> snapshot.songId != null
                    else -> null
                }

                else -> null
            } as A
        }

        override fun <A> Set(interface_name: String, property_name: String, value: A) {
            when (interface_name) {
                "org.mpris.MediaPlayer2.Player" -> when (property_name) {
                    "Shuffle" -> playerState.isShuffled = value as Boolean
                    "Volume" -> playerState.volume = (value as Number).toFloat().coerceIn(0f, 1f)
                    "LoopStatus" -> setLoopStatus(value as String)
                }
            }
        }

        override fun GetAll(interface_name: String): Map<String, Variant<*>> =
            when (interface_name) {
                "org.mpris.MediaPlayer2" -> linkedMapOf(
                    "CanQuit" to Variant(false),
                    "Fullscreen" to Variant(false),
                    "CanSetFullscreen" to Variant(false),
                    "CanRaise" to Variant(false),
                    "HasTrackList" to Variant(false),
                    "Identity" to Variant("Metrolist"),
                    "DesktopEntry" to Variant("metrolist"),
                    "SupportedUriSchemes" to stringListVariant(listOf("http", "https", "file")),
                    "SupportedMimeTypes" to stringListVariant(listOf("audio/webm", "audio/ogg", "audio/mpeg", "audio/mp4")),
                )

                "org.mpris.MediaPlayer2.Player" -> linkedMapOf(
                    "PlaybackStatus" to Variant(playbackStatus()),
                    "LoopStatus" to Variant(loopStatus()),
                    "Rate" to Variant(1.0),
                    "Shuffle" to Variant(snapshot.isShuffled),
                    "Metadata" to Variant(buildMetadata()),
                    "Volume" to Variant(snapshot.volume.toDouble()),
                    "Position" to Variant(snapshot.positionMs * 1000L),
                    "MinimumRate" to Variant(1.0),
                    "MaximumRate" to Variant(1.0),
                    "CanGoNext" to Variant(snapshot.canGoNext),
                    "CanGoPrevious" to Variant(snapshot.canGoPrevious),
                    "CanPlay" to Variant(snapshot.songId != null),
                    "CanPause" to Variant(snapshot.songId != null),
                    "CanSeek" to Variant(snapshot.songId != null),
                    "CanControl" to Variant(snapshot.songId != null),
                )

                else -> emptyMap()
            }

        private fun playbackStatus(): String = when {
            snapshot.isPlaying -> "Playing"
            snapshot.songId != null -> "Paused"
            else -> "Stopped"
        }

        private fun loopStatus(): String = when (snapshot.repeatMode) {
            RepeatMode.OFF -> "None"
            RepeatMode.ALL -> "Playlist"
            RepeatMode.ONE -> "Track"
        }

        private fun setLoopStatus(value: String) {
            playerState.repeatMode = when (value.lowercase(Locale.US)) {
                "track" -> RepeatMode.ONE
                "playlist" -> RepeatMode.ALL
                else -> RepeatMode.OFF
            }
        }

        private fun buildMetadata(): Map<String, Variant<*>> {
            val title = snapshot.title?.takeIf { it.isNotBlank() } ?: "Metrolist"
            val metadata = LinkedHashMap<String, Variant<*>>()
            metadata["mpris:trackid"] = Variant(DBusPath(trackObjectPath(snapshot.songId)))
            metadata["xesam:title"] = Variant(title)
            metadata["xesam:artist"] = stringListVariant(listOfNotNull(snapshot.artist?.takeIf { it.isNotBlank() }))
            metadata["mpris:length"] = Variant(snapshot.durationMs * 1000L)
            snapshot.albumArt?.takeIf { it.isNotBlank() }?.let { albumArt ->
                metadata["mpris:artUrl"] = Variant(albumArt)
            }
            return metadata
        }

        private fun buildPlayerChangedProperties(
            previous: DesktopMediaSnapshot,
            current: DesktopMediaSnapshot,
        ): Map<String, Variant<*>> {
            val changed = LinkedHashMap<String, Variant<*>>()
            if (previous.isPlaying != current.isPlaying || previous.songId != current.songId) {
                changed["PlaybackStatus"] = Variant(playbackStatus())
            }
            if (previous.songId != current.songId || previous.title != current.title || previous.artist != current.artist || previous.albumArt != current.albumArt || previous.durationMs != current.durationMs) {
                changed["Metadata"] = Variant(buildMetadata())
            }
            if (previous.positionMs != current.positionMs) {
                changed["Position"] = Variant(current.positionMs * 1000L)
            }
            if (previous.volume != current.volume) {
                changed["Volume"] = Variant(current.volume.toDouble())
            }
            if (previous.isShuffled != current.isShuffled) {
                changed["Shuffle"] = Variant(current.isShuffled)
            }
            if (previous.repeatMode != current.repeatMode) {
                changed["LoopStatus"] = Variant(loopStatus())
            }
            if (previous.canGoNext != current.canGoNext) {
                changed["CanGoNext"] = Variant(current.canGoNext)
            }
            if (previous.canGoPrevious != current.canGoPrevious) {
                changed["CanGoPrevious"] = Variant(current.canGoPrevious)
            }
            return changed
        }

        private fun emitPropertiesChanged(interfaceName: String, changed: Map<String, Variant<*>>) {
            try {
                connection.sendMessage(
                    PropertiesChangedSignal(
                        MprisObjectPath,
                        interfaceName,
                        changed,
                        emptyList(),
                    ),
                )
            } catch (t: Throwable) {
                println("[MPRIS] Failed to emit PropertiesChanged: ${t.message}")
            }
        }

        private fun stringListVariant(values: List<String>): Variant<*> =
            Variant(values, Marshalling.convertJavaClassesToSignature(List::class.java, String::class.java))
    }
}

private fun trackObjectPath(songId: String?): String {
    val safeId = songId?.replace(Regex("[^A-Za-z0-9_]"), "_")?.takeIf { it.isNotBlank() } ?: "unknown"
    return "$MprisObjectPath/track/$safeId"
}

private fun extractYouTubeVideoId(uri: String): String? {
    val trimmed = uri.trim()
    if (trimmed.isBlank()) return null

    if (trimmed.length == 11 && trimmed.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
        return trimmed
    }

    val watchMatch = Regex("""[?&]v=([A-Za-z0-9_-]{11})""").find(trimmed)
    if (watchMatch != null) return watchMatch.groupValues[1]

    val shortMatch = Regex("""youtu\.be/([A-Za-z0-9_-]{11})""").find(trimmed)
    if (shortMatch != null) return shortMatch.groupValues[1]

    return null
}
