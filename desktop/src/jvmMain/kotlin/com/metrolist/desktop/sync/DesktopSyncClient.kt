/**
 * Metrolist Desktop — Relay Sync Client
 *
 * Connects to the Metrolist relay server via secure WebSocket (wss://).
 * Protocol is shared with the Android client:
 *   - REGISTER  on connect (device_id, device_name, account_hash)
 *   - STATE_UPDATE   when playback changes (song, position, playing)
 *   - PLAYBACK_CMD   to remote-control other devices
 *   - REMOTE_CMD     received from other devices → executes local action
 */

package com.metrolist.desktop.sync

import com.metrolist.desktop.player.PlayerState
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

// ─── Protocol models (must match relay server) ────────────────────────────────

@Serializable
data class SyncMessage(
    val type: String,
    val payload: String = "",
)

@Serializable
data class RegisterPayload(
    @SerialName("device_id")   val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("account_hash") val accountHash: String,
)

@Serializable
data class DeviceState(
    @SerialName("device_id")    val deviceId: String,
    @SerialName("device_name")  val deviceName: String,
    @SerialName("account_hash") val accountHash: String,
    @SerialName("song_id")      val songId: String? = null,
    @SerialName("song_title")   val songTitle: String? = null,
    @SerialName("song_artist")  val songArtist: String? = null,
    @SerialName("position_ms")  val positionMs: Long = 0L,
    @SerialName("is_playing")   val isPlaying: Boolean = false,
    @SerialName("timestamp")    val timestamp: Long = System.currentTimeMillis(),
)

@Serializable
data class PlaybackCommand(
    val action: String,          // "play", "pause", "skip_next", "skip_prev", "seek", "play_song"
    val value: String = "",      // song id for play_song, position for seek
)

// ─── Message type constants ───────────────────────────────────────────────────

private object MsgType {
    const val REGISTER          = "register"
    const val STATE_UPDATE      = "state_update"
    const val PLAYBACK_COMMAND  = "playback_cmd"
    const val REMOTE_STATE      = "remote_state"
    const val REMOTE_COMMAND    = "remote_cmd"
    const val DEVICE_JOINED     = "device_joined"
    const val DEVICE_LEFT       = "device_left"
    const val ROOM_INFO         = "room_info"
    const val CONFLICT          = "conflict"
    const val UNREGISTER        = "unregister"
}

// ─── Sync client ─────────────────────────────────────────────────────────────

class DesktopSyncClient(
    private val relayUrl: String,  // e.g. "wss://your-relay.example.com/sync"
    private val playerState: PlayerState,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val deviceId   = UUID.randomUUID().toString()
    private val deviceName = "Desktop — ${System.getProperty("user.name") ?: "PC"}"

    // Derived from accountHash passed on connect; blank = not logged in
    private var accountHash = ""

    private var session: WebSocketSession? = null
    private val isConnected = AtomicBoolean(false)
    private var connectJob: Job? = null
    private var stateJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val client = HttpClient(OkHttp) {
        install(WebSockets)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Connect using the SHA-256 hash of the user's Google account email.
     * Call this once the user is logged in.
     */
    fun connect(accountEmail: String) {
        accountHash = sha256(accountEmail.lowercase().trim())
        println("[Sync] Connecting to $relayUrl as $deviceName (hash=${accountHash.take(8)}…)")
        startConnection()
    }

    fun disconnect() {
        scope.launch {
            session?.let { ws ->
                try {
                    ws.send(Frame.Text(json.encodeToString(SyncMessage(
                        type = MsgType.UNREGISTER,
                        payload = "",
                    ))))
                } catch (_: Exception) {}
                ws.close()
            }
        }
        connectJob?.cancel()
        stateJob?.cancel()
        isConnected.set(false)
        session = null
        println("[Sync] Disconnected")
    }

    /**
     * Send a remote-control command to all paired devices.
     * e.g. sendCommand("pause"), sendCommand("play_song", "dQw4w9WgXcQ")
     */
    fun sendCommand(action: String, value: String = "") {
        if (!isConnected.get()) return
        scope.launch {
            try {
                session?.send(Frame.Text(json.encodeToString(SyncMessage(
                    type = MsgType.PLAYBACK_COMMAND,
                    payload = json.encodeToString(PlaybackCommand(action, value)),
                ))))
            } catch (e: Exception) {
                println("[Sync] Send command error: ${e.message}")
            }
        }
    }

    val connected: Boolean get() = isConnected.get()

    // ── Internal connection loop ──────────────────────────────────────────────

    private fun startConnection() {
        connectJob?.cancel()
        connectJob = scope.launch {
            while (isActive) {
                try {
                    client.webSocket(relayUrl) {
                        session = this
                        isConnected.set(true)
                        println("[Sync] WebSocket connected")

                        // Register this device
                        sendSyncMessage(MsgType.REGISTER, json.encodeToString(RegisterPayload(
                            deviceId   = deviceId,
                            deviceName = deviceName,
                            accountHash = accountHash,
                        )))

                        // Start sending state updates
                        startStateUpdates()

                        // Receive incoming messages
                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    handleMessage(frame.readText())
                                }
                            }
                        } catch (_: ClosedReceiveChannelException) { }
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    println("[Sync] Connection failed: ${e.message}")
                } finally {
                    isConnected.set(false)
                    session = null
                    stateJob?.cancel()
                    println("[Sync] Disconnected — retrying in 10s")
                }
                delay(10_000)
            }
        }
    }

    /** Push playback state to relay every 5 seconds while something is playing. */
    private fun startStateUpdates() {
        stateJob?.cancel()
        stateJob = scope.launch {
            while (isActive) {
                try {
                    val song = playerState.currentSong
                    val state = DeviceState(
                        deviceId    = deviceId,
                        deviceName  = deviceName,
                        accountHash = accountHash,
                        songId      = song?.id,
                        songTitle   = song?.title,
                        songArtist  = song?.artist,
                        positionMs  = playerState.currentPosition,
                        isPlaying   = playerState.isPlaying,
                    )
                    session?.sendSyncMessage(MsgType.STATE_UPDATE, json.encodeToString(state))
                } catch (e: Exception) {
                    println("[Sync] State update error: ${e.message}")
                }
                delay(5_000)
            }
        }
    }

    /** Handle a message from the relay server. */
    private fun handleMessage(text: String) {
        try {
            val msg = json.decodeFromString<SyncMessage>(text)
            when (msg.type) {
                MsgType.REMOTE_COMMAND -> {
                    val cmd = json.decodeFromString<PlaybackCommand>(msg.payload)
                    println("[Sync] Remote command received: ${cmd.action} value=${cmd.value}")
                    executeRemoteCommand(cmd)
                }
                MsgType.REMOTE_STATE -> {
                    // Another device's state — could show in UI if needed
                    println("[Sync] Remote state received")
                }
                MsgType.DEVICE_JOINED -> println("[Sync] A device joined the room")
                MsgType.DEVICE_LEFT   -> println("[Sync] A device left the room")
                MsgType.CONFLICT -> {
                    // Two devices playing simultaneously — we received a conflict notice
                    println("[Sync] Conflict: another device is also playing")
                }
                MsgType.ROOM_INFO -> println("[Sync] Room info received")
            }
        } catch (e: Exception) {
            println("[Sync] Parse error: ${e.message}")
        }
    }

    /** Execute a playback command received from a remote device. */
    private fun executeRemoteCommand(cmd: PlaybackCommand) {
        // Switch to main thread for UI/player updates
        scope.launch(Dispatchers.Main) {
            when (cmd.action) {
                "play"      -> playerState.resume()
                "pause"     -> playerState.pause()
                "skip_next" -> playerState.skipNext()
                "skip_prev" -> playerState.skipPrevious()
                "seek"      -> cmd.value.toLongOrNull()?.let { playerState.seekTo(it) }
                "play_song" -> if (cmd.value.isNotBlank()) playerState.playSongById(cmd.value)
            }
        }
    }

    private suspend fun WebSocketSession.sendSyncMessage(type: String, payload: String) {
        send(Frame.Text(json.encodeToString(SyncMessage(type = type, payload = payload))))
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
