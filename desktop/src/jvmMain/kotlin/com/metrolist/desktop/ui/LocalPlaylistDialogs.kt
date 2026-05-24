package com.metrolist.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.player.PlayerSong
import com.metrolist.desktop.viewmodel.DesktopViewModel

@Composable
fun AddToPlaylistButton(
    song: PlayerSong,
    viewModel: DesktopViewModel,
    iconOnly: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }

    if (iconOnly) {
        IconButton(
            onClick = { showDialog = true },
            modifier = modifier,
        ) {
            Icon(Icons.Rounded.AddCircleOutline, "Add to playlist")
        }
    } else {
        OutlinedButton(
            onClick = { showDialog = true },
            modifier = modifier,
        ) {
            Icon(Icons.Rounded.AddCircleOutline, null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Playlist")
        }
    }

    if (showDialog) {
        AddToPlaylistDialog(
            song = song,
            viewModel = viewModel,
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
fun AddToPlaylistDialog(
    song: PlayerSong,
    viewModel: DesktopViewModel,
    onDismiss: () -> Unit,
) {
    var newPlaylistName by remember { mutableStateOf("") }
    val playlists = viewModel.localPlaylists
    val creationTime = remember { System.currentTimeMillis() }

    AlertDialog(
        onDismissRequest = {
            if (System.currentTimeMillis() - creationTime > 200) {
                onDismiss()
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, null)
                Text("Add to playlist")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                if (playlists.isEmpty()) {
                    Text(
                        "No playlists yet. Create one below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    playlists.forEach { playlist ->
                        val alreadyAdded = playlist.songs.any { it.id == song.id }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    playlist.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "${playlist.songs.size} song${if (playlist.songs.size == 1) "" else "s"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    viewModel.addSongToLocalPlaylist(playlist.id, song)
                                    onDismiss()
                                },
                                enabled = !alreadyAdded,
                            ) {
                                Icon(
                                    if (alreadyAdded) Icons.Rounded.Check else Icons.Rounded.Add,
                                    null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.size(4.dp))
                                Text(if (alreadyAdded) "Added" else "Add")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Text(
                    "Create new playlist",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Playlist name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val playlist = viewModel.createLocalPlaylist(newPlaylistName)
                        if (playlist != null) {
                            viewModel.addSongToLocalPlaylist(playlist.id, song)
                            onDismiss()
                        }
                    },
                    enabled = newPlaylistName.isNotBlank(),
                ) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Create and add")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
fun LocalPlaylistNameDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialValue) { mutableStateOf(initialValue) }
    val creationTime = remember { System.currentTimeMillis() }

    AlertDialog(
        onDismissRequest = {
            if (System.currentTimeMillis() - creationTime > 200) {
                onDismiss()
            }
        },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Playlist name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
