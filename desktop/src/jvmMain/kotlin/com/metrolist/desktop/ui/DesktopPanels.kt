/**
 * Metrolist Desktop — UI Panels
 * LeftSidebarPanel, TopBar, NowPlayingPanel
 * Material 3 Expressive styling inspired by Metrolist Mobile
 */

package com.metrolist.desktop.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.metrolist.desktop.data.AppUpdater
import com.metrolist.desktop.data.LocalPlaylist
import com.metrolist.desktop.player.PlayerSong
import com.metrolist.desktop.player.PlayerState
import androidx.compose.ui.layout.ContentScale
import com.metrolist.desktop.ui.component.PlayingIndicator
import com.metrolist.desktop.ui.component.PlaylistCollageThumbnail
import com.metrolist.desktop.viewmodel.DesktopViewModel
import com.metrolist.innertube.models.PlaylistItem
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────
// LEFT SIDEBAR PANEL
// ─────────────────────────────────────────────────────────────

enum class NavScreen(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    SEARCH("Explore", Icons.Rounded.Explore),
    LIBRARY("Library", Icons.Rounded.LibraryMusic),
    PLAYLIST("Playlist", Icons.AutoMirrored.Rounded.QueueMusic),
    LIKED("Liked", Icons.Rounded.Favorite),
    DOWNLOADS("Downloads", Icons.Rounded.Download),
    CACHE("Cached", Icons.Rounded.CloudDownload),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LeftSidebarPanel(
    currentScreen: NavScreen,
    onNavigate: (NavScreen) -> Unit,
    viewModel: DesktopViewModel,
    selectedPlaylistId: String?,
    selectedLocalPlaylistId: String?,
    onPlaylistSelected: (PlaylistItem) -> Unit,
    onLocalPlaylistSelected: (LocalPlaylist) -> Unit,
    onCreatePlaylist: () -> Unit,
    onImportPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playlists = viewModel.userPlaylists
    val localPlaylists = viewModel.localPlaylists
    val scrollState = rememberLazyListState()

    val currentOrder = viewModel.sidebarPlaylistOrder
    val orderedPlaylistItems = remember(localPlaylists, currentOrder) {
        buildOrderedSidebarItems(localPlaylists, currentOrder)
    }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    val itemHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }

    Surface(
        modifier = modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Logo Header ──
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary,
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.MusicNote,
                        contentDescription = "Logo",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Metrolist",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // ── Main Navigation ──
            SidebarNavItem(
                label = NavScreen.HOME.label,
                icon = NavScreen.HOME.icon,
                selected = currentScreen == NavScreen.HOME,
                onClick = { onNavigate(NavScreen.HOME) },
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            )
            Spacer(Modifier.height(6.dp))

            // ── Section: Playlists Header ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "PLAYLISTS",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onCreatePlaylist,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        "Create playlist",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onImportPlaylist,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Input,
                        "Import playlist",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            LazyColumn(
                state = scrollState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                // ── Reorderable Playlist Items (Liked, Custom Playlists, Downloads, Cached) ──
                itemsIndexed(orderedPlaylistItems, key = { _, item -> item.key }) { index, item ->
                    val isDragging = draggingIndex == index
                    val targetOffset = when {
                        draggingIndex == null -> 0f
                        index == draggingIndex -> dragOffsetY
                        else -> {
                            val draggedTo = (draggingIndex!! + dragOffsetY / itemHeightPx).roundToInt()
                                .coerceIn(0, orderedPlaylistItems.size - 1)
                            when {
                                draggingIndex!! < index && index <= draggedTo -> -itemHeightPx
                                draggingIndex!! > index && index >= draggedTo -> itemHeightPx
                                else -> 0f
                            }
                        }
                    }

                    SidebarReorderablePlaylistItem(
                        item = item,
                        currentScreen = currentScreen,
                        selectedLocalPlaylistId = selectedLocalPlaylistId,
                        cacheCount = com.metrolist.desktop.data.StreamCache.entries.size,
                        isDragging = isDragging,
                        offsetY = targetOffset,
                        onDragStart = {
                            draggingIndex = index
                            dragOffsetY = 0f
                        },
                        onDrag = { dy -> dragOffsetY += dy },
                        onDragEnd = {
                            val to = (index + dragOffsetY / itemHeightPx).roundToInt()
                                .coerceIn(0, orderedPlaylistItems.size - 1)
                            if (index != to) {
                                val reordered = orderedPlaylistItems.toMutableList()
                                val moved = reordered.removeAt(index)
                                reordered.add(to, moved)
                                viewModel.updateSidebarPlaylistOrder(reordered.map { it.key })
                            }
                            draggingIndex = null
                            dragOffsetY = 0f
                        },
                        onNavigate = onNavigate,
                        onLocalPlaylistSelected = onLocalPlaylistSelected,
                    )
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "YouTube Playlists",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!viewModel.isLoggedIn) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        ) {
                            Text(
                                "Sign in to see your YouTube playlists",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                    }
                } else if (playlists.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        ) {
                            Text(
                                "No YouTube playlists yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                } else {
                    items(playlists) { playlist ->
                        PlaylistSidebarItem(
                            playlist = playlist,
                            selected = currentScreen == NavScreen.PLAYLIST && selectedPlaylistId == playlist.id,
                            onClick = { onPlaylistSelected(playlist) },
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            )

            // ── Settings ──
            SidebarNavItem(
                label = "Settings",
                icon = Icons.Rounded.Settings,
                selected = currentScreen == NavScreen.SETTINGS,
                onClick = { onNavigate(NavScreen.SETTINGS) },
                showBadge = AppUpdater.updateAvailable,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SidebarNavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    tintOverride: Color? = null,
    showBadge: Boolean = false,
    badgeText: String? = null,
) {
    var hovered by remember { mutableStateOf(false) }
    val animatedBg by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            hovered  -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
            else     -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 200),
    )
    val contentColor = when {
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else     -> tintOverride ?: MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit)  { hovered = false },
        shape = RoundedCornerShape(24.dp), // M3 Expressive stadium pill
        color = animatedBg,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
                Icon(icon, label, tint = contentColor, modifier = Modifier.size(20.dp))
                if (showBadge) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .offset(x = 3.dp, y = (-3).dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (badgeText != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        badgeText,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlaylistSidebarItem(
    playlist: PlaylistItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val animatedBg by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            hovered -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 180),
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit)  { hovered = false },
        shape = RoundedCornerShape(10.dp),
        color = animatedBg,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 48dp Thumbnail
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (playlist.thumbnail != null) {
                    AsyncImage(
                        url = playlist.thumbnail,
                        contentDescription = playlist.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = {
                            Box(
                                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.QueueMusic, null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        },
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Rounded.QueueMusic, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    playlist.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                val sub = buildString {
                    append("Playlist")
                    playlist.author?.name?.let { append(" • $it") }
                }
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// SIDEBAR PLAYLIST REORDERABLE ITEMS & THEME THUMBNAILS
// ─────────────────────────────────────────────────────────────

sealed class SidebarPlaylistItem(val key: String) {
    data object Liked : SidebarPlaylistItem("builtin:liked")
    data class Local(val playlist: LocalPlaylist) : SidebarPlaylistItem("local:${playlist.id}")
    data object Downloads : SidebarPlaylistItem("builtin:downloads")
    data object Cached : SidebarPlaylistItem("builtin:cached")
}

private fun buildOrderedSidebarItems(
    localPlaylists: List<LocalPlaylist>,
    savedOrder: List<String>,
): List<SidebarPlaylistItem> {
    val localMap = localPlaylists.associateBy { "local:${it.id}" }

    // Default order if savedOrder is empty:
    // 1. Liked songs on top (default)
    // 2. Custom playlist(s)
    // 3. Downloaded
    // 4. Cached
    if (savedOrder.isEmpty()) {
        val defaultList = mutableListOf<SidebarPlaylistItem>()
        defaultList.add(SidebarPlaylistItem.Liked)
        localPlaylists.forEach { defaultList.add(SidebarPlaylistItem.Local(it)) }
        defaultList.add(SidebarPlaylistItem.Downloads)
        defaultList.add(SidebarPlaylistItem.Cached)
        return defaultList
    }

    val ordered = mutableListOf<SidebarPlaylistItem>()
    val seen = mutableSetOf<String>()

    for (key in savedOrder) {
        when {
            key == "builtin:liked" -> {
                ordered.add(SidebarPlaylistItem.Liked)
                seen.add(key)
            }
            key == "builtin:downloads" -> {
                ordered.add(SidebarPlaylistItem.Downloads)
                seen.add(key)
            }
            key == "builtin:cached" -> {
                ordered.add(SidebarPlaylistItem.Cached)
                seen.add(key)
            }
            key.startsWith("local:") -> {
                val pl = localMap[key]
                if (pl != null) {
                    ordered.add(SidebarPlaylistItem.Local(pl))
                    seen.add(key)
                }
            }
        }
    }

    if ("builtin:liked" !in seen) {
        ordered.add(0, SidebarPlaylistItem.Liked)
        seen.add("builtin:liked")
    }

    localPlaylists.forEach { pl ->
        val key = "local:${pl.id}"
        if (key !in seen) {
            val insertIdx = ordered.indexOfFirst {
                it is SidebarPlaylistItem.Downloads || it is SidebarPlaylistItem.Cached
            }
            if (insertIdx >= 0) {
                ordered.add(insertIdx, SidebarPlaylistItem.Local(pl))
            } else {
                ordered.add(SidebarPlaylistItem.Local(pl))
            }
            seen.add(key)
        }
    }

    if ("builtin:downloads" !in seen) {
        ordered.add(SidebarPlaylistItem.Downloads)
        seen.add("builtin:downloads")
    }

    if ("builtin:cached" !in seen) {
        ordered.add(SidebarPlaylistItem.Cached)
        seen.add("builtin:cached")
    }

    return ordered
}

@Composable
fun LikedThumbnail(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    )
                )
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                shape = RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Favorite,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
fun DownloadsThumbnail(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    )
                )
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Download,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
fun CachedThumbnail(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                    )
                )
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Cached,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(22.dp),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SidebarReorderablePlaylistItem(
    item: SidebarPlaylistItem,
    currentScreen: NavScreen,
    selectedLocalPlaylistId: String?,
    cacheCount: Int,
    isDragging: Boolean,
    offsetY: Float,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onNavigate: (NavScreen) -> Unit,
    onLocalPlaylistSelected: (LocalPlaylist) -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }

    val isSelected = when (item) {
        is SidebarPlaylistItem.Liked -> currentScreen == NavScreen.LIKED
        is SidebarPlaylistItem.Downloads -> currentScreen == NavScreen.DOWNLOADS
        is SidebarPlaylistItem.Cached -> currentScreen == NavScreen.CACHE
        is SidebarPlaylistItem.Local -> currentScreen == NavScreen.PLAYLIST && selectedLocalPlaylistId == item.playlist.id
    }

    val animatedBg by animateColorAsState(
        targetValue = when {
            isSelected || isDragging -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            hovered -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 180),
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .offset { IntOffset(0, offsetY.roundToInt()) }
            .zIndex(if (isDragging) 2f else 0f)
            .shadow(if (isDragging) 8.dp else 0.dp, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false },
        shape = RoundedCornerShape(10.dp),
        color = animatedBg,
        onClick = {
            when (item) {
                is SidebarPlaylistItem.Liked -> onNavigate(NavScreen.LIKED)
                is SidebarPlaylistItem.Downloads -> onNavigate(NavScreen.DOWNLOADS)
                is SidebarPlaylistItem.Cached -> onNavigate(NavScreen.CACHE)
                is SidebarPlaylistItem.Local -> onLocalPlaylistSelected(item.playlist)
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail (48dp)
            when (item) {
                is SidebarPlaylistItem.Liked -> {
                    LikedThumbnail(Modifier.size(48.dp))
                }
                is SidebarPlaylistItem.Downloads -> {
                    DownloadsThumbnail(Modifier.size(48.dp))
                }
                is SidebarPlaylistItem.Cached -> {
                    CachedThumbnail(Modifier.size(48.dp))
                }
                is SidebarPlaylistItem.Local -> {
                    PlaylistCollageThumbnail(
                        playlist = item.playlist,
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(8.dp),
                        iconSize = 22.dp,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // Title and subtitle
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                val title = when (item) {
                    is SidebarPlaylistItem.Liked -> "Liked Songs"
                    is SidebarPlaylistItem.Downloads -> "Downloads"
                    is SidebarPlaylistItem.Cached -> "Cached"
                    is SidebarPlaylistItem.Local -> item.playlist.name
                }
                val subtitle = when (item) {
                    is SidebarPlaylistItem.Liked -> "Playlist • Auto"
                    is SidebarPlaylistItem.Downloads -> "Playlist • Offline"
                    is SidebarPlaylistItem.Cached -> if (cacheCount > 0) "Playlist • $cacheCount song${if (cacheCount == 1) "" else "s"}" else "Playlist • 0 songs"
                    is SidebarPlaylistItem.Local -> {
                        val count = item.playlist.songs.size
                        "Playlist • $count song${if (count == 1) "" else "s"}"
                    }
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Optional badge for Cached
            if (item is SidebarPlaylistItem.Cached && cacheCount > 0) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        "$cacheCount",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(4.dp))
            }

            // Drag handle
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .pointerInput(item.key) {
                        detectVerticalDragGestures(
                            onDragStart = { onDragStart() },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount)
                            }
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.DragHandle,
                    contentDescription = "Reorder playlist",
                    tint = if (isDragging) MaterialTheme.colorScheme.primary
                           else if (hovered) MaterialTheme.colorScheme.onSurfaceVariant
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// TOP BAR
// ─────────────────────────────────────────────────────────────

@Composable
fun TopBar(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onSearchFocused: () -> Unit,
    viewModel: DesktopViewModel,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Search field — pill shaped, centered, M3 Expressive
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    onQueryChange(it)
                    if (it.isNotBlank()) onSearchFocused()
                },
                placeholder = {
                    Text(
                        "Search songs, artists, albums...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSearchFocused() },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor   = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.75f),
                    focusedBorderColor      = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor    = Color.Transparent,
                    cursorColor             = MaterialTheme.colorScheme.primary,
                ),
                shape = RoundedCornerShape(28.dp),
                singleLine = true,
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Search, "Search",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                },
                trailingIcon = {
                    AnimatedVisibility(searchQuery.isNotBlank()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Rounded.Close, "Clear", modifier = Modifier.size(18.dp))
                        }
                    }
                },
            )

            Spacer(Modifier.width(16.dp))

            // Account badge
            if (viewModel.isLoggedIn) {
                val initials = viewModel.accountName
                    ?.split(" ")?.mapNotNull { it.firstOrNull()?.uppercaseChar() }
                    ?.take(2)?.joinToString("") ?: "?"
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        initials,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 13.sp,
                    )
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Login, "Sign in",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Sign In",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
    }
}

// ─────────────────────────────────────────────────────────────
// NOW PLAYING / RIGHT PANEL
// ─────────────────────────────────────────────────────────────

@Composable
fun NowPlayingPanel(
    playerState: PlayerState,
    viewModel: DesktopViewModel,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        AnimatedContent(
            targetState = playerState.showQueue,
            transitionSpec = {
                if (targetState) {
                    (slideInHorizontally { it } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it } + fadeOut())
                } else {
                    (slideInHorizontally { -it } + fadeIn()) togetherWith
                    (slideOutHorizontally { it } + fadeOut())
                }
            },
            label = "RightPanelMode",
        ) { showQueue ->
            if (showQueue) {
                QueuePanel(playerState = playerState)
            } else {
                NowPlayingContent(playerState = playerState, viewModel = viewModel)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// NOW PLAYING CONTENT (the info view inside NowPlayingPanel)
// ─────────────────────────────────────────────────────────────

@Composable
private fun NowPlayingContent(
    playerState: PlayerState,
    viewModel: DesktopViewModel,
) {
    val song = playerState.currentSong ?: return
    val artist = viewModel.artistPage
    val queue = playerState.queue
    val queueIndex = playerState.queueIndex

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))

        // ── Album art with subtle ambient shadow ──
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(12.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (song.albumArt != null) {
                AsyncImage(
                    url = song.albumArt,
                    contentDescription = song.title,
                    modifier = Modifier.fillMaxSize(),
                    placeholder = {
                        Box(
                            Modifier.fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primaryContainer,
                                            MaterialTheme.colorScheme.secondaryContainer,
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.MusicNote, null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(48.dp),
                            )
                        }
                    },
                )
            } else {
                Box(
                    Modifier.fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.secondaryContainer,
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.MusicNote, null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Song title + artist ──
        Text(
            song.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            song.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        Spacer(Modifier.height(16.dp))

        // ── Artist info ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "ABOUT THE ARTIST",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            )
        }
        Spacer(Modifier.height(12.dp))

        if (viewModel.artistLoading) {
            Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        } else if (artist != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                ),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Artist thumbnail
                        Box(
                            Modifier.size(52.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            val thumb = artist.artist.thumbnail
                            if (thumb != null) {
                                AsyncImage(
                                    url = thumb,
                                    contentDescription = artist.artist.title,
                                    modifier = Modifier.fillMaxSize(),
                                    placeholder = {
                                        Icon(
                                            Icons.Rounded.Person, null,
                                            modifier = Modifier.size(28.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        )
                                    },
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.Person, null,
                                    Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                artist.artist.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val meta = listOfNotNull(
                                artist.monthlyListenerCount?.let { "$it monthly listeners" },
                                artist.subscriberCountText,
                            ).firstOrNull()
                            if (meta != null) {
                                Text(
                                    meta,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    // Description
                    val desc = artist.description
                    if (!desc.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Artist info unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }

        // ── Queue ──
        if (queue.size > 1) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "UP NEXT",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                )
            }
            Spacer(Modifier.height(8.dp))
            val upcoming = queue.drop(queueIndex + 1).take(5)
            upcoming.forEach { qSong ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(38.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (qSong.albumArt != null) {
                            AsyncImage(
                                url = qSong.albumArt,
                                contentDescription = qSong.title,
                                modifier = Modifier.fillMaxSize(),
                                placeholder = {
                                    Icon(
                                        Icons.Rounded.MusicNote, null,
                                        Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    )
                                },
                            )
                        } else {
                            Icon(
                                Icons.Rounded.MusicNote, null,
                                Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            qSong.title,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            qSong.artist,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

// ─────────────────────────────────────────────────────────────
// QUEUE PANEL (drag-and-drop)
// ─────────────────────────────────────────────────────────────

@Composable
fun QueuePanel(playerState: PlayerState) {
    val queue = playerState.queue
    val currentIndex = playerState.queueIndex

    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    val itemHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Queue",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    "${queue.size} songs",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(queue, key = { idx, song -> "${idx}_${song.id}" }) { index, song ->
                val isPlaying = index == currentIndex
                val isDragging = draggingIndex == index
                val targetOffset = when {
                    draggingIndex == null -> 0f
                    index == draggingIndex -> dragOffsetY
                    else -> {
                        val draggedTo = (draggingIndex!! + dragOffsetY / itemHeightPx).roundToInt()
                            .coerceIn(0, queue.size - 1)
                        when {
                            draggingIndex!! < index && index <= draggedTo -> -itemHeightPx
                            draggingIndex!! > index && index >= draggedTo -> itemHeightPx
                            else -> 0f
                        }
                    }
                }

                QueueItem(
                    song = song,
                    isPlaying = isPlaying,
                    isDragging = isDragging,
                    offsetY = targetOffset,
                    onDragStart = {
                        draggingIndex = index
                        dragOffsetY = 0f
                    },
                    onDrag = { dy -> dragOffsetY += dy },
                    onDragEnd = {
                        val to = (index + dragOffsetY / itemHeightPx).roundToInt()
                            .coerceIn(0, queue.size - 1)
                        playerState.reorderQueue(index, to)
                        draggingIndex = null
                        dragOffsetY = 0f
                    },
                    onRemove = { playerState.removeFromQueue(index) },
                    onClick = {
                        playerState.queue = queue
                        playerState.queueIndex = index
                        playerState.playSong(song)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun QueueItem(
    song: PlayerSong,
    isPlaying: Boolean,
    isDragging: Boolean,
    offsetY: Float,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onRemove: () -> Unit,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .offset { IntOffset(0, offsetY.roundToInt()) }
            .zIndex(if (isDragging) 1f else 0f)
            .shadow(if (isDragging) 8.dp else 0.dp, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit)  { hovered = false },
        shape = RoundedCornerShape(10.dp),
        color = when {
            isPlaying || isDragging -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            hovered -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> Color.Transparent
        },
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDrag = { _, delta -> onDrag(delta.y) },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.DragHandle,
                    "Drag",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }

            Spacer(Modifier.width(4.dp))

            // Album art with animated PlayingIndicator overlay when playing
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (song.albumArt != null) {
                    AsyncImage(
                        url = song.albumArt,
                        contentDescription = song.title,
                        modifier = Modifier.fillMaxSize(),
                        placeholder = {
                            Icon(
                                Icons.Rounded.MusicNote, null, Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                        },
                    )
                } else {
                    Icon(
                        Icons.Rounded.MusicNote, null, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }

                // If currently playing, show overlay
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        PlayingIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.height(18.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                    color = if (isPlaying) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song.artist,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Remove button (shown on hover)
            AnimatedVisibility(hovered && !isPlaying) {
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        "Remove",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
