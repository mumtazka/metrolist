/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Sync UI Components - Compose Multiplatform UI for the remote sync feature.
 */

package com.metrolist.shared.sync.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.shared.sync.RemoteDevice
import com.metrolist.shared.sync.SyncManager
import com.metrolist.shared.sync.SyncState

// ============================================================
// SyncBanner — "Playing on [Device Name]"
// Shown at the bottom of the player when in REMOTE_CONTROL mode
// ============================================================

@Composable
fun SyncBanner(
    syncState: SyncState,
    activeDevice: RemoteDevice?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = syncState == SyncState.REMOTE_CONTROL && activeDevice != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        activeDevice?.let { device ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onTap),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                tonalElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Pulsing green dot
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Playing on ${device.deviceName}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        device.songTitle?.let { title ->
                            Text(
                                text = "${title}${device.songArtist?.let { " • $it" } ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Text(
                        text = "🎧",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        }
    }
}

// ============================================================
// ConflictDialog — Shown when two devices are both playing
// (e.g. after offline reconnect)
// ============================================================

@Composable
fun ConflictDialog(
    syncState: SyncState,
    conflictDevice: RemoteDevice?,
    onSyncToRemote: () -> Unit,
    onTakeOver: () -> Unit,
    onKeepIndependent: () -> Unit,
) {
    if (syncState == SyncState.CONFLICT && conflictDevice != null) {
        AlertDialog(
            onDismissRequest = onKeepIndependent,
            title = {
                Text("Playback Conflict")
            },
            text = {
                Column {
                    Text(
                        "Music is also playing on ${conflictDevice.deviceName}."
                    )
                    conflictDevice.songTitle?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Playing: $it",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("What would you like to do?")
                }
            },
            confirmButton = {
                TextButton(onClick = onSyncToRemote) {
                    Text("Listen on ${conflictDevice.deviceName}")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onTakeOver) {
                        Text("Play Here")
                    }
                    TextButton(onClick = onKeepIndependent) {
                        Text("Both")
                    }
                }
            },
        )
    }
}

// ============================================================
// DeviceSwitcher — Shows connected devices and allows
// transferring playback (like Spotify's device picker)
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSwitcher(
    isVisible: Boolean,
    currentDeviceName: String,
    remoteDevices: List<RemoteDevice>,
    syncState: SyncState,
    onTransferToDevice: (String) -> Unit,
    onTransferToSelf: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (isVisible) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "Select a device",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                // This device
                DeviceItem(
                    deviceName = "$currentDeviceName (This device)",
                    isActive = syncState == SyncState.MASTER,
                    songInfo = null,
                    onClick = onTransferToSelf,
                )

                // Remote devices
                remoteDevices.forEach { device ->
                    DeviceItem(
                        deviceName = device.deviceName,
                        isActive = syncState == SyncState.REMOTE_CONTROL &&
                                device.isPlaying,
                        songInfo = device.songTitle,
                        onClick = { onTransferToDevice(device.deviceId) },
                    )
                }

                if (remoteDevices.isEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No other devices found.\nMake sure you're logged in with the same Google account.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceItem(
    deviceName: String,
    isActive: Boolean,
    songInfo: String?,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isActive) "🔊" else "🔇",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                )
                if (isActive && songInfo != null) {
                    Text(
                        text = songInfo,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}
