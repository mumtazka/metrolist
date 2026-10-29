package com.metrolist.desktop.sync

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import com.metrolist.desktop.auth.DesktopPreferences
import com.metrolist.desktop.player.PlayerSong
import com.metrolist.desktop.player.PlayerState
import com.metrolist.music.listentogether.proto.Listentogether
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

enum class ListenTogetherConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }
enum class ListenTogetherRole { NONE, HOST, GUEST }

data class ListenTogetherUser(
    val id: String,
    val username: String,
    val isHost: Boolean,
    val isConnected: Boolean,
)

data class ListenTogetherRoom(
    val code: String,
    val hostId: String,
    val users: List<ListenTogetherUser>,
    val trackId: String? = null,
    val trackTitle: String? = null,
    val trackArtist: String? = null,
    val trackThumbnail: String? = null,
    val isPlaying: Boolean = false,
    val position: Long = 0L,
)

data class ListenTogetherJoinRequest(val userId: String, val username: String)
data class ListenTogetherSuggestion(val id: String, val fromUsername: String, val track: PlayerSong)

sealed class PendingRoomAction {
    data class CreateRoom(val username: String) : PendingRoomAction()
    data class JoinRoom(val roomCode: String, val username: String) : PendingRoomAction()
}

private data class PendingGuestPlayback(
    val action: String,
    val position: Long,
)

/** Desktop implementation of the Listen Together protobuf WebSocket protocol. */
class DesktopListenTogetherClient(private val player: PlayerState) {
    companion object {
        const val DEFAULT_SERVER_URL = "wss://metroserverx.meowery.eu/ws"
        private const val PING_INTERVAL_MS = 25_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connected = AtomicBoolean(false)

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var playbackJob: Job? = null
    private var reconnectAttempts = 0

    private var activeServerUrl: String = DesktopPreferences.load().listenTogetherServerUrl.ifBlank { DEFAULT_SERVER_URL }
    private var storedUsername: String = DesktopPreferences.load().listenTogetherUsername
    private var sessionToken: String? = DesktopPreferences.load().listenTogetherSessionToken.ifBlank { null }
    private var roomCode: String? = DesktopPreferences.load().listenTogetherRoomCode.ifBlank { null }
    private var userId: String? = null

    private var pendingAction: PendingRoomAction? = null
    private var suppressPlaybackEvents = false
    private var lastSentTrack: String? = null
    private var lastSentPlaying: Boolean? = null
    private var lastSentPosition = -1L
    private var lastSentVolume = -1f
    private var bufferingTrackId: String? = null
    private var bufferingJob: Job? = null
    private var pendingGuestPlayback: PendingGuestPlayback? = null

    private val _state = MutableStateFlow(ListenTogetherConnectionState.DISCONNECTED)
    val state: StateFlow<ListenTogetherConnectionState> = _state.asStateFlow()

    private val _role = MutableStateFlow(ListenTogetherRole.NONE)
    val role: StateFlow<ListenTogetherRole> = _role.asStateFlow()

    private val _room = MutableStateFlow<ListenTogetherRoom?>(null)
    val room: StateFlow<ListenTogetherRoom?> = _room.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _joinRequests = MutableStateFlow<List<ListenTogetherJoinRequest>>(emptyList())
    val joinRequests: StateFlow<List<ListenTogetherJoinRequest>> = _joinRequests.asStateFlow()

    private val _suggestions = MutableStateFlow<List<ListenTogetherSuggestion>>(emptyList())
    val suggestions: StateFlow<List<ListenTogetherSuggestion>> = _suggestions.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _blockedUsers = MutableStateFlow(DesktopPreferences.load().listenTogetherBlockedUsers)
    val blockedUsers: StateFlow<Set<String>> = _blockedUsers.asStateFlow()

    private val _hasSavedSession = MutableStateFlow(DesktopPreferences.load().listenTogetherSessionToken.isNotBlank())
    val hasSavedSession: StateFlow<Boolean> = _hasSavedSession.asStateFlow()
    val savedRoomCode: String? get() = roomCode

    val isConnected: Boolean get() = connected.get()

    fun reconnect() {
        val token = sessionToken
        if (token.isNullOrBlank()) {
            _error.value = "No previous session token saved"
            return
        }
        if (isConnected) {
            log("Sending reconnect with saved token")
            send(MessageTypes.RECONNECT, reconnectPayload(token))
        } else {
            log("Connecting to reconnect with saved session...")
            connect(activeServerUrl, autoReconnect = true)
        }
    }

    fun clearSavedSession() {
        sessionToken = null
        roomCode = null
        saveSession()
        log("Saved session cleared")
    }

    fun connect(serverUrl: String = activeServerUrl, autoReconnect: Boolean = false) {
        activeServerUrl = serverUrl.ifBlank { DEFAULT_SERVER_URL }
        if (connected.get() || _state.value == ListenTogetherConnectionState.CONNECTING) {
            log("Already connected or connecting to $activeServerUrl")
            return
        }

        reconnectJob?.cancel()
        _state.value = ListenTogetherConnectionState.CONNECTING
        log("Connecting to $activeServerUrl...")

        val request = Request.Builder()
            .url(activeServerUrl)
            // Keep the handshake identical to Android. The relay uses the
            // WebSocket client identity when deciding who may create rooms.
            // Android leaves User-Agent at OkHttp's default, so desktop must
            // not send a custom desktop-only value here.
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.set(true)
                _state.value = ListenTogetherConnectionState.CONNECTED
                _error.value = null
                reconnectAttempts = 0
                log("Connected to server successfully")

                startPingJob()
                startPlaybackSync()

                // If we had a pending action (create / join), execute it immediately
                val action = pendingAction
                pendingAction = null
                if (action != null) {
                    when (action) {
                        is PendingRoomAction.CreateRoom -> {
                            log("Executing pending create room: ${action.username}")
                            send(MessageTypes.CREATE_ROOM, createRoomPayload(action.username))
                        }
                        is PendingRoomAction.JoinRoom -> {
                            log("Executing pending join room: ${action.roomCode} as ${action.username}")
                            send(MessageTypes.JOIN_ROOM, joinRoomPayload(action.roomCode, action.username))
                        }
                    }
                } else if (autoReconnect && !sessionToken.isNullOrBlank()) {
                    log("Attempting session reconnect with saved token")
                    send(MessageTypes.RECONNECT, reconnectPayload(sessionToken!!))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                handleIncomingBytes(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                log("Server closing connection: code $code, reason: $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                log("Connection closed: code $code")
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val msg = t.message ?: t.javaClass.simpleName
                log("Connection error: $msg")
                handleFailure(msg)
            }
        })
    }

    fun disconnect() {
        log("Disconnecting from server")
        reconnectJob?.cancel()
        pingJob?.cancel()
        playbackJob?.cancel()
        bufferingJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        connected.set(false)
        _state.value = ListenTogetherConnectionState.DISCONNECTED
        _room.value = null
        _role.value = ListenTogetherRole.NONE
        _joinRequests.value = emptyList()
        _suggestions.value = emptyList()
        pendingAction = null
        bufferingTrackId = null
        pendingGuestPlayback = null
        reconnectAttempts = 0
        saveSession()
    }

    fun createRoom(username: String) {
        val trimmed = username.trim()
        storedUsername = trimmed
        sessionToken = null
        roomCode = null
        userId = null
        _error.value = null
        saveSession()

        if (isConnected) {
            log("Sending create_room for $trimmed")
            send(MessageTypes.CREATE_ROOM, createRoomPayload(trimmed))
        } else {
            log("Queueing create_room and connecting...")
            pendingAction = PendingRoomAction.CreateRoom(trimmed)
            connect(activeServerUrl)
        }
    }

    fun joinRoom(code: String, username: String) {
        val trimmedCode = code.trim().uppercase()
        val trimmedUser = username.trim()
        storedUsername = trimmedUser
        sessionToken = null
        roomCode = null
        userId = null
        _error.value = null
        saveSession()

        if (isConnected) {
            log("Sending join_room for $trimmedCode as $trimmedUser")
            send(MessageTypes.JOIN_ROOM, joinRoomPayload(trimmedCode, trimmedUser))
        } else {
            log("Queueing join_room and connecting...")
            pendingAction = PendingRoomAction.JoinRoom(trimmedCode, trimmedUser)
            connect(activeServerUrl)
        }
    }

    fun leaveRoom() {
        log("Leaving room")
        send(MessageTypes.LEAVE_ROOM, null)
        _room.value = null
        _role.value = ListenTogetherRole.NONE
        _joinRequests.value = emptyList()
        _suggestions.value = emptyList()
        sessionToken = null
        roomCode = null
        userId = null
        saveSession()
    }

    fun approveJoin(userId: String) {
        _joinRequests.value = _joinRequests.value.filterNot { it.userId == userId }
        send(MessageTypes.APPROVE_JOIN, approveJoinPayload(userId))
    }

    fun rejectJoin(userId: String, reason: String = "Rejected by host") {
        _joinRequests.value = _joinRequests.value.filterNot { it.userId == userId }
        send(MessageTypes.REJECT_JOIN, rejectJoinPayload(userId, reason))
    }

    fun kickUser(userId: String, reason: String = "Removed by host") =
        send(MessageTypes.KICK_USER, kickUserPayload(userId, reason))

    fun transferHost(userId: String) =
        send(MessageTypes.TRANSFER_HOST, transferHostPayload(userId))

    fun blockUser(username: String) {
        val updated = _blockedUsers.value + username
        _blockedUsers.value = updated
        _joinRequests.value = _joinRequests.value.filterNot { it.username in updated }
        _suggestions.value = _suggestions.value.filterNot { it.fromUsername in updated }
        DesktopPreferences.save(DesktopPreferences.load().copy(listenTogetherBlockedUsers = updated))
        log("Blocked user: $username")
    }

    fun unblockUser(username: String) {
        val updated = _blockedUsers.value - username
        _blockedUsers.value = updated
        DesktopPreferences.save(DesktopPreferences.load().copy(listenTogetherBlockedUsers = updated))
        log("Unblocked user: $username")
    }

    fun suggestCurrentTrack() {
        val song = player.currentSong
        if (song == null) {
            _error.value = "No song currently playing to suggest"
            return
        }
        send(MessageTypes.SUGGEST_TRACK, suggestTrackPayload(song, player.duration))
        log("Suggested track: ${song.title}")
    }

    fun approveSuggestion(id: String) {
        _suggestions.value = _suggestions.value.filterNot { it.id == id }
        send(MessageTypes.APPROVE_SUGGESTION, approveSuggestionPayload(id))
    }

    fun rejectSuggestion(id: String, reason: String = "Rejected by host") {
        _suggestions.value = _suggestions.value.filterNot { it.id == id }
        send(MessageTypes.REJECT_SUGGESTION, rejectSuggestionPayload(id, reason))
    }

    fun clearError() {
        _error.value = null
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    // ─────────────────────────────────────────────────────────────
    // Internal WebSocket & Protocol Handling
    // ─────────────────────────────────────────────────────────────

    private fun handleIncomingBytes(bytes: ByteArray) {
        try {
            val envelope = Listentogether.Envelope.parseFrom(bytes)
            var payload = envelope.payload.toByteArray()
            if (envelope.compressed && payload.isNotEmpty()) {
                payload = GZIPInputStream(ByteArrayInputStream(payload)).use { it.readBytes() }
            }
            log("Received: ${envelope.type}")
            handleMessage(envelope.type, payload)
        } catch (t: Throwable) {
            val msg = "Protocol error: ${t.message ?: t.javaClass.simpleName}"
            _error.value = msg
            log(msg)
        }
    }

    private fun handleMessage(type: String, payload: ByteArray) {
        when (type) {
            MessageTypes.ROOM_CREATED -> {
                val p = Listentogether.RoomCreatedPayload.parseFrom(payload)
                sessionToken = p.sessionToken
                roomCode = p.roomCode
                userId = p.userId
                _role.value = ListenTogetherRole.HOST
                val user = storedUsername.ifBlank { "Host" }
                _room.value = ListenTogetherRoom(
                    code = p.roomCode,
                    hostId = p.userId,
                    users = listOf(ListenTogetherUser(p.userId, user, isHost = true, isConnected = true)),
                    trackId = player.currentSong?.id,
                    trackTitle = player.currentSong?.title,
                    trackArtist = player.currentSong?.artist,
                    trackThumbnail = player.currentSong?.albumArt,
                    isPlaying = player.isPlaying,
                    position = player.currentPosition,
                )
                saveSession()
                log("Room created successfully: ${p.roomCode}")

                // Broadcast current track to newly created room if already playing
                val currentSong = player.currentSong
                if (currentSong != null) {
                    val trackProto = trackToProto(currentSong, player.duration)
                    send(MessageTypes.PLAYBACK_ACTION, playbackActionPayload(
                        action = PlaybackActions.CHANGE_TRACK,
                        trackId = currentSong.id,
                        trackInfo = trackProto,
                    ))
                    lastSentTrack = currentSong.id
                    if (player.isPlaying) {
                        send(MessageTypes.PLAYBACK_ACTION, playbackActionPayload(
                            action = PlaybackActions.PLAY,
                            position = player.currentPosition,
                        ))
                        lastSentPlaying = true
                        lastSentPosition = player.currentPosition
                    }
                }
            }

            MessageTypes.JOIN_APPROVED -> {
                val p = Listentogether.JoinApprovedPayload.parseFrom(payload)
                sessionToken = p.sessionToken
                roomCode = p.roomCode
                userId = p.userId
                _role.value = ListenTogetherRole.GUEST
                updateRoom(p.state)
                saveSession()
                log("Joined room ${p.roomCode} as guest")
                applyRoomStateAsGuest(p.state)
            }

            MessageTypes.JOIN_REQUEST -> {
                val p = Listentogether.JoinRequestPayload.parseFrom(payload)
                log("Join request from: ${p.username} (${p.userId})")
                if (p.username in _blockedUsers.value) {
                    log("Silently rejecting blocked user: ${p.username}")
                    rejectJoin(p.userId, "Blocked")
                    return
                }
                if (DesktopPreferences.load().listenTogetherAutoApproval) {
                    log("Auto-approving join request: ${p.username}")
                    approveJoin(p.userId)
                } else {
                    _joinRequests.value = _joinRequests.value.filterNot { it.userId == p.userId } +
                        ListenTogetherJoinRequest(p.userId, p.username)
                }
            }

            MessageTypes.JOIN_REJECTED -> {
                val p = Listentogether.JoinRejectedPayload.parseFrom(payload)
                val reason = p.reason.ifBlank { "Host rejected the join request" }
                _error.value = reason
                log("Join rejected: $reason")
                _room.value = null
                _role.value = ListenTogetherRole.NONE
            }

            MessageTypes.USER_JOINED -> {
                val p = Listentogether.UserJoinedPayload.parseFrom(payload)
                log("User joined room: ${p.username}")
                val current = _room.value
                if (current != null) {
                    val users = current.users.filterNot { it.id == p.userId } +
                        ListenTogetherUser(p.userId, p.username, isHost = false, isConnected = true)
                    _room.value = current.copy(users = users)
                }
                _joinRequests.value = _joinRequests.value.filterNot { it.userId == p.userId }

                // Host sends current playback state to sync newly joined guest
                if (_role.value == ListenTogetherRole.HOST) {
                    val currentSong = player.currentSong
                    if (currentSong != null) {
                        val trackProto = trackToProto(currentSong, player.duration)
                        send(MessageTypes.PLAYBACK_ACTION, playbackActionPayload(
                            action = PlaybackActions.CHANGE_TRACK,
                            trackId = currentSong.id,
                            trackInfo = trackProto,
                        ))
                        if (player.isPlaying) {
                            send(MessageTypes.PLAYBACK_ACTION, playbackActionPayload(
                                action = PlaybackActions.PLAY,
                                position = player.currentPosition,
                            ))
                        }
                    }
                }
            }

            MessageTypes.USER_LEFT -> {
                val p = Listentogether.UserLeftPayload.parseFrom(payload)
                log("User left room: ${p.username}")
                val current = _room.value
                if (current != null) {
                    _room.value = current.copy(users = current.users.filterNot { it.id == p.userId })
                }
                _joinRequests.value = _joinRequests.value.filterNot { it.userId == p.userId }
            }

            MessageTypes.USER_RECONNECTED -> {
                val p = Listentogether.UserReconnectedPayload.parseFrom(payload)
                val current = _room.value
                if (current != null) {
                    val users = current.users.map { if (it.id == p.userId) it.copy(isConnected = true) else it }
                    _room.value = current.copy(users = users)
                }
            }

            MessageTypes.USER_DISCONNECTED -> {
                val p = Listentogether.UserDisconnectedPayload.parseFrom(payload)
                val current = _room.value
                if (current != null) {
                    val users = current.users.map { if (it.id == p.userId) it.copy(isConnected = false) else it }
                    _room.value = current.copy(users = users)
                }
            }

            MessageTypes.SYNC_PLAYBACK -> {
                val p = Listentogether.PlaybackActionPayload.parseFrom(payload)
                // HOST NEVER echoes playback actions onto its own player to prevent feedback loops!
                if (_role.value != ListenTogetherRole.HOST) {
                    applyPlaybackAsGuest(p)
                }
            }

            MessageTypes.SYNC_STATE -> {
                val p = Listentogether.SyncStatePayload.parseFrom(payload)
                if (_role.value != ListenTogetherRole.HOST) {
                    applySyncStateAsGuest(p)
                }
            }

            MessageTypes.BUFFER_WAIT -> {
                val p = Listentogether.BufferWaitPayload.parseFrom(payload)
                log("Waiting for buffering: ${p.waitingForList.size} users for track ${p.trackId}")
            }

            MessageTypes.BUFFER_COMPLETE -> {
                val p = Listentogether.BufferCompletePayload.parseFrom(payload)
                log("Buffering complete for track: ${p.trackId}")
                if (bufferingTrackId == p.trackId) {
                    bufferingTrackId = null
                    applyPendingGuestPlayback()
                }
            }

            MessageTypes.HOST_CHANGED -> {
                val p = Listentogether.HostChangedPayload.parseFrom(payload)
                log("Host transferred to: ${p.newHostName} (${p.newHostId})")
                val current = _room.value
                if (current != null) {
                    val users = current.users.map { it.copy(isHost = it.id == p.newHostId) }
                    _room.value = current.copy(hostId = p.newHostId, users = users)
                }
                _role.value = if (userId == p.newHostId) ListenTogetherRole.HOST else ListenTogetherRole.GUEST
            }

            MessageTypes.KICKED -> {
                val p = Listentogether.KickedPayload.parseFrom(payload)
                val reason = p.reason.ifBlank { "You were removed from the room by the host" }
                log("Kicked from room: $reason")
                _error.value = reason
                _room.value = null
                _role.value = ListenTogetherRole.NONE
                sessionToken = null
                roomCode = null
                saveSession()
            }

            MessageTypes.SUGGESTION_RECEIVED -> {
                val p = Listentogether.SuggestionReceivedPayload.parseFrom(payload)
                if (p.fromUsername in _blockedUsers.value) {
                    log("Ignored suggestion from blocked user: ${p.fromUsername}")
                    return
                }
                if (p.hasTrackInfo() && p.trackInfo.id.isNotEmpty()) {
                    val t = p.trackInfo
                    val track = PlayerSong(
                        id = t.id,
                        title = t.title,
                        artist = t.artist,
                        albumArt = t.thumbnail.takeIf { it.isNotBlank() },
                        durationMs = t.duration,
                    )
                    if (DesktopPreferences.load().listenTogetherAutoApproveSuggestions && _role.value == ListenTogetherRole.HOST) {
                        log("Auto-approving suggestion from ${p.fromUsername}: ${track.title}")
                        approveSuggestion(p.suggestionId)
                    } else {
                        _suggestions.value = _suggestions.value.filterNot { it.id == p.suggestionId } +
                            ListenTogetherSuggestion(p.suggestionId, p.fromUsername, track)
                    }
                }
            }

            MessageTypes.SUGGESTION_APPROVED -> {
                val p = Listentogether.SuggestionApprovedPayload.parseFrom(payload)
                _suggestions.value = _suggestions.value.filterNot { it.id == p.suggestionId }
                if (p.hasTrackInfo() && p.trackInfo.id.isNotEmpty()) {
                    val t = p.trackInfo
                    val track = PlayerSong(
                        id = t.id,
                        title = t.title,
                        artist = t.artist,
                        albumArt = t.thumbnail.takeIf { it.isNotBlank() },
                        durationMs = t.duration,
                    )
                    player.addToQueue(track)
                    log("Approved song added to queue: ${track.title}")
                }
            }

            MessageTypes.SUGGESTION_REJECTED -> {
                val p = Listentogether.SuggestionRejectedPayload.parseFrom(payload)
                _suggestions.value = _suggestions.value.filterNot { it.id == p.suggestionId }
                log("Suggestion rejected: ${p.reason}")
            }

            MessageTypes.RECONNECTED -> {
                val p = Listentogether.ReconnectedPayload.parseFrom(payload)
                roomCode = p.roomCode
                userId = p.userId
                _role.value = if (p.isHost) ListenTogetherRole.HOST else ListenTogetherRole.GUEST
                updateRoom(p.state)
                log("Reconnected to room ${p.roomCode}")
                if (!p.isHost) {
                    applyRoomStateAsGuest(p.state)
                }
            }

            MessageTypes.ERROR -> {
                val p = Listentogether.ErrorPayload.parseFrom(payload)
                log("Server error: [${p.code}] ${p.message}")
                if (p.code == "session_not_found") {
                    sessionToken = null
                    roomCode = null
                    saveSession()
                }
                _error.value = p.message.ifBlank { "Server error: ${p.code}" }
            }

            MessageTypes.PONG -> {
                // Heartbeat response from server
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Guest Playback Synchronizers
    // ─────────────────────────────────────────────────────────────

    private fun applyPlaybackAsGuest(p: Listentogether.PlaybackActionPayload) {
        suppressPlaybackEvents = true
        try {
            when (p.action) {
                PlaybackActions.CHANGE_TRACK -> {
                    if (p.hasTrackInfo() && p.trackInfo.id.isNotEmpty()) {
                        val t = p.trackInfo
                        val song = PlayerSong(
                            id = t.id,
                            title = t.title,
                            artist = t.artist,
                            albumArt = t.thumbnail.takeIf { it.isNotBlank() },
                            durationMs = t.duration,
                        )
                        log("Guest applying CHANGE_TRACK: ${song.title}")
                        pendingGuestPlayback = null
                        startGuestTrack(song)
                    }
                }
                PlaybackActions.PLAY -> {
                    val pos = adjustedPosition(p)
                    if (bufferingTrackId != null) {
                        pendingGuestPlayback = PendingGuestPlayback(PlaybackActions.PLAY, pos)
                        return
                    }
                    val diff = kotlin.math.abs(player.currentPosition - pos)
                    // Only seek if distance is large (>3s) to prevent choppy playback
                    if (diff > 3000L || !player.isPlaying) {
                        player.seekTo(pos)
                    }
                    player.resume()
                }
                PlaybackActions.PAUSE -> {
                    val pos = p.position
                    if (bufferingTrackId != null) {
                        pendingGuestPlayback = PendingGuestPlayback(PlaybackActions.PAUSE, pos)
                        return
                    }
                    val diff = kotlin.math.abs(player.currentPosition - pos)
                    if (diff > 2000L) {
                        player.seekTo(pos)
                    }
                    player.pause()
                }
                PlaybackActions.SEEK -> {
                    if (bufferingTrackId != null) {
                        pendingGuestPlayback = PendingGuestPlayback(PlaybackActions.SEEK, p.position)
                        return
                    }
                    player.seekTo(p.position)
                }
                PlaybackActions.SKIP_NEXT -> {
                    player.skipNext()
                }
                PlaybackActions.SKIP_PREV -> {
                    player.skipPrevious()
                }
                PlaybackActions.SET_VOLUME -> {
                    if (DesktopPreferences.load().listenTogetherSyncVolume) {
                        player.volume = p.volume.coerceIn(0f, 1f)
                    }
                }
            }
        } finally {
            scope.launch {
                delay(300)
                suppressPlaybackEvents = false
            }
        }
    }

    private fun adjustedPosition(p: Listentogether.PlaybackActionPayload): Long {
        if (p.serverTime <= 0L) return p.position
        val elapsed = (System.currentTimeMillis() - p.serverTime).coerceAtLeast(0L)
        return p.position + elapsed
    }

    private fun startGuestTrack(song: PlayerSong) {
        bufferingJob?.cancel()
        bufferingTrackId = song.id
        player.playSong(song)
        bufferingJob = scope.launch {
            var waited = 0
            while (isActive && bufferingTrackId == song.id && player.isLoadingStream && waited < 150) {
                delay(200)
                waited++
            }
            if (!isActive || bufferingTrackId != song.id) return@launch

            if (player.isLoadingStream) {
                log("Timed out waiting for guest track to buffer: ${song.id}")
                return@launch
            }

            player.pause()
            send(MessageTypes.BUFFER_READY, bufferReadyPayload(song.id))
            log("Guest buffer ready for track: ${song.id}")
        }
    }

    private fun applyPendingGuestPlayback() {
        val pending = pendingGuestPlayback ?: return
        pendingGuestPlayback = null
        suppressPlaybackEvents = true
        try {
            player.seekTo(pending.position)
            when (pending.action) {
                PlaybackActions.PLAY -> player.resume()
                PlaybackActions.PAUSE -> player.pause()
            }
        } finally {
            scope.launch {
                delay(300)
                suppressPlaybackEvents = false
            }
        }
    }

    private fun applyRoomStateAsGuest(state: Listentogether.RoomState) {
        if (state.hasCurrentTrack() && state.currentTrack.id.isNotEmpty()) {
            val t = state.currentTrack
            val song = PlayerSong(
                id = t.id,
                title = t.title,
                artist = t.artist,
                albumArt = t.thumbnail.takeIf { it.isNotBlank() },
                durationMs = t.duration,
            )
            if (player.currentSong?.id != song.id) {
                suppressPlaybackEvents = true
                pendingGuestPlayback = PendingGuestPlayback(
                    action = if (state.isPlaying) PlaybackActions.PLAY else PlaybackActions.PAUSE,
                    position = state.position,
                )
                startGuestTrack(song)
            }
            scope.launch {
                var waited = 0
                while (player.isLoadingStream && waited < 20) {
                    delay(200)
                    waited++
                }
                if (bufferingTrackId != song.id) {
                    if (state.position > 0) {
                        player.seekTo(state.position)
                    }
                    if (state.isPlaying) {
                        player.resume()
                    } else {
                        player.pause()
                    }
                }
                delay(500)
                suppressPlaybackEvents = false
            }
        }
        if (state.volume > 0f && DesktopPreferences.load().listenTogetherSyncVolume) {
            player.volume = state.volume.coerceIn(0f, 1f)
        }
    }

    private fun applySyncStateAsGuest(state: Listentogether.SyncStatePayload) {
        if (state.hasCurrentTrack() && state.currentTrack.id.isNotEmpty()) {
            val t = state.currentTrack
            val song = PlayerSong(
                id = t.id,
                title = t.title,
                artist = t.artist,
                albumArt = t.thumbnail.takeIf { it.isNotBlank() },
                durationMs = t.duration,
            )
            if (player.currentSong?.id != song.id) {
                pendingGuestPlayback = PendingGuestPlayback(
                    action = if (state.isPlaying) PlaybackActions.PLAY else PlaybackActions.PAUSE,
                    position = state.position,
                )
                startGuestTrack(song)
                return
            }
        }
        if (state.isPlaying) {
            player.resume()
        } else {
            player.pause()
        }
        player.seekTo(state.position)
    }

    private fun updateRoom(state: Listentogether.RoomState) {
        val track = if (state.hasCurrentTrack() && state.currentTrack.id.isNotEmpty()) state.currentTrack else null
        _room.value = ListenTogetherRoom(
            code = state.roomCode,
            hostId = state.hostId,
            users = state.usersList.map { ListenTogetherUser(it.userId, it.username, it.isHost, it.isConnected) },
            trackId = track?.id,
            trackTitle = track?.title,
            trackArtist = track?.artist,
            trackThumbnail = track?.thumbnail?.takeIf { it.isNotBlank() },
            isPlaying = state.isPlaying,
            position = state.position,
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Host Playback Observer Loop
    // ─────────────────────────────────────────────────────────────

    private fun startPlaybackSync() {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            while (isActive) {
                if (_role.value == ListenTogetherRole.HOST && !suppressPlaybackEvents && isConnected) {
                    val song = player.currentSong
                    if (song != null) {
                        val trackChanged = song.id != lastSentTrack
                        val playChanged = player.isPlaying != lastSentPlaying
                        val seeked = lastSentPosition >= 0 && kotlin.math.abs(player.currentPosition - lastSentPosition) > 2500L

                        if (trackChanged) {
                            val trackProto = trackToProto(song, player.duration)
                            send(MessageTypes.PLAYBACK_ACTION, playbackActionPayload(
                                action = PlaybackActions.CHANGE_TRACK,
                                trackId = song.id,
                                trackInfo = trackProto,
                            ))
                            lastSentTrack = song.id
                            lastSentPlaying = false
                            lastSentPosition = player.currentPosition
                        }

                        if (playChanged || (trackChanged && player.isPlaying)) {
                            send(MessageTypes.PLAYBACK_ACTION, playbackActionPayload(
                                action = if (player.isPlaying) PlaybackActions.PLAY else PlaybackActions.PAUSE,
                                position = player.currentPosition,
                            ))
                            lastSentPlaying = player.isPlaying
                            lastSentPosition = player.currentPosition
                        } else if (seeked && !trackChanged) {
                            send(MessageTypes.PLAYBACK_ACTION, playbackActionPayload(
                                action = PlaybackActions.SEEK,
                                position = player.currentPosition,
                            ))
                            lastSentPosition = player.currentPosition
                        }
                    } else if (lastSentTrack != null) {
                        lastSentTrack = null
                        lastSentPlaying = null
                        lastSentPosition = -1L
                    }

                    if (DesktopPreferences.load().listenTogetherSyncVolume &&
                        kotlin.math.abs(player.volume - lastSentVolume) > 0.02f
                    ) {
                        send(MessageTypes.PLAYBACK_ACTION, playbackActionPayload(
                            action = PlaybackActions.SET_VOLUME,
                            volume = player.volume,
                        ))
                        lastSentVolume = player.volume
                    }
                }
                delay(500)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Heartbeat & Connection Helpers
    // ─────────────────────────────────────────────────────────────

    private fun startPingJob() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                if (isConnected) {
                    send(MessageTypes.PING, null)
                }
            }
        }
    }

    private fun handleDisconnect() {
        connected.set(false)
        pingJob?.cancel()
        playbackJob?.cancel()
        webSocket = null
        _state.value = ListenTogetherConnectionState.DISCONNECTED
    }

    private fun handleFailure(errorMessage: String) {
        connected.set(false)
        pingJob?.cancel()
        playbackJob?.cancel()
        webSocket = null

        val shouldReconnect = (!sessionToken.isNullOrBlank() || _room.value != null || pendingAction != null)
        if (shouldReconnect && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            _state.value = ListenTogetherConnectionState.RECONNECTING
            val delayMs = (INITIAL_RECONNECT_DELAY_MS * (1 shl minOf(reconnectAttempts - 1, 4)))
                .coerceAtMost(MAX_RECONNECT_DELAY_MS)
            log("Reconnecting in ${delayMs / 1000}s (attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)...")

            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(delayMs)
                connect(activeServerUrl, autoReconnect = true)
            }
        } else {
            _state.value = ListenTogetherConnectionState.ERROR
            _error.value = "Connection error: $errorMessage"
        }
    }

    private fun send(type: String, payload: MessageLite?) {
        val ws = webSocket
        if (ws == null || !connected.get()) {
            log("Cannot send $type: not connected")
            return
        }
        try {
            val bytes = encodeEnvelope(type, payload)
            val success = ws.send(okio.ByteString.of(*bytes))
            if (!success) {
                log("Failed to enqueue $type")
            }
        } catch (t: Throwable) {
            log("Send error for $type: ${t.message}")
        }
    }

    private fun saveSession() {
        _hasSavedSession.value = !sessionToken.isNullOrBlank()
        DesktopPreferences.save(
            DesktopPreferences.load().copy(
                listenTogetherSessionToken = sessionToken ?: "",
                listenTogetherRoomCode = roomCode ?: "",
                listenTogetherUsername = storedUsername,
            ),
        )
    }

    private fun log(message: String) {
        val time = LocalTime.now().withNano(0)
        _logs.value = (_logs.value + "$time  $message").takeLast(200)
    }

    // ─────────────────────────────────────────────────────────────
    // Envelope & Payload Builders
    // ─────────────────────────────────────────────────────────────

    private fun encodeEnvelope(type: String, payload: MessageLite?): ByteArray {
        var payloadBytes = payload?.toByteArray() ?: ByteArray(0)
        var compressed = false
        if (payloadBytes.size > 100) {
            val baos = ByteArrayOutputStream()
            GZIPOutputStream(baos).use { it.write(payloadBytes) }
            val compressedBytes = baos.toByteArray()
            if (compressedBytes.size < payloadBytes.size) {
                payloadBytes = compressedBytes
                compressed = true
            }
        }
        return Listentogether.Envelope.newBuilder()
            .setType(type)
            .setPayload(ByteString.copyFrom(payloadBytes))
            .setCompressed(compressed)
            .build()
            .toByteArray()
    }

    private fun createRoomPayload(username: String) =
        Listentogether.CreateRoomPayload.newBuilder().setUsername(username).build()

    private fun joinRoomPayload(code: String, username: String) =
        Listentogether.JoinRoomPayload.newBuilder().setRoomCode(code).setUsername(username).build()

    private fun approveJoinPayload(userId: String) =
        Listentogether.ApproveJoinPayload.newBuilder().setUserId(userId).build()

    private fun rejectJoinPayload(userId: String, reason: String) =
        Listentogether.RejectJoinPayload.newBuilder().setUserId(userId).setReason(reason).build()

    private fun kickUserPayload(userId: String, reason: String) =
        Listentogether.KickUserPayload.newBuilder().setUserId(userId).setReason(reason).build()

    private fun transferHostPayload(newHostId: String) =
        Listentogether.TransferHostPayload.newBuilder().setNewHostId(newHostId).build()

    private fun bufferReadyPayload(trackId: String) =
        Listentogether.BufferReadyPayload.newBuilder().setTrackId(trackId).build()

    private fun reconnectPayload(token: String) =
        Listentogether.ReconnectPayload.newBuilder().setSessionToken(token).build()

    private fun suggestTrackPayload(song: PlayerSong, durationMs: Long) =
        Listentogether.SuggestTrackPayload.newBuilder().setTrackInfo(trackToProto(song, durationMs)).build()

    private fun approveSuggestionPayload(id: String) =
        Listentogether.ApproveSuggestionPayload.newBuilder().setSuggestionId(id).build()

    private fun rejectSuggestionPayload(id: String, reason: String) =
        Listentogether.RejectSuggestionPayload.newBuilder().setSuggestionId(id).setReason(reason).build()

    private fun trackToProto(song: PlayerSong, durationMs: Long = 0L) =
        Listentogether.TrackInfo.newBuilder()
            .setId(song.id)
            .setTitle(song.title)
            .setArtist(song.artist)
            .setThumbnail(song.albumArt ?: "")
            .setDuration(if (durationMs > 0) durationMs else if (song.durationMs > 0) song.durationMs else 180000L)
            .build()

    private fun playbackActionPayload(
        action: String,
        trackId: String? = null,
        position: Long = 0L,
        volume: Float = 1f,
        trackInfo: Listentogether.TrackInfo? = null,
    ) = Listentogether.PlaybackActionPayload.newBuilder()
        .setAction(action)
        .setTrackId(trackId ?: "")
        .setPosition(position)
        .setVolume(volume)
        .setServerTime(System.currentTimeMillis())
        .apply { trackInfo?.let(::setTrackInfo) }
        .build()
}

object MessageTypes {
    const val CREATE_ROOM = "create_room"
    const val JOIN_ROOM = "join_room"
    const val LEAVE_ROOM = "leave_room"
    const val JOIN_REQUEST = "join_request"
    const val APPROVE_JOIN = "approve_join"
    const val REJECT_JOIN = "reject_join"
    const val KICK_USER = "kick_user"
    const val TRANSFER_HOST = "transfer_host"
    const val SUGGEST_TRACK = "suggest_track"
    const val APPROVE_SUGGESTION = "approve_suggestion"
    const val REJECT_SUGGESTION = "reject_suggestion"
    const val PLAYBACK_ACTION = "playback_action"
    const val BUFFER_READY = "buffer_ready"
    const val ROOM_CREATED = "room_created"
    const val JOIN_APPROVED = "join_approved"
    const val JOIN_REJECTED = "join_rejected"
    const val RECONNECT = "reconnect"
    const val RECONNECTED = "reconnected"
    const val SYNC_PLAYBACK = "sync_playback"
    const val SYNC_STATE = "sync_state"
    const val BUFFER_WAIT = "buffer_wait"
    const val BUFFER_COMPLETE = "buffer_complete"
    const val USER_JOINED = "user_joined"
    const val USER_LEFT = "user_left"
    const val USER_RECONNECTED = "user_reconnected"
    const val USER_DISCONNECTED = "user_disconnected"
    const val HOST_CHANGED = "host_changed"
    const val ERROR = "error"
    const val KICKED = "kicked"
    const val SUGGESTION_RECEIVED = "suggestion_received"
    const val SUGGESTION_APPROVED = "suggestion_approved"
    const val SUGGESTION_REJECTED = "suggestion_rejected"
    const val PING = "ping"
    const val PONG = "pong"
}

object PlaybackActions {
    const val PLAY = "play"
    const val PAUSE = "pause"
    const val SEEK = "seek"
    const val SKIP_NEXT = "skip_next"
    const val SKIP_PREV = "skip_prev"
    const val CHANGE_TRACK = "change_track"
    const val SET_VOLUME = "set_volume"
}
