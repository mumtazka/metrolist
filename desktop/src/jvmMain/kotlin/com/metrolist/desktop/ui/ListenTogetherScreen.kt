package com.metrolist.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.desktop.auth.DesktopPreferences
import com.metrolist.desktop.sync.DesktopListenTogetherClient
import com.metrolist.desktop.sync.ListenTogetherConnectionState
import com.metrolist.desktop.sync.ListenTogetherRole
import com.metrolist.desktop.ui.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ListenTogetherScreen(client: DesktopListenTogetherClient) {
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val config = remember { DesktopPreferences.load() }

    var serverUrl by remember {
        mutableStateOf(config.listenTogetherServerUrl.ifBlank { DesktopListenTogetherClient.DEFAULT_SERVER_URL })
    }
    var username by remember { mutableStateOf(config.listenTogetherUsername) }
    var roomCodeInput by remember { mutableStateOf("") }
    var showAdvancedSettings by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }

    var autoApproval by remember { mutableStateOf(config.listenTogetherAutoApproval) }
    var syncVolume by remember { mutableStateOf(config.listenTogetherSyncVolume) }
    var autoApproveSuggestions by remember { mutableStateOf(config.listenTogetherAutoApproveSuggestions) }

    var codeCopied by remember { mutableStateOf(false) }
    var linkCopied by remember { mutableStateOf(false) }

    val state by client.state.collectAsState()
    val room by client.room.collectAsState()
    val role by client.role.collectAsState()
    val error by client.error.collectAsState()
    val joinRequests by client.joinRequests.collectAsState()
    val suggestions by client.suggestions.collectAsState()
    val blockedUsers by client.blockedUsers.collectAsState()
    val logs by client.logs.collectAsState()
    val hasSavedSession by client.hasSavedSession.collectAsState()

    fun persistConfig() {
        DesktopPreferences.save(
            DesktopPreferences.load().copy(
                listenTogetherServerUrl = serverUrl.trim(),
                listenTogetherUsername = username.trim(),
                listenTogetherAutoApproval = autoApproval,
                listenTogetherSyncVolume = syncVolume,
                listenTogetherAutoApproveSuggestions = autoApproveSuggestions,
            ),
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Header ──
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Headphones,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            "Listen Together",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Synchronize playback and queues with friends in real time.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Server status indicator pill
                ConnectionStatusBadge(state)
            }
        }

        // ── Error Banner ──
        if (error != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                error ?: "",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        IconButton(onClick = client::clearError) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Dismiss error",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }

        // ── Room Setup (When not currently in a room) ──
        if (room == null) {
            // Saved session recovery prompt
            if (hasSavedSession && client.savedRoomCode != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(
                                    "Saved Session Found",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text(
                                    "You were previously connected to Room: ${client.savedRoomCode}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { client.reconnect() },
                                    enabled = state != ListenTogetherConnectionState.CONNECTING,
                                ) {
                                    Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Reconnect")
                                }
                                OutlinedButton(onClick = client::clearSavedSession) {
                                    Text("Dismiss")
                                }
                            }
                        }
                    }
                }
            }

            // User Info & Session Launcher Cards
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Create Session Card
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                "Host a Session",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Create a room and invite others. Your music, seek position, and volume will sync to guests automatically.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = username,
                                onValueChange = {
                                    username = it
                                    persistConfig()
                                },
                                label = { Text("Display Name") },
                                leadingIcon = { Icon(Icons.Rounded.Person, null) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            )
                            Spacer(Modifier.height(4.dp))
                            Button(
                                onClick = {
                                    persistConfig()
                                    client.createRoom(username)
                                },
                                enabled = username.isNotBlank() && state != ListenTogetherConnectionState.CONNECTING,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.Rounded.Headphones, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Create Session")
                            }
                        }
                    }

                    // Join Session Card
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                "Join a Session",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Enter a 6-character room code to join an existing session and listen with your friends.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = roomCodeInput,
                                onValueChange = { input ->
                                    roomCodeInput = input.trim().uppercase().take(6)
                                },
                                label = { Text("Room Code") },
                                placeholder = { Text("e.g. ABCDEF") },
                                leadingIcon = { Icon(Icons.Rounded.Link, null) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            )
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = {
                                    persistConfig()
                                    client.joinRoom(roomCodeInput, username)
                                },
                                enabled = roomCodeInput.isNotBlank() && username.isNotBlank() && state != ListenTogetherConnectionState.CONNECTING,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.Rounded.Link, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Join Session")
                            }
                        }
                    }
                }
            }

            // Advanced Preferences Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    "Server & Automation Settings",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "Manage your connection endpoint and auto-approval preferences.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { showAdvancedSettings = !showAdvancedSettings }) {
                                Icon(
                                    if (showAdvancedSettings) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = "Toggle settings",
                                )
                            }
                        }

                        AnimatedVisibility(visible = showAdvancedSettings) {
                            Column(
                                modifier = Modifier.padding(top = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                OutlinedTextField(
                                    value = serverUrl,
                                    onValueChange = {
                                        serverUrl = it
                                        persistConfig()
                                    },
                                    label = { Text("Server WebSocket URL") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                )

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Button(
                                        onClick = {
                                            persistConfig()
                                            client.connect(serverUrl)
                                        },
                                        enabled = state == ListenTogetherConnectionState.DISCONNECTED || state == ListenTogetherConnectionState.ERROR,
                                    ) {
                                        Text("Connect")
                                    }
                                    OutlinedButton(
                                        onClick = client::disconnect,
                                        enabled = state != ListenTogetherConnectionState.DISCONNECTED,
                                    ) {
                                        Text("Disconnect")
                                    }
                                }

                                Spacer(Modifier.height(4.dp))

                                PreferenceSwitchRow(
                                    title = "Automatically approve join requests",
                                    subtitle = "Allow anyone with the room code to join without manual confirmation",
                                    checked = autoApproval,
                                    onCheckedChange = {
                                        autoApproval = it
                                        persistConfig()
                                    },
                                )
                                PreferenceSwitchRow(
                                    title = "Automatically approve song suggestions",
                                    subtitle = "Add suggested tracks from guests directly into the host queue",
                                    checked = autoApproveSuggestions,
                                    onCheckedChange = {
                                        autoApproveSuggestions = it
                                        persistConfig()
                                    },
                                )
                                PreferenceSwitchRow(
                                    title = "Sync host volume",
                                    subtitle = "Synchronize guest volume when host adjusts their desktop volume",
                                    checked = syncVolume,
                                    onCheckedChange = {
                                        syncVolume = it
                                        persistConfig()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // ── Active Session UI ──
            val currentRoom = room!!

            // Room Info Banner
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    "ROOM CODE",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    currentRoom.code,
                                    style = MaterialTheme.typography.displayMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.primary,
                            ) {
                                Text(
                                    text = if (role == ListenTogetherRole.HOST) "HOST" else "GUEST",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(currentRoom.code))
                                    codeCopied = true
                                    scope.launch {
                                        delay(2000)
                                        codeCopied = false
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(
                                    if (codeCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                    null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (codeCopied) "Copied!" else "Copy Code")
                            }

                            OutlinedButton(
                                onClick = {
                                    val inviteUrl = "https://metrolist.meowery.eu/listen?code=${currentRoom.code}"
                                    clipboardManager.setText(AnnotatedString(inviteUrl))
                                    linkCopied = true
                                    scope.launch {
                                        delay(2000)
                                        linkCopied = false
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(
                                    if (linkCopied) Icons.Rounded.Check else Icons.Rounded.Share,
                                    null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (linkCopied) "Link Copied!" else "Copy Invite Link")
                            }

                            Spacer(Modifier.weight(1f))

                            OutlinedButton(
                                onClick = client::leaveRoom,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) {
                                Icon(Icons.Rounded.Close, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Leave Room")
                            }
                        }
                    }
                }
            }

            // Synchronized Playback Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (!currentRoom.trackThumbnail.isNullOrBlank()) {
                                    AsyncImage(
                                        url = currentRoom.trackThumbnail,
                                        contentDescription = currentRoom.trackTitle,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Icon(
                                        Icons.Rounded.MusicNote,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = currentRoom.trackTitle?.ifBlank { "No song playing" } ?: "No song playing",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                )
                                Text(
                                    text = currentRoom.trackArtist?.ifBlank { "Playback idle" } ?: "Playback idle",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }

                        if (role == ListenTogetherRole.GUEST) {
                            OutlinedButton(
                                onClick = client::suggestCurrentTrack,
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.Rounded.Add, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Suggest Current Track")
                            }
                        }
                    }
                }
            }

            // Pending Join Requests (Host Only)
            if (role == ListenTogetherRole.HOST && joinRequests.isNotEmpty()) {
                item {
                    Text(
                        "Join Requests (${joinRequests.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(joinRequests, key = { it.userId }) { req ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Text(req.username, style = MaterialTheme.typography.titleMedium)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { client.approveJoin(req.userId) },
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text("Approve")
                                }
                                OutlinedButton(
                                    onClick = { client.rejectJoin(req.userId) },
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text("Reject")
                                }
                            }
                        }
                    }
                }
            }

            // Pending Song Suggestions (Host Only)
            if (role == ListenTogetherRole.HOST && suggestions.isNotEmpty()) {
                item {
                    Text(
                        "Song Suggestions (${suggestions.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(suggestions, key = { it.id }) { sug ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    "${sug.track.title} • ${sug.track.artist}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "Suggested by ${sug.fromUsername}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { client.approveSuggestion(sug.id) },
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text("Add to Queue")
                                }
                                OutlinedButton(
                                    onClick = { client.rejectSuggestion(sug.id) },
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text("Reject")
                                }
                            }
                        }
                    }
                }
            }

            // Participants List
            item {
                Text(
                    "Connected Users (${currentRoom.users.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            items(currentRoom.users, key = { it.id }) { user ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (user.isHost) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerHighest
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    user.username.take(1).uppercase(),
                                    fontWeight = FontWeight.Bold,
                                    color = if (user.isHost) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        user.username,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    if (user.isHost) {
                                        Spacer(Modifier.width(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                        ) {
                                            Text(
                                                "Host",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                                Text(
                                    if (user.isConnected) "Connected" else "Offline",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (user.isConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        // Host Moderation actions
                        if (role == ListenTogetherRole.HOST && !user.isHost) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { client.transferHost(user.id) },
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text("Make Host")
                                }
                                OutlinedButton(
                                    onClick = { client.kickUser(user.id) },
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text("Kick")
                                }
                                OutlinedButton(
                                    onClick = {
                                        client.blockUser(user.username)
                                        client.kickUser(user.id, "Blocked by host")
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) {
                                    Text("Block")
                                }
                            }
                        }
                    }
                }
            }

            // Blocked Users (Host Only)
            if (blockedUsers.isNotEmpty()) {
                item {
                    Text(
                        "Blocked Users (${blockedUsers.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(blockedUsers.toList()) { blockedName ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Block, null, tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(12.dp))
                                Text(blockedName, style = MaterialTheme.typography.bodyMedium)
                            }
                            OutlinedButton(
                                onClick = { client.unblockUser(blockedName) },
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text("Unblock")
                            }
                        }
                    }
                }
            }
        }

        // ── Connection Log (Collapsible at bottom) ──
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Live Connection Log",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ) {
                                Text(
                                    "${logs.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (showLogs && logs.isNotEmpty()) {
                                IconButton(onClick = client::clearLogs) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = "Clear logs",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            IconButton(onClick = { showLogs = !showLogs }) {
                                Icon(
                                    if (showLogs) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = "Toggle logs",
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = showLogs) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (logs.isEmpty()) {
                                Text(
                                    "No log entries yet",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                logs.takeLast(30).forEach { line ->
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusBadge(state: ListenTogetherConnectionState) {
    val (label, dotColor, containerColor) = when (state) {
        ListenTogetherConnectionState.CONNECTED -> Triple(
            "Connected",
            Color(0xFF4CAF50),
            Color(0xFF4CAF50).copy(alpha = 0.15f),
        )
        ListenTogetherConnectionState.CONNECTING -> Triple(
            "Connecting...",
            Color(0xFFFFB300),
            Color(0xFFFFB300).copy(alpha = 0.15f),
        )
        ListenTogetherConnectionState.RECONNECTING -> Triple(
            "Reconnecting...",
            Color(0xFFFF9800),
            Color(0xFFFF9800).copy(alpha = 0.15f),
        )
        ListenTogetherConnectionState.ERROR -> Triple(
            "Error",
            Color(0xFFE53935),
            Color(0xFFE53935).copy(alpha = 0.15f),
        )
        ListenTogetherConnectionState.DISCONNECTED -> Triple(
            "Disconnected",
            Color(0xFF9E9E9E),
            Color(0xFF9E9E9E).copy(alpha = 0.15f),
        )
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun PreferenceSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
