/**
 * Metrolist Desktop — mpv Setup Dialog
 *
 * Shown on first launch (Windows only) when mpv is not found on PATH
 * and has not been auto-downloaded yet.
 *
 * The dialog offers two options:
 *  A) Auto-download a portable mpv build (recommended — no admin, ~30 MB)
 *  B) Open the mpv download page in the system browser for manual install
 */

package com.metrolist.desktop.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

/**
 * Shown when mpv is not found. The user can auto-download or open the browser.
 *
 * @param onDismiss called when the dialog is dismissed (mpv still not available — playback won't work)
 * @param onMpvReady called after a successful download/detection; passes the mpv executable path
 */
@Composable
fun MpvSetupDialog(
    onDismiss: () -> Unit,
    onMpvReady: (mpvPath: String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var downloadState by remember { mutableStateOf(MpvDownloadState.IDLE) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val animatedProgress by animateFloatAsState(targetValue = downloadProgress)

    AlertDialog(
        onDismissRequest = {
            if (downloadState != MpvDownloadState.DOWNLOADING) onDismiss()
        },
        icon = {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                "Audio player required",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (downloadState) {
                    MpvDownloadState.IDLE -> {
                        Text(
                            "Metrolist needs mpv to play music. " +
                            "mpv is a free, open-source media player that runs in the background.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (errorMessage != null) {
                            Text(
                                "Download failed: $errorMessage",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Text(
                            "Click \"Auto-install\" below and mpv will be downloaded automatically (~30 MB, no admin needed).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    MpvDownloadState.DOWNLOADING -> {
                        Text(
                            "Downloading mpv… please wait.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${(animatedProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    MpvDownloadState.DONE -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                "mpv installed successfully! Metrolist is ready to play music.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (downloadState) {
                MpvDownloadState.IDLE -> {
                    Button(
                        onClick = {
                            downloadState = MpvDownloadState.DOWNLOADING
                            errorMessage = null
                            scope.launch {
                                runCatching {
                                    val path = MpvSetupHelper.downloadMpvForWindows { progress ->
                                        downloadProgress = progress
                                    }
                                    path
                                }.onSuccess { path ->
                                    downloadState = MpvDownloadState.DONE
                                    onMpvReady(path)
                                }.onFailure { e ->
                                    downloadState = MpvDownloadState.IDLE
                                    errorMessage = e.message ?: "Unknown error"
                                    println("[MpvSetup] Download failed: ${e.message}")
                                    e.printStackTrace()
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Auto-install mpv")
                    }
                }
                MpvDownloadState.DONE -> {
                    Button(onClick = onDismiss) {
                        Text("Start listening")
                    }
                }
                MpvDownloadState.DOWNLOADING -> {
                    // No confirm button while downloading
                }
            }
        },
        dismissButton = {
            if (downloadState != MpvDownloadState.DOWNLOADING) {
                TextButton(
                    onClick = {
                        // Open the official mpv Windows download page in the system browser
                        runCatching {
                            if (Desktop.isDesktopSupported()) {
                                Desktop.getDesktop().browse(URI("https://mpv.io/installation/"))
                            }
                        }
                        onDismiss()
                    },
                ) {
                    Text(if (downloadState == MpvDownloadState.DONE) "Close" else "Install manually")
                }
            }
        },
    )
}

private enum class MpvDownloadState { IDLE, DOWNLOADING, DONE }
