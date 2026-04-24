/**
 * Metrolist Desktop — UI Panels
 * LeftSidebarPanel, TopBar, NowPlayingPanel
 */

package com.metrolist.desktop.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
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
import com.metrolist.desktop.player.PlayerSong
import com.metrolist.desktop.player.PlayerState
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
    LIKED("Liked", Icons.Rounded.Favorite),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LeftSidebarPanel(
    currentScreen: NavScreen,
    onNavigate: (NavScreen) -> Unit,
    viewModel: DesktopViewModel,
    modifier: Modifier = Modifier,
) {
    val playlists = viewModel.userPlaylists
    val scrollState = rememberLazyListState()

    Surface(
        modifier = modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Logo ──
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.MusicNote, null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text("Metrolist",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
            }

            // ── Main Nav ──
            listOf(NavScreen.HOME, NavScreen.SEARCH, NavScreen.LIBRARY).forEach { screen ->
                SidebarNavItem(
                    label = screen.label,
                    icon = screen.icon,
                    selected = currentScreen == screen,
                    onClick = { onNavigate(screen) },
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
            Spacer(Modifier.height(8.dp))

            // ── Your Library header ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Your Library",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ── Liked Songs ──
            SidebarNavItem(
                label = "Liked Songs",
                icon = Icons.Rounded.Favorite,
                selected = currentScreen == NavScreen.LIKED,
                onClick = { onNavigate(NavScreen.LIKED) },
                tintOverride = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                    .takeIf { currentScreen != NavScreen.LIKED },
            )

            // ── Playlist list ──
            if (!viewModel.isLoggedIn) {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text("Sign in to see your playlists",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            } else if (playlists.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text("No playlists yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                }
            } else {
                LazyColumn(
                    state = scrollState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    itemsIndexed(playlists) { _, playlist ->
                        PlaylistSidebarItem(playlist = playlist)
                    }
                }
            }

            // Push settings to bottom
            if (viewModel.isLoggedIn && playlists.isEmpty()) Spacer(Modifier.weight(1f))
            if (!viewModel.isLoggedIn) Spacer(Modifier.weight(1f))

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )

            // ── Settings ──
            SidebarNavItem(
                label = "Settings",
                icon = Icons.Rounded.Settings,
                selected = currentScreen == NavScreen.SETTINGS,
                onClick = { onNavigate(NavScreen.SETTINGS) },
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
) {
    var hovered by remember { mutableStateOf(false) }
    val bg = when {
        selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        hovered  -> MaterialTheme.colorScheme.surfaceContainerHighest
        else     -> Color.Transparent
    }
    val contentColor = if (selected) MaterialTheme.colorScheme.primary
        else tintOverride ?: MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit)  { hovered = false },
        shape = RoundedCornerShape(10.dp),
        color = bg,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, label, tint = contentColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlaylistSidebarItem(playlist: PlaylistItem) {
    var hovered by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit)  { hovered = false },
        shape = RoundedCornerShape(8.dp),
        color = if (hovered) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
        onClick = { /* TODO: open playlist */ },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (playlist.thumbnail != null) {
                    AsyncImage(
                        url = playlist.thumbnail,
                        contentDescription = playlist.title,
                        modifier = Modifier.fillMaxSize(),
                        placeholder = {
                            Icon(Icons.AutoMirrored.Rounded.QueueMusic, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp))
                        },
                    )
                } else {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(playlist.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = buildString {
                    append("Playlist")
                    playlist.author?.name?.let { append(" · $it") }
                }
                Text(sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Search field — pill shaped, centered, takes most space
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    onQueryChange(it)
                    if (it.isNotBlank()) onSearchFocused()
                },
                placeholder = {
                    Text("Search songs, artists, albums...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                },
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSearchFocused() },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor   = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedBorderColor      = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor    = Color.Transparent,
                    cursorColor             = MaterialTheme.colorScheme.primary,
                ),
                shape = RoundedCornerShape(28.dp),
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Rounded.Search, "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp))
                },
                trailingIcon = {
                    AnimatedVisibility(searchQuery.isNotBlank()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Rounded.Close, "Clear", modifier = Modifier.size(18.dp))
                        }
                    }
                },
            )

            Spacer(Modifier.width(12.dp))

            // Account button
            if (viewModel.isLoggedIn) {
                val initials = viewModel.accountName
                    ?.split(" ")?.mapNotNull { it.firstOrNull()?.uppercaseChar() }
                    ?.take(2)?.joinToString("") ?: "?"
                Box(
                    Modifier.size(36.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(initials,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 13.sp)
                }
            } else {
                Icon(Icons.AutoMirrored.Rounded.Login, "Sign in",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp))
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
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

            // ── Album art ──
            Box(
                Modifier.fillMaxWidth()
                    .aspectRatio(1f)
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
                                        Brush.linearGradient(listOf(
                                            MaterialTheme.colorScheme.primaryContainer,
                                            MaterialTheme.colorScheme.secondaryContainer,
                                        ))
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.MusicNote, null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(48.dp))
                            }
                        },
                    )
                } else {
                    Box(
                        Modifier.fillMaxSize()
                            .background(Brush.linearGradient(listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.secondaryContainer,
                            ))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.MusicNote, null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(48.dp))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Song title + artist ──
            Text(song.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(16.dp))

            // ── Artist info ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("About the artist",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))

            if (viewModel.artistLoading) {
                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else if (artist != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Artist thumbnail
                    Box(
                        Modifier.size(52.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        val thumb = artist.artist.thumbnail
                        if (thumb != null) {
                            AsyncImage(
                                url = thumb,
                                contentDescription = artist.artist.title,
                                modifier = Modifier.fillMaxSize(),
                                placeholder = {
                                    Icon(Icons.Rounded.Person, null,
                                        modifier = Modifier.size(28.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                },
                            )
                        } else {
                            Icon(Icons.Rounded.Person, null,
                                Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(artist.artist.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(
                            artist.monthlyListenerCount?.let { "$it monthly listeners" },
                            artist.subscriberCountText,
                        ).firstOrNull()
                        if (meta != null) {
                            Text(meta,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                // Description
                val desc = artist.description
                if (!desc.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis)
                }
            } else {
                Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                    Text("Artist info unavailable",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                }
            }

            // ── Queue ──
            if (queue.size > 1) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("Up next",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                // Show up to 5 upcoming tracks (non-scrollable inside scroll column)
                val upcoming = queue.drop(queueIndex + 1).take(5)
                upcoming.forEach { qSong ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (qSong.albumArt != null) {
                                AsyncImage(
                                    url = qSong.albumArt,
                                    contentDescription = qSong.title,
                                    modifier = Modifier.fillMaxSize(),
                                    placeholder = {
                                        Icon(Icons.Rounded.MusicNote, null,
                                            Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                    },
                                )
                            } else {
                                Icon(Icons.Rounded.MusicNote, null,
                                    Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(qSong.title,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(qSong.artist,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
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

    // Track which item is being dragged
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
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${queue.size} songs",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            .shadow(if (isDragging) 8.dp else 0.dp, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit)  { hovered = false },
        shape = RoundedCornerShape(8.dp),
        color = when {
            isPlaying || isDragging -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
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

            // Album art
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (song.albumArt != null) {
                    AsyncImage(
                        url = song.albumArt,
                        contentDescription = song.title,
                        modifier = Modifier.fillMaxSize(),
                        placeholder = {
                            Icon(Icons.Rounded.MusicNote, null, Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        },
                    )
                } else {
                    Icon(Icons.Rounded.MusicNote, null, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
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
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song.artist,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }

            // Playing indicator
            if (isPlaying) {
                Icon(Icons.Rounded.VolumeUp, "Playing", Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
            }

            // Remove button (shown on hover)
            AnimatedVisibility(hovered && !isPlaying) {
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.Close, "Remove", modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
