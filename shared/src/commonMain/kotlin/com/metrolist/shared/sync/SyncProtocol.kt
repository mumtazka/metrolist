/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * SyncProtocol - Message types for the remote sync relay server.
 * Both client and server share these data classes.
 */

package com.metrolist.shared.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The state of a connected device as reported to the relay server.
 */
@Serializable
data class DeviceState(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("account_hash") val accountHash: String,
    @SerialName("song_id") val songId: String? = null,
    @SerialName("song_title") val songTitle: String? = null,
    @SerialName("song_artist") val songArtist: String? = null,
    @SerialName("position_ms") val positionMs: Long = 0L,
    @SerialName("is_playing") val isPlaying: Boolean = false,
    @SerialName("timestamp") val timestamp: Long = 0L,
)

/**
 * Payload sent when a device registers with the relay server.
 */
@Serializable
data class RegisterPayload(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("account_hash") val accountHash: String,
)

/**
 * All possible sync message types between client ↔ relay server.
 */
object SyncMessageTypes {
    // Client → Server
    const val REGISTER = "register"            // Device comes online
    const val UNREGISTER = "unregister"         // Device goes offline
    const val STATE_UPDATE = "state_update"     // Playback state changed
    const val PLAYBACK_COMMAND = "playback_cmd" // Remote control action

    // Server → Client
    const val DEVICE_JOINED = "device_joined"   // Another device with same account connected
    const val DEVICE_LEFT = "device_left"       // Another device disconnected
    const val REMOTE_STATE = "remote_state"     // Another device's playback state
    const val REMOTE_COMMAND = "remote_cmd"     // Incoming remote control command
    const val CONFLICT = "conflict"             // Two devices both playing after reconnect
    const val ROOM_INFO = "room_info"           // Full room state on connect
}

/**
 * Playback commands that can be sent between devices.
 */
object PlaybackCommands {
    const val PLAY = "play"
    const val PAUSE = "pause"
    const val SEEK = "seek"
    const val SKIP_NEXT = "skip_next"
    const val SKIP_PREV = "skip_prev"
    const val CHANGE_TRACK = "change_track"
    const val SET_VOLUME = "set_volume"
    const val TRANSFER_PLAYBACK = "transfer_playback"
}

/**
 * Envelope for all WebSocket messages.
 */
@Serializable
data class SyncMessage(
    val type: String,
    val payload: String = "", // JSON-encoded payload (varies by type)
)

/**
 * A playback command sent from one device to control another.
 */
@Serializable
data class PlaybackCommandPayload(
    val action: String,
    @SerialName("song_id") val songId: String? = null,
    @SerialName("position_ms") val positionMs: Long? = null,
    val volume: Float? = null,
    @SerialName("from_device") val fromDevice: String = "",
)

/**
 * Conflict detection payload sent by server when two devices are both playing.
 */
@Serializable
data class ConflictPayload(
    @SerialName("other_device_id") val otherDeviceId: String,
    @SerialName("other_device_name") val otherDeviceName: String,
    @SerialName("other_song_id") val otherSongId: String?,
    @SerialName("other_song_title") val otherSongTitle: String?,
)

/**
 * Room info payload sent by server on initial connect.
 */
@Serializable
data class RoomInfoPayload(
    val devices: List<DeviceState>,
    @SerialName("active_device_id") val activeDeviceId: String? = null,
)
