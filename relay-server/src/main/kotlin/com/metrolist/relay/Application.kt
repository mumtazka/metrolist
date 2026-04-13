/**
 * Metrolist Sync Relay Server
 * A lightweight WebSocket server that pairs devices by Google Account hash
 * and relays playback commands between them.
 *
 * Deploy for free on Render.com, Fly.io, or Koyeb.
 */

package com.metrolist.relay

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// ============================================================
// Data Models
// ============================================================

@Serializable
data class SyncMessage(
    val type: String,
    val payload: String = "",
)

@Serializable
data class RegisterPayload(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("account_hash") val accountHash: String,
)

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

@Serializable
data class RoomInfoPayload(
    val devices: List<DeviceState>,
    @SerialName("active_device_id") val activeDeviceId: String? = null,
)

@Serializable
data class ConflictPayload(
    @SerialName("other_device_id") val otherDeviceId: String,
    @SerialName("other_device_name") val otherDeviceName: String,
    @SerialName("other_song_id") val otherSongId: String?,
    @SerialName("other_song_title") val otherSongTitle: String?,
)

// ============================================================
// Connected Device Session
// ============================================================

data class ConnectedDevice(
    val deviceId: String,
    val deviceName: String,
    val accountHash: String,
    val session: WebSocketSession,
    var state: DeviceState,
)

// ============================================================
// Room Manager
// ============================================================

object RoomManager {
    // accountHash -> list of connected devices
    private val rooms = ConcurrentHashMap<String, MutableList<ConnectedDevice>>()
    private val totalConnections = AtomicInteger(0)
    private const val MAX_CONNECTIONS = 512

    fun tryAddDevice(device: ConnectedDevice): Boolean {
        if (totalConnections.get() >= MAX_CONNECTIONS) return false
        val room = rooms.getOrPut(device.accountHash) { mutableListOf() }
        synchronized(room) {
            room.removeAll { it.deviceId == device.deviceId } // Remove stale sessions
            room.add(device)
        }
        totalConnections.incrementAndGet()
        return true
    }

    // Keep old addDevice for backward compat — calls tryAddDevice ignoring result
    fun addDevice(device: ConnectedDevice) { tryAddDevice(device) }

    fun removeDevice(accountHash: String, deviceId: String) {
        val room = rooms[accountHash] ?: return
        val removed: Boolean
        synchronized(room) {
            removed = room.removeAll { it.deviceId == deviceId }
            if (room.isEmpty()) rooms.remove(accountHash)
        }
        if (removed) totalConnections.decrementAndGet()
    }

    fun getRoom(accountHash: String): List<ConnectedDevice> {
        return rooms[accountHash]?.toList() ?: emptyList()
    }

    fun getOtherDevices(accountHash: String, deviceId: String): List<ConnectedDevice> {
        return getRoom(accountHash).filter { it.deviceId != deviceId }
    }

    fun updateDeviceState(accountHash: String, deviceId: String, state: DeviceState) {
        rooms[accountHash]?.find { it.deviceId == deviceId }?.state = state
    }

    fun getActiveDevice(accountHash: String): ConnectedDevice? {
        return rooms[accountHash]?.firstOrNull { it.state.isPlaying }
    }

    fun getStats(): Map<String, Int> {
        return mapOf(
            "rooms"   to rooms.size,
            "devices" to totalConnections.get(),
        )
    }
}

// ============================================================
// Message Types (must match client SyncProtocol)
// ============================================================

object MessageTypes {
    const val REGISTER = "register"
    const val UNREGISTER = "unregister"
    const val STATE_UPDATE = "state_update"
    const val PLAYBACK_COMMAND = "playback_cmd"
    const val DEVICE_JOINED = "device_joined"
    const val DEVICE_LEFT = "device_left"
    const val REMOTE_STATE = "remote_state"
    const val REMOTE_COMMAND = "remote_cmd"
    const val CONFLICT = "conflict"
    const val ROOM_INFO = "room_info"
}

// ============================================================
// Application Entry Point
// ============================================================

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

private val ADMIN_KEY: String = System.getenv("RELAY_ADMIN_KEY") ?: ""
private val ACCOUNT_HASH_REGEX = Regex("^[0-9a-f]{64}$")
private val DEVICE_ID_REGEX    = Regex("^[A-Za-z0-9\\-_]{1,128}$")

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        install(WebSockets) {
            pingPeriod = Duration.ofSeconds(15)
            timeout = Duration.ofSeconds(30)
            maxFrameSize = 65_536L  // 64 KB — prevents memory-exhaustion attacks
            masking = false
        }
        install(ContentNegotiation) {
            json(json)
        }

        routing {
            // Health check endpoint
            get("/") {
                call.respondText("Metrolist Sync Relay Server is running")
            }

            // Stats endpoint — protected by admin key
            get("/stats") {
                val key = call.request.header("X-Admin-Key") ?: ""
                if (ADMIN_KEY.isNotBlank() && key != ADMIN_KEY) {
                    call.respond(io.ktor.http.HttpStatusCode.Unauthorized, "Unauthorized")
                    return@get
                }
                val stats = RoomManager.getStats()
                call.respondText("Rooms: ${stats["rooms"]}, Devices: ${stats["devices"]}")
            }

            // Main WebSocket sync endpoint
            webSocket("/sync") {
                var currentDevice: ConnectedDevice? = null

                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            // Guard against suspiciously large payloads (belt+suspenders)
                            if (text.length > 65_536) {
                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Payload too large"))
                                break
                            }
                            val message = try {
                                json.decodeFromString(SyncMessage.serializer(), text)
                            } catch (_: Exception) {
                                continue // ignore malformed frames
                            }

                            when (message.type) {
                                // --- REGISTER: Device comes online ---
                                MessageTypes.REGISTER -> {
                                    val payload = try {
                                        json.decodeFromString(RegisterPayload.serializer(), message.payload)
                                    } catch (_: Exception) { continue }

                                    // Validate account_hash (must be SHA-256 hex) and device_id
                                    if (!ACCOUNT_HASH_REGEX.matches(payload.accountHash)) {
                                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid account_hash"))
                                        break
                                    }
                                    if (!DEVICE_ID_REGEX.matches(payload.deviceId)) {
                                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid device_id"))
                                        break
                                    }

                                    val device = ConnectedDevice(
                                        deviceId    = payload.deviceId,
                                        deviceName  = payload.deviceName.take(64), // clamp name length
                                        accountHash = payload.accountHash,
                                        session     = this,
                                        state       = DeviceState(
                                            deviceId    = payload.deviceId,
                                            deviceName  = payload.deviceName.take(64),
                                            accountHash = payload.accountHash,
                                        )
                                    )

                                    if (!RoomManager.tryAddDevice(device)) {
                                        close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Server at capacity"))
                                        break
                                    }
                                    currentDevice = device

                                    println("[REGISTER] ${device.deviceName} (${device.deviceId.take(8)}) joined room ${device.accountHash.take(8)}…")

                                    // Notify other devices in the room
                                    val others = RoomManager.getOtherDevices(
                                        payload.accountHash, payload.deviceId
                                    )
                                    others.forEach { other ->
                                        other.session.sendSyncMessage(
                                            MessageTypes.DEVICE_JOINED,
                                            json.encodeToString(DeviceState.serializer(), device.state)
                                        )
                                    }

                                    // Send room info to the new device
                                    val roomDevices = RoomManager.getRoom(payload.accountHash)
                                        .map { it.state }
                                    val activeDevice = RoomManager.getActiveDevice(payload.accountHash)
                                    sendSyncMessage(
                                        MessageTypes.ROOM_INFO,
                                        json.encodeToString(
                                            RoomInfoPayload.serializer(),
                                            RoomInfoPayload(
                                                devices = roomDevices,
                                                activeDeviceId = activeDevice?.deviceId,
                                            )
                                        )
                                    )
                                }

                                // --- STATE_UPDATE: Device playback state changed ---
                                MessageTypes.STATE_UPDATE -> {
                                    val state = json.decodeFromString(
                                        DeviceState.serializer(), message.payload
                                    )
                                    val dev = currentDevice ?: continue

                                    RoomManager.updateDeviceState(
                                        dev.accountHash, dev.deviceId, state
                                    )

                                    // Check for conflict: is another device also playing?
                                    val otherPlaying = RoomManager.getOtherDevices(
                                        dev.accountHash, dev.deviceId
                                    ).filter { it.state.isPlaying }

                                    if (state.isPlaying && otherPlaying.isNotEmpty()) {
                                        // Conflict detected! Notify both sides
                                        val otherDev = otherPlaying.first()

                                        // Notify the new device about the conflict
                                        send(Frame.Text(
                                            json.encodeToString(SyncMessage.serializer(), SyncMessage(
                                                type = MessageTypes.CONFLICT,
                                                payload = json.encodeToString(
                                                    ConflictPayload.serializer(),
                                                    ConflictPayload(
                                                        otherDeviceId = otherDev.deviceId,
                                                        otherDeviceName = otherDev.deviceName,
                                                        otherSongId = otherDev.state.songId,
                                                        otherSongTitle = otherDev.state.songTitle,
                                                    )
                                                )
                                            ))
                                        ))
                                    } else {
                                        // No conflict: broadcast state to other devices
                                        val others = RoomManager.getOtherDevices(
                                            dev.accountHash, dev.deviceId
                                        )
                                        others.forEach { other ->
                                            other.session.sendSyncMessage(
                                                MessageTypes.REMOTE_STATE, message.payload
                                            )
                                        }
                                    }
                                }

                                // --- PLAYBACK_COMMAND: Remote control action ---
                                MessageTypes.PLAYBACK_COMMAND -> {
                                    val dev = currentDevice ?: continue

                                    // Forward the command to all other devices in the room
                                    val others = RoomManager.getOtherDevices(
                                        dev.accountHash, dev.deviceId
                                    )
                                    others.forEach { other ->
                                        other.session.sendSyncMessage(
                                            MessageTypes.REMOTE_COMMAND, message.payload
                                        )
                                    }

                                    println("[CMD] ${dev.deviceName} sent command to ${others.size} device(s)")
                                }

                                // --- UNREGISTER: Graceful disconnect ---
                                MessageTypes.UNREGISTER -> {
                                    currentDevice?.let { dev ->
                                        handleDeviceDisconnect(dev)
                                    }
                                    currentDevice = null
                                }
                            }
                        }
                    }
                } catch (e: ClosedReceiveChannelException) {
                    // Client disconnected
                } catch (e: Exception) {
                    println("[ERROR] ${e.message}")
                } finally {
                    // Clean up on disconnect
                    currentDevice?.let { dev ->
                        handleDeviceDisconnect(dev)
                    }
                }
            }
        }
    }.start(wait = true)
}

private suspend fun handleDeviceDisconnect(device: ConnectedDevice) {
    RoomManager.removeDevice(device.accountHash, device.deviceId)
    println("[DISCONNECT] ${device.deviceName} (${device.deviceId}) left room ${device.accountHash}")

    // Notify remaining devices
    val others = RoomManager.getRoom(device.accountHash)
    others.forEach { other ->
        other.session.sendSyncMessage(
            MessageTypes.DEVICE_LEFT,
            json.encodeToString(DeviceState.serializer(), device.state)
        )
    }
}

private suspend fun WebSocketSession.sendSyncMessage(type: String, payload: String) {
    try {
        send(Frame.Text(
            json.encodeToString(SyncMessage.serializer(), SyncMessage(type = type, payload = payload))
        ))
    } catch (e: Exception) {
        println("[SEND ERROR] ${e.message}")
    }
}
