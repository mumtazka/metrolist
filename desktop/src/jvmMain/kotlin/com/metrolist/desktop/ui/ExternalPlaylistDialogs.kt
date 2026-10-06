package com.metrolist.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.importer.MatchedTrack
import com.metrolist.desktop.viewmodel.DesktopViewModel

@Composable
fun ExternalPlaylistImportDialog(
    viewModel: DesktopViewModel,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    val playlist = viewModel.externalPlaylist
    val matches = viewModel.externalMatches

    AlertDialog(
        onDismissRequest = { if (!viewModel.externalImportLoading) { viewModel.clearExternalImport(); onDismiss() } },
        title = { Text(if (playlist == null) "Import Spotify playlist" else "Review playlist") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (playlist == null) {
                    OutlinedTextField(url, { url = it }, label = { Text("Playlist URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    if (viewModel.externalImportLoading) {
                        Spacer(Modifier.height(16.dp)); LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    viewModel.externalImportError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                } else {
                    Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                    Text("${matches.size} lagu ditemukan · ${matches.count { it.youtubeId != null }} cocok", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    LaunchedEffect(matches) {
                        selected = matches.indices.filter { matches[it].youtubeId != null }.toSet()
                    }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        itemsIndexed(matches) { index, match ->
                            MatchRow(match, index in selected) { checked ->
                                selected = if (checked) selected + index else selected - index
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (playlist == null) {
                Button(onClick = { viewModel.importSpotifyPlaylist(url) }, enabled = url.isNotBlank() && !viewModel.externalImportLoading) { Text("Read playlist") }
            } else {
                Button(onClick = {
                    viewModel.createLocalPlaylistFromMatches(playlist.name, selected.mapNotNull(matches::getOrNull))
                    viewModel.clearExternalImport(); onDismiss()
                }, enabled = selected.any { matches.getOrNull(it)?.youtubeId != null }) { Text("Import") }
            }
        },
        dismissButton = { TextButton(onClick = { viewModel.clearExternalImport(); onDismiss() }) { Text("Cancel") } },
    )
}

@Composable
private fun MatchRow(match: MatchedTrack, selected: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(selected, onCheckedChange, enabled = match.youtubeId != null)
        Column(Modifier.weight(1f)) {
            Text(match.source.title, maxLines = 1)
            Text(
                when {
                    match.youtubeId == null -> "Tidak ditemukan · ${match.source.artist}"
                    match.ambiguous -> "Perlu ditinjau · ${match.youtubeTitle} — ${match.youtubeArtist}"
                    else -> "${match.youtubeTitle} — ${match.youtubeArtist}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (match.youtubeId == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}
