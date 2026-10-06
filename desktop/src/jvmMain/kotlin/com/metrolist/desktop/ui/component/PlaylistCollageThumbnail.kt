/**
 * Metrolist Desktop — Spotify-style 4-cover collage playlist thumbnail
 */

package com.metrolist.desktop.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.data.LocalPlaylist
import com.metrolist.desktop.ui.AsyncImage

/**
 * Extracts up to 4 cover URLs for building a 2x2 Spotify-style collage.
 * If 4 or more distinct covers are available, uses 4 covers.
 * If at least 4 songs have covers with >= 2 distinct, uses the first 4.
 * Otherwise falls back to 1 cover or an empty list.
 */
fun extractPlaylistCollageCovers(covers: List<String?>): List<String> {
    val valid = covers.filterNotNull().filter { it.isNotBlank() }
    val distinct = valid.distinct()
    return when {
        distinct.size >= 4 -> distinct.take(4)
        valid.size >= 4 && distinct.size >= 2 -> valid.take(4)
        distinct.isNotEmpty() -> distinct.take(1)
        else -> emptyList()
    }
}

/**
 * Renders a playlist thumbnail.
 * If 4 covers are provided, renders a 2x2 grid collage like Spotify.
 * If 1 cover is provided, renders a full single cover.
 * If empty, renders a themed gradient with a playlist icon.
 */
@Composable
fun PlaylistCollageThumbnail(
    covers: List<String>,
    modifier: Modifier = Modifier.size(48.dp),
    shape: Shape = RoundedCornerShape(8.dp),
    placeholderIcon: ImageVector = Icons.AutoMirrored.Rounded.QueueMusic,
    iconSize: Dp = 22.dp,
    contentDescription: String? = null,
) {
    val collageCovers = remember(covers) {
        extractPlaylistCollageCovers(covers)
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        when {
            collageCovers.size >= 4 -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        AsyncImage(
                            url = collageCovers[0],
                            contentDescription = null,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentScale = ContentScale.Crop,
                        )
                        AsyncImage(
                            url = collageCovers[1],
                            contentDescription = null,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        AsyncImage(
                            url = collageCovers[2],
                            contentDescription = null,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentScale = ContentScale.Crop,
                        )
                        AsyncImage(
                            url = collageCovers[3],
                            contentDescription = null,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
            collageCovers.isNotEmpty() -> {
                AsyncImage(
                    url = collageCovers.first(),
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    placeholder = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                placeholderIcon,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(iconSize),
                            )
                        }
                    },
                )
            }
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        placeholderIcon,
                        null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
        }
    }
}

/**
 * Overload specifically for [LocalPlaylist].
 */
@Composable
fun PlaylistCollageThumbnail(
    playlist: LocalPlaylist,
    modifier: Modifier = Modifier.size(48.dp),
    shape: Shape = RoundedCornerShape(8.dp),
    placeholderIcon: ImageVector = Icons.AutoMirrored.Rounded.QueueMusic,
    iconSize: Dp = 22.dp,
) {
    val covers = remember(playlist.songs) {
        playlist.songs.mapNotNull { it.albumArt }
    }
    PlaylistCollageThumbnail(
        covers = covers,
        modifier = modifier,
        shape = shape,
        placeholderIcon = placeholderIcon,
        iconSize = iconSize,
        contentDescription = playlist.name,
    )
}
