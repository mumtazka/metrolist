/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * SyncManager - Core state machine for cross-device synchronization.
 * Connects to the relay server via Ktor WebSocket client and manages
 * device pairing, remote control, and conflict resolution.
 */

package com.metrolist.shared.sync

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import com.metrolist.shared.player.AudioPlayer
import com.metrolist.shared.player.SongInfo

/**
 * The possible states of the sync session.
 */
enum class SyncState {
    /** Not connected to the relay server (offline or disabled). */
    DISCONNECTED,

    /** Connected to the relay server but no device is playing. */
    CONNECTED_IDLE,

    /** This device is the active speaker (master). */
    MASTER,

    /** Another device is playing; this device shows remote control UI. */
    REMOTE_CONTROL,

    /** Both devices were playing (e.g. offline reconnect). Needs user resolution. */
    CONFLICT,
}

/**
 * Information about a remote device in the same sync room.
 */
data class RemoteDevice(
    val deviceId: String,
    val deviceName: String,
    val songId: String? = null,
    val songTitle: String? = null,
    val songArtist: String? = null,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
)

/**
 * Core sync manager that coordinates cross-device playback.
 * Connects to the relay server at [SyncConfig.SERVER_URL].
 */
class SyncManager(
    private val player: AudioPlayer,
    private val deviceId: String,
    private val deviceName: String,
    private val accountHash: String,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Ktor WebSocket client
    private val httpClient = HttpClient {
        install(WebSockets)
    }
    private var webSocketSession: WebSocketSession? = null
    private var connectionJob: Job? = null
    private var positionUpdateJob: Job? = null
    private var reconnectAttempts = 0

    // --- Public State ---

    private val _syncState = MutableStateFlow(SyncState.DISCONNECTED)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _remoteDevices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    val remoteDevices: StateFlow<List<RemoteDevice>> = _remoteDevices.asStateFlow()

    private val _activeRemoteDevice = MutableStateFlow<RemoteDevice?>(null)
    val activeRemoteDevice: StateFlow<RemoteDevice?> = _activeRemoteDevice.asStateFlow()

    private val _conflictDevice = MutableStateFlow<RemoteDevice?>(null)
    val conflictDevice: StateFlow<RemoteDevice?> = _conflictDevice.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // --- Connection Management ---

    /**
     * Connect to the relay server. Call this on app startup when online.
     */
    fun connect() {
        if (connectionJob?.isActive == true) return
        reconnectAttempts = 0

        connectionJob = scope.launch {
            connectWithRetry()
        }
    }

    /**
     * Disconnect from the relay server.
     */
    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        positionUpdateJob?.cancel()
        positionUpdateJob = null

        scope.launch {
            try {
                // Send unregister message before closing
                sendMessage(SyncMessage(type = SyncMessageTypes.UNREGISTER))
                webSocketSession?.close(CloseReason(CloseReason.Codes.NORMAL, "User disconnected"))
            } catch (_: Exception) { }
            webSocketSession = null
            _isConnected.value = false
            _syncState.value = SyncState.DISCONNECTED
            _remoteDevices.value = emptyList()
            _activeRemoteDevice.value = null
        }
    }

    // --- Playback State Broadcasting ---

    /**
     * Called by the local player whenever playback state changes.
     * Broadcasts the update to the relay server.
     */
    fun onLocalPlaybackChanged(song: SongInfo?, isPlaying: Boolean, positionMs: Long) {
        if (!_isConnected.value) return

        scope.launch {
            val state = DeviceState(
                deviceId = deviceId,
                deviceName = deviceName,
                accountHash = accountHash,
                songId = song?.id,
                songTitle = song?.title,
                songArtist = song?.artist,
                positionMs = positionMs,
                isPlaying = isPlaying,
                timestamp = System.currentTimeMillis(),
            )

            if (isPlaying) {
                _syncState.value = SyncState.MASTER
                startPositionUpdates()
            } else {
                stopPositionUpdates()
            }

            sendMessage(SyncMessage(
                type = SyncMessageTypes.STATE_UPDATE,
                payload = json.encodeToString(DeviceState.serializer(), state),
            ))
        }
    }

    // --- Remote Control Commands ---

    fun remotePlay() = sendCommand(PlaybackCommands.PLAY)
    fun remotePause() = sendCommand(PlaybackCommands.PAUSE)
    fun remoteSeek(positionMs: Long) = sendCommand(PlaybackCommands.SEEK, positionMs = positionMs)
    fun remoteSkipNext() = sendCommand(PlaybackCommands.SKIP_NEXT)
    fun remoteSkipPrev() = sendCommand(PlaybackCommands.SKIP_PREV)

    fun transferPlaybackToSelf() {
        sendCommand(PlaybackCommands.TRANSFER_PLAYBACK)
        _syncState.value = SyncState.MASTER
        _activeRemoteDevice.value = null
    }

    fun transferPlaybackTo(targetDeviceId: String) {
        scope.launch {
            sendMessage(SyncMessage(
                type = SyncMessageTypes.PLAYBACK_COMMAND,
                payload = json.encodeToString(
                    PlaybackCommandPayload.serializer(),
                    PlaybackCommandPayload(
                        action = PlaybackCommands.TRANSFER_PLAYBACK,
                        fromDevice = deviceId,
                        songId = player.currentSong.value?.id,
                        positionMs = player.currentPosition.value,
                    )
                )
            ))
            player.pause()
            _syncState.value = SyncState.REMOTE_CONTROL
        }
    }

    // --- Conflict Resolution ---

    fun resolveConflictSyncToRemote() {
        player.pause()
        _syncState.value = SyncState.REMOTE_CONTROL
        _conflictDevice.value = null
    }

    fun resolveConflictKeepIndependent() {
        _syncState.value = SyncState.MASTER
        _conflictDevice.value = null
    }

    fun resolveConflictTakeOver() {
        sendCommand(PlaybackCommands.PAUSE)
        _syncState.value = SyncState.MASTER
        _conflictDevice.value = null
    }

    // ===================================================================
    // Ktor WebSocket Client — Real Network Implementation
    // ===================================================================

    /**
     * Connect with automatic retry on failure.
     */
    private suspend fun connectWithRetry() {
        while (reconnectAttempts < SyncConfig.MAX_RECONNECT_ATTEMPTS) {
            try {
                startWebSocketConnection()
            } catch (e: CancellationException) {
                throw e // Don't catch cancellation
            } catch (e: Exception) {
                println("[SyncManager] Connection failed: ${e.message}")
                _isConnected.value = false
                _syncState.value = SyncState.DISCONNECTED
            }

            reconnectAttempts++
            if (reconnectAttempts < SyncConfig.MAX_RECONNECT_ATTEMPTS) {
                println("[SyncManager] Reconnecting in ${SyncConfig.RECONNECT_DELAY_MS}ms (attempt $reconnectAttempts/${SyncConfig.MAX_RECONNECT_ATTEMPTS})")
                delay(SyncConfig.RECONNECT_DELAY_MS)
            }
        }
        println("[SyncManager] Max reconnect attempts reached, giving up")
    }

    /**
     * Establish WebSocket connection, register, and listen for messages.
     */
    private suspend fun startWebSocketConnection() {
        httpClient.webSocket(SyncConfig.SERVER_URL) {
            webSocketSession = this
            _isConnected.value = true
            _syncState.value = SyncState.CONNECTED_IDLE
            reconnectAttempts = 0 // Reset on successful connect

            println("[SyncManager] Connected to ${SyncConfig.SERVER_URL}")

            // Step 1: Register this device
            val registerPayload = json.encodeToString(
                RegisterPayload.serializer(),
                RegisterPayload(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    accountHash = accountHash,
                )
            )
            send(Frame.Text(
                json.encodeToString(SyncMessage.serializer(), SyncMessage(
                    type = SyncMessageTypes.REGISTER,
                    payload = registerPayload,
                ))
            ))
            println("[SyncManager] Registered as $deviceName ($deviceId)")

            // Step 2: If currently playing, broadcast state immediately
            val currentSong = player.currentSong.value
            if (currentSong != null && player.isPlaying.value) {
                onLocalPlaybackChanged(currentSong, true, player.currentPosition.value)
            }

            // Step 3: Listen for incoming messages
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        try {
                            val message = json.decodeFromString(SyncMessage.serializer(), text)
                            handleIncomingMessage(message)
                        } catch (e: Exception) {
                            println("[SyncManager] Failed to parse message: ${e.message}")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("[SyncManager] WebSocket error: ${e.message}")
            }

            // Connection closed
            webSocketSession = null
            _isConnected.value = false
            println("[SyncManager] Disconnected from server")
        }
    }

    /**
     * Send a message to the relay server via the active WebSocket session.
     */
    private suspend fun sendMessage(message: SyncMessage) {
        try {
            webSocketSession?.send(Frame.Text(
                json.encodeToString(SyncMessage.serializer(), message)
            ))
        } catch (e: Exception) {
            println("[SyncManager] Send failed: ${e.message}")
        }
    }

    /**
     * Periodically broadcasts the current playback position while playing.
     * This keeps remote devices' progress bars in sync.
     */
    private fun startPositionUpdates() {
        stopPositionUpdates()
        positionUpdateJob = scope.launch {
            while (isActive) {
                delay(SyncConfig.POSITION_UPDATE_INTERVAL_MS)
                val song = player.currentSong.value
                if (song != null && player.isPlaying.value) {
                    onLocalPlaybackChanged(song, true, player.currentPosition.value)
                }
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    // --- Internal Message Handling ---

    internal fun handleIncomingMessage(message: SyncMessage) {
        when (message.type) {
            SyncMessageTypes.DEVICE_JOINED -> {
                val state = json.decodeFromString(DeviceState.serializer(), message.payload)
                val device = RemoteDevice(
                    deviceId = state.deviceId,
                    deviceName = state.deviceName,
                    songId = state.songId,
                    songTitle = state.songTitle,
                    songArtist = state.songArtist,
                    positionMs = state.positionMs,
                    isPlaying = state.isPlaying,
                )
                _remoteDevices.value = _remoteDevices.value + device
                println("[SyncManager] Device joined: ${state.deviceName}")
            }

            SyncMessageTypes.DEVICE_LEFT -> {
                val state = json.decodeFromString(DeviceState.serializer(), message.payload)
                _remoteDevices.value = _remoteDevices.value.filter { it.deviceId != state.deviceId }
                if (_activeRemoteDevice.value?.deviceId == state.deviceId) {
                    _activeRemoteDevice.value = null
                    if (_syncState.value == SyncState.REMOTE_CONTROL) {
                        _syncState.value = SyncState.CONNECTED_IDLE
                    }
                }
                println("[SyncManager] Device left: ${state.deviceName}")
            }

            SyncMessageTypes.REMOTE_STATE -> {
                val state = json.decodeFromString(DeviceState.serializer(), message.payload)
                val device = RemoteDevice(
                    deviceId = state.deviceId,
                    deviceName = state.deviceName,
                    songId = state.songId,
                    songTitle = state.songTitle,
                    songArtist = state.songArtist,
                    positionMs = state.positionMs,
                    isPlaying = state.isPlaying,
                )

                _remoteDevices.value = _remoteDevices.value.map {
                    if (it.deviceId == device.deviceId) device else it
                }

                if (state.isPlaying && _syncState.value == SyncState.CONNECTED_IDLE) {
                    _activeRemoteDevice.value = device
                    _syncState.value = SyncState.REMOTE_CONTROL
                }

                if (_syncState.value == SyncState.REMOTE_CONTROL &&
                    _activeRemoteDevice.value?.deviceId == device.deviceId) {
                    _activeRemoteDevice.value = device
                }
            }

            SyncMessageTypes.REMOTE_COMMAND -> {
                val cmd = json.decodeFromString(PlaybackCommandPayload.serializer(), message.payload)
                executeRemoteCommand(cmd)
            }

            SyncMessageTypes.CONFLICT -> {
                val conflict = json.decodeFromString(ConflictPayload.serializer(), message.payload)
                _conflictDevice.value = RemoteDevice(
                    deviceId = conflict.otherDeviceId,
                    deviceName = conflict.otherDeviceName,
                    songId = conflict.otherSongId,
                    songTitle = conflict.otherSongTitle,
                )
                _syncState.value = SyncState.CONFLICT
                println("[SyncManager] CONFLICT with ${conflict.otherDeviceName}")
            }

            SyncMessageTypes.ROOM_INFO -> {
                val info = json.decodeFromString(RoomInfoPayload.serializer(), message.payload)
                _remoteDevices.value = info.devices
                    .filter { it.deviceId != deviceId }
                    .map { state ->
                        RemoteDevice(
                            deviceId = state.deviceId,
                            deviceName = state.deviceName,
                            songId = state.songId,
                            songTitle = state.songTitle,
                            songArtist = state.songArtist,
                            positionMs = state.positionMs,
                            isPlaying = state.isPlaying,
                        )
                    }

                val playingDevice = _remoteDevices.value.firstOrNull { it.isPlaying }
                if (playingDevice != null && !player.isPlaying.value) {
                    _activeRemoteDevice.value = playingDevice
                    _syncState.value = SyncState.REMOTE_CONTROL
                } else if (playingDevice != null && player.isPlaying.value) {
                    _conflictDevice.value = playingDevice
                    _syncState.value = SyncState.CONFLICT
                }

                println("[SyncManager] Room info: ${_remoteDevices.value.size} other device(s)")
            }
        }
    }

    private fun executeRemoteCommand(cmd: PlaybackCommandPayload) {
        when (cmd.action) {
            PlaybackCommands.PLAY -> player.play()
            PlaybackCommands.PAUSE -> player.pause()
            PlaybackCommands.SEEK -> cmd.positionMs?.let { player.seekTo(it) }
            PlaybackCommands.SKIP_NEXT -> player.skipNext()
            PlaybackCommands.SKIP_PREV -> player.skipPrevious()
            PlaybackCommands.SET_VOLUME -> cmd.volume?.let { player.setVolume(it) }
            PlaybackCommands.TRANSFER_PLAYBACK -> {
                cmd.songId?.let {
                    player.play()
                }
                _syncState.value = SyncState.MASTER
                _activeRemoteDevice.value = null
            }
        }
        println("[SyncManager] Executed remote command: ${cmd.action}")
    }

    private fun sendCommand(action: String, positionMs: Long? = null, volume: Float? = null) {
        scope.launch {
            sendMessage(SyncMessage(
                type = SyncMessageTypes.PLAYBACK_COMMAND,
                payload = json.encodeToString(
                    PlaybackCommandPayload.serializer(),
                    PlaybackCommandPayload(
                        action = action,
                        positionMs = positionMs,
                        volume = volume,
                        fromDevice = deviceId,
                    )
                )
            ))
        }
    }

    /**
     * Release all resources.
     */
    fun release() {
        disconnect()
        httpClient.close()
        scope.cancel()
    }
}
