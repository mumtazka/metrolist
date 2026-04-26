/**
 * Metrolist Desktop — Real YouTube Music Data
 * Material Design 3 icons, dynamic theming, live API
 */

package com.metrolist.desktop

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.metrolist.desktop.auth.DesktopPreferences
import com.metrolist.desktop.player.LyricLine
import com.metrolist.desktop.player.PlayerState
import com.metrolist.desktop.search.SearchRanker
import com.metrolist.desktop.ui.theme.*
import com.metrolist.desktop.viewmodel.DesktopViewModel
import com.metrolist.innertube.models.*
import com.metrolist.innertube.pages.HomePage
import kotlinx.coroutines.delay
import com.metrolist.desktop.sync.DesktopSyncClient
import com.metrolist.desktop.ui.AsyncImage
import com.metrolist.desktop.ui.LeftSidebarPanel
import com.metrolist.desktop.ui.NavScreen
import com.metrolist.desktop.ui.NowPlayingPanel
import com.metrolist.desktop.ui.TopBar
import java.awt.Desktop as AwtDesktop
import java.awt.Dimension
import java.net.URI

// ============================================================
// Navigation
// ============================================================

// Navigation is handled by NavScreen in DesktopPanels.kt
// Kept as typealiases for compatibility with existing screen composables
typealias Screen = NavScreen

// ============================================================
// Entry Point
// ============================================================

fun main() = application {
    // Start maximized — works properly on tiling WMs like Hyprland
    val windowState = rememberWindowState(
        placement = WindowPlacement.Maximized,
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "Metrolist",
        state = windowState,
    ) {
        // Set minimum window size for proper Hyprland/tiling WM behavior
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(900, 600)
        }

        val scope = rememberCoroutineScope()
        val viewModel = remember { DesktopViewModel(scope) }
        val playerState = remember { PlayerState() }
        val syncClient = remember { com.metrolist.desktop.sync.DesktopSyncClient(
            relayUrl = "wss://metrolistsyncrelay-ooae5v0w.b4a.run/sync",
            playerState = playerState,
        ) }
        var currentScreen by remember { mutableStateOf(Screen.HOME) }
        var searchQuery by remember { mutableStateOf("") }

        // Load persisted settings from disk
        val savedConfig = remember { DesktopPreferences.load() }
        var pureBlack by remember { mutableStateOf(savedConfig.pureBlack) }
        var themeColor by remember {
            val savedArgb = savedConfig.themeColorArgb
            mutableStateOf(if (savedArgb != null) Color(savedArgb) else DefaultThemeColor)
        }

        // Load home on first launch
        LaunchedEffect(Unit) {
            viewModel.loadHome()
        }

        // Auto-connect sync when logged in
        LaunchedEffect(viewModel.accountEmail) {
            val email = viewModel.accountEmail
            if (email != null) {
                syncClient.connect(email)
            } else {
                syncClient.disconnect()
            }
        }

        // Clean up on close
        DisposableEffect(Unit) {
            onDispose {
                syncClient.disconnect()
                playerState.cleanup()
            }
        }

        // Persist settings when they change
        LaunchedEffect(pureBlack, themeColor) {
            val argb = ((themeColor.alpha * 255).toInt() shl 24) or
                       ((themeColor.red * 255).toInt() shl 16) or
                       ((themeColor.green * 255).toInt() shl 8) or
                       (themeColor.blue * 255).toInt()
            val config = DesktopPreferences.load().copy(
                pureBlack = pureBlack,
                themeColorArgb = argb,
            )
            DesktopPreferences.save(config)
        }

        // Auto-load artist info when the playing song changes
        // We look for an ArtistItem in the search results to get a browseId
        LaunchedEffect(playerState.currentSong?.id) {
            val song = playerState.currentSong
            if (song == null) {
                viewModel.clearArtist()
                return@LaunchedEffect
            }
            // Try to find an artist browseId from home sections first
            val artistId = viewModel.homeSections
                .flatMap { it.items }
                .filterIsInstance<ArtistItem>()
                .firstOrNull { it.title.equals(song.artist, ignoreCase = true) }?.id
            if (artistId != null) {
                viewModel.loadArtist(artistId)
            } else {
                // Search for the artist to get a proper browseId
                val query = song.artist.trim()
                if (query.isNotBlank()) {
                    viewModel.clearArtist()
                    delay(300)
                    viewModel.searchArtistForPanel(query)
                }
            }
        }

        MetrolistTheme(pureBlack = pureBlack, themeColor = themeColor) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val totalWidth = maxWidth
                    // Responsive breakpoints
                    val showLeftSidebar  = totalWidth >= 600.dp
                    val showRightPanel   = totalWidth >= 1100.dp && (playerState.currentSong != null || playerState.showQueue)
                    val leftWidth        = 260.dp
                    val rightWidth       = 280.dp

                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.weight(1f)) {

                            // ── Left sidebar ──
                            AnimatedVisibility(
                                visible = showLeftSidebar,
                                enter = slideInHorizontally() + fadeIn(),
                                exit  = slideOutHorizontally() + fadeOut(),
                            ) {
                                LeftSidebarPanel(
                                    currentScreen = currentScreen,
                                    onNavigate = { currentScreen = it },
                                    viewModel = viewModel,
                                    modifier = Modifier.width(leftWidth),
                                )
                            }

                            if (showLeftSidebar) {
                                VerticalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                )
                            }

                            // ── Main content ──
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.background),
                            ) {
                                TopBar(
                                    searchQuery = searchQuery,
                                    onQueryChange = { searchQuery = it },
                                    onSearchFocused = { currentScreen = Screen.SEARCH },
                                    viewModel = viewModel,
                                )
                                Box(modifier = Modifier.weight(1f)) {
                                    when (currentScreen) {
                                        Screen.HOME     -> HomeScreen(viewModel, playerState)
                                        Screen.SEARCH   -> SearchScreen(viewModel, searchQuery, { searchQuery = it }, playerState)
                                        Screen.LIBRARY  -> LibraryScreen(viewModel, playerState)
                                        Screen.LIKED    -> LikedSongsScreen(viewModel, playerState)
                                        Screen.SETTINGS -> SettingsScreen(
                                            viewModel,
                                            pureBlack, { pureBlack = it },
                                            themeColor, { newColor -> themeColor = newColor },
                                            syncClient,
                                        )
                                    }
                                }
                            }

                            // ── Right now-playing panel ──
                            AnimatedVisibility(
                                visible = showRightPanel,
                                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                                exit  = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                            ) {
                                if (showRightPanel) {
                                    Row {
                                        VerticalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                        )
                                        NowPlayingPanel(
                                            playerState = playerState,
                                            viewModel   = viewModel,
                                            modifier    = Modifier.width(rightWidth),
                                        )
                                    }
                                }
                            }
                        }

                        // Lyrics panel (slides up above player bar)
                        if (playerState.showLyrics && playerState.currentSong != null) {
                            LyricsPanel(playerState)
                        }

                        if (playerState.currentSong != null) {
                            PlayerBar(playerState, syncClient, viewModel)
                        }
                    }
                }
            }
        }
    }
}


// ============================================================
// Hover helper
// ============================================================

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.hoverBackground(
    isHovered: Boolean,
    onHoverChanged: (Boolean) -> Unit,
): Modifier = this
    .onPointerEvent(PointerEventType.Enter) { onHoverChanged(true) }
    .onPointerEvent(PointerEventType.Exit) { onHoverChanged(false) }
    .background(
        if (isHovered) MaterialTheme.colorScheme.surfaceContainerHighest
        else Color.Transparent
    )

// ============================================================
// Sidebar
// ============================================================

// Sidebar replaced by LeftSidebarPanel in DesktopPanels.kt

// ============================================================
// Home Screen — Real YouTube Music API
// ============================================================

@Composable
fun HomeScreen(viewModel: DesktopViewModel, playerState: PlayerState) {
    when {
        viewModel.homeLoading && viewModel.homeSections.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("Loading YouTube Music...", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        viewModel.homeError != null && viewModel.homeSections.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.CloudOff, "Error",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                    Spacer(Modifier.height(16.dp))
                    Text("Failed to load", style = MaterialTheme.typography.titleMedium)
                    Text(viewModel.homeError ?: "", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = { viewModel.loadHome() }) {
                        Icon(Icons.Rounded.Refresh, "Retry", Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Retry")
                    }
                }
            }
        }
        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                // Gradient header
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(140.dp)
                            .background(
                                Brush.verticalGradient(listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    MaterialTheme.colorScheme.background,
                                ))
                            )
                            .padding(horizontal = 32.dp, vertical = 24.dp),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        Column {
                            Text("Good ${getGreeting()}", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                            Text("What do you want to listen to?", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // Real YouTube Music sections
                items(viewModel.homeSections) { section ->
                    HomeSectionView(section, playerState)
                }

                if (viewModel.homeLoading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeSectionView(section: HomePage.Section, playerState: PlayerState) {
    Column {
        // Section header
        Row(
            modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 12.dp, end = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(section.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            val label = section.label
            if (label != null) {
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Items carousel
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val songs = section.items.filterIsInstance<SongItem>()
            items(section.items) { item ->
                YTItemCard(item, playerState, contextSongs = songs)
            }
        }
    }
}

@Composable
fun YTItemCard(item: YTItem, playerState: PlayerState, contextSongs: List<SongItem> = emptyList()) {
    var hovered by remember { mutableStateOf(false) }
    val thumbnailUrl = item.thumbnail

    Surface(
        modifier = Modifier.width(170.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        onClick = {
            // For songs, start playback simulation
            if (item is SongItem) {
                if (contextSongs.isNotEmpty()) {
                    val queue = contextSongs.map { s -> com.metrolist.desktop.player.PlayerSong(s.id, s.title, s.artists.joinToString { a -> a.name }, s.thumbnail, (s.duration ?: 210) * 1000L) }
                    val index = contextSongs.indexOf(item).coerceAtLeast(0)
                    playerState.playQueue(queue, index)
                } else {
                    playerState.playSongItem(item)
                }
            }
        },
    ) {
        Column(
            modifier = Modifier.hoverBackground(hovered) { hovered = it }.padding(8.dp),
        ) {
            // Thumbnail
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(
                    if (item is ArtistItem) CircleShape else RoundedCornerShape(8.dp)
                ),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    url = thumbnailUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    placeholder = {
                        Box(
                            Modifier.fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                when (item) {
                                    is SongItem -> Icons.Rounded.MusicNote
                                    is AlbumItem -> Icons.Rounded.Album
                                    is ArtistItem -> Icons.Rounded.Person
                                    is PlaylistItem -> Icons.AutoMirrored.Rounded.QueueMusic
                                    else -> Icons.Rounded.MusicNote
                                },
                                item.title,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    },
                )
                // Play button overlay
                if (hovered && item is SongItem) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(8.dp).size(40.dp)
                            .clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                            .clickable {
                                if (contextSongs.isNotEmpty()) {
                                    val queue = contextSongs.map { s -> com.metrolist.desktop.player.PlayerSong(s.id, s.title, s.artists.joinToString { a -> a.name }, s.thumbnail, (s.duration ?: 210) * 1000L) }
                                    val index = contextSongs.indexOf(item).coerceAtLeast(0)
                                    playerState.playQueue(queue, index)
                                } else {
                                    playerState.playSongItem(item)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.PlayArrow, "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(item.title, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // Subtitle based on type
            val subtitle = when (item) {
                is SongItem -> item.artists.joinToString { it.name }
                is AlbumItem -> item.artists?.joinToString { it.name } ?: ""
                is ArtistItem -> "Artist"
                is PlaylistItem -> item.author?.name ?: ""
                else -> ""
            }
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ============================================================
// Search Screen — Real YouTube Music API
// ============================================================

@Composable
fun SearchScreen(viewModel: DesktopViewModel, query: String, onQueryChange: (String) -> Unit, playerState: PlayerState) {
    // Debounced search
    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            delay(400) // debounce
            viewModel.search(query)
        }
    }

    // Search bar is now in TopBar — SearchScreen just shows results/categories
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Spacer(Modifier.height(8.dp))

        when {
            query.isBlank() -> {
                Text("Browse All", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                data class GenreData(val name: String, val color: Color, val icon: ImageVector)
                val categories = listOf(
                    GenreData("Pop", Color(0xFFE91E63), Icons.Rounded.MusicNote),
                    GenreData("Hip-Hop", Color(0xFFFF5722), Icons.Rounded.Mic),
                    GenreData("Rock", Color(0xFFFF9800), Icons.Rounded.Album),
                    GenreData("R&B", Color(0xFF2196F3), Icons.Rounded.Favorite),
                    GenreData("Electronic", Color(0xFF00BCD4), Icons.Rounded.Equalizer),
                    GenreData("Jazz", Color(0xFFFFC107), Icons.Rounded.Audiotrack),
                    GenreData("Classical", Color(0xFF673AB7), Icons.Rounded.LibraryMusic),
                    GenreData("Indie", Color(0xFF4CAF50), Icons.AutoMirrored.Rounded.QueueMusic),
                    GenreData("K-Pop", Color(0xFFE91E63).copy(alpha = 0.85f), Icons.Rounded.Star),
                    GenreData("Lo-Fi", Color(0xFF607D8B), Icons.Rounded.Bedtime),
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(categories) { genre ->
                        Surface(
                            modifier = Modifier.height(100.dp),
                            shape = RoundedCornerShape(12.dp), color = genre.color,
                            onClick = { onQueryChange(genre.name) },
                        ) {
                            Box(
                                Modifier.fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                genre.color,
                                                genre.color.copy(alpha = 0.6f),
                                            )
                                        )
                                    )
                            ) {
                                // Genre icon (top-right, semi-transparent)
                                Icon(
                                    genre.icon, null,
                                    tint = Color.White.copy(alpha = 0.25f),
                                    modifier = Modifier
                                        .size(64.dp)
                                        .align(Alignment.TopEnd)
                                        .offset(x = 8.dp, y = (-8).dp),
                                )
                                // Genre name
                                Text(
                                    genre.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                                )
                            }
                        }
                    }
                }
            }
            viewModel.searchLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            viewModel.searchResults.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.SearchOff, "No results", modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.height(16.dp))
                        Text("No results found", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            else -> {
                Text("Results for \"$query\"", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))

                val allResults = viewModel.searchResults
                val topResult  = SearchRanker.pickTopResult(allResults, query, viewModel.likedSongIds)
                val restResults = allResults.filter { it !== topResult }
                val searchSongs = allResults.filterIsInstance<SongItem>()

                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val isWide = maxWidth >= 800.dp
                    if (isWide) {
                        // ── Wide layout: Top Result card left, list right ──
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            // Left: Top Result card
                            if (topResult != null) {
                                TopResultCard(
                                    item = topResult,
                                    playerState = playerState,
                                    searchSongs = searchSongs,
                                    modifier = Modifier.width(260.dp).fillMaxHeight(),
                                )
                            }
                            // Right: remaining results
                            LazyColumn(Modifier.weight(1f)) {
                                items(restResults) { item ->
                                    SearchResultRow(item, playerState, searchSongs)
                                }
                            }
                        }
                    } else {
                        // ── Narrow layout: stacked ──
                        LazyColumn(Modifier.fillMaxSize()) {
                            if (topResult != null) {
                                item {
                                    TopResultCard(
                                        item = topResult,
                                        playerState = playerState,
                                        searchSongs = searchSongs,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(16.dp))
                                }
                            }
                            items(restResults) { item ->
                                SearchResultRow(item, playerState, searchSongs)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Top Result Card ────────────────────────────────────────────────────────

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TopResultCard(
    item: YTItem,
    playerState: PlayerState,
    searchSongs: List<SongItem>,
    modifier: Modifier = Modifier,
) {
    var hovered by remember { mutableStateOf(false) }

    val typeLabel = when (item) {
        is SongItem   -> if (item.isVideoSong) "Video" else "Song"
        is ArtistItem -> "Artist"
        is AlbumItem  -> "Album"
        is PlaylistItem -> "Playlist"
        else          -> "Result"
    }
    val subtitle = when (item) {
        is SongItem   -> item.artists.joinToString { it.name }
        is ArtistItem -> "Artist"
        is AlbumItem  -> item.artists?.joinToString { it.name } ?: ""
        else          -> ""
    }

    Column(
        modifier = modifier
            .then(Modifier.clip(RoundedCornerShape(16.dp)))
            .background(
                if (hovered) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit)  { hovered = false }
            .clickable {
                when (item) {
                    is SongItem -> {
                        val idx = searchSongs.indexOf(item).coerceAtLeast(0)
                        val queue = searchSongs.map { s ->
                            com.metrolist.desktop.player.PlayerSong(
                                s.id, s.title,
                                s.artists.joinToString { a -> a.name },
                                s.thumbnail, (s.duration ?: 210) * 1000L,
                            )
                        }
                        playerState.playQueue(queue, idx)
                    }
                    else -> {} // artist/album click = no-op for now
                }
            }
            .padding(20.dp),
    ) {
        // Large thumbnail
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(if (item is ArtistItem) 999.dp else 12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (item.thumbnail != null) {
                AsyncImage(
                    url = item.thumbnail,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    placeholder = {
                        Icon(Icons.Rounded.MusicNote, null, Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    },
                )
            } else {
                Icon(Icons.Rounded.MusicNote, null, Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            }
        }

        Spacer(Modifier.height(16.dp))

        // Type badge
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                typeLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            item.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Duration (songs only)
        if (item is SongItem) {
            item.duration?.let { dur ->
                Spacer(Modifier.height(4.dp))
                Text(
                    com.metrolist.desktop.player.PlayerState.formatTime(dur * 1000L),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }

        // Action buttons (only for songs)
        if (item is SongItem) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Play button
                Button(
                    onClick = {
                        val idx = searchSongs.indexOf(item).coerceAtLeast(0)
                        val queue = searchSongs.map { s ->
                            com.metrolist.desktop.player.PlayerSong(
                                s.id, s.title,
                                s.artists.joinToString { a -> a.name },
                                s.thumbnail, (s.duration ?: 210) * 1000L,
                            )
                        }
                        playerState.playQueue(queue, idx)
                    },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.height(40.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, "Play", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play", style = MaterialTheme.typography.labelLarge)
                }
                // Add to queue button
                OutlinedButton(
                    onClick = { playerState.addToQueue(item) },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.height(40.dp),
                ) {
                    Icon(Icons.Rounded.AddCircleOutline, "Add to queue", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add to Queue", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}


@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SearchResultRow(item: YTItem, playerState: PlayerState, contextSongs: List<SongItem> = emptyList()) {
    var hovered by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (hovered) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
        onClick = {
            if (item is SongItem) {
                if (contextSongs.isNotEmpty()) {
                    val queue = contextSongs.map { s -> com.metrolist.desktop.player.PlayerSong(s.id, s.title, s.artists.joinToString { a -> a.name }, s.thumbnail, (s.duration ?: 210) * 1000L) }
                    val index = contextSongs.indexOf(item).coerceAtLeast(0)
                    playerState.playQueue(queue, index)
                } else {
                    playerState.playSongItem(item)
                }
            }
        },
    ) {
        Row(
            modifier = Modifier.hoverBackground(hovered) { hovered = it }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(if (item is ArtistItem) 24.dp else 4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    url = item.thumbnail,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    placeholder = {
                        Box(
                            Modifier.fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                when (item) {
                                    is SongItem -> Icons.Rounded.MusicNote
                                    is AlbumItem -> Icons.Rounded.Album
                                    is ArtistItem -> Icons.Rounded.Person
                                    is PlaylistItem -> Icons.AutoMirrored.Rounded.QueueMusic
                                    else -> Icons.Rounded.MusicNote
                                },
                                null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    },
                )
            }
            Spacer(Modifier.width(12.dp))
            // Title + subtitle
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = when (item) {
                    is SongItem -> "Song · ${item.artists.joinToString { it.name }}"
                    is AlbumItem -> "Album · ${item.artists?.joinToString { it.name } ?: ""}"
                    is ArtistItem -> "Artist"
                    is PlaylistItem -> "Playlist · ${item.author?.name ?: ""}"
                    else -> ""
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }

            // Duration (songs only)
            if (item is SongItem) {
                item.duration?.let { dur ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        com.metrolist.desktop.player.PlayerState.formatTime(dur * 1000L),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }

            // Hover action buttons (songs only)
            if (hovered && item is SongItem) {
                Spacer(Modifier.width(4.dp))
                // Add to queue (with tooltip)
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Add to queue") } },
                    state = rememberTooltipState(),
                ) {
                    IconButton(
                        onClick = { playerState.addToQueue(item) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Rounded.AddCircleOutline, "Add to queue",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                // Play now (with tooltip)
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Play now") } },
                    state = rememberTooltipState(),
                ) {
                    IconButton(
                        onClick = {
                            if (contextSongs.isNotEmpty()) {
                                val queue = contextSongs.map { s -> com.metrolist.desktop.player.PlayerSong(s.id, s.title, s.artists.joinToString { a -> a.name }, s.thumbnail, (s.duration ?: 210) * 1000L) }
                                val index = contextSongs.indexOf(item).coerceAtLeast(0)
                                playerState.playQueue(queue, index)
                            } else {
                                playerState.playSongItem(item)
                            }
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, "Play now", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

// ============================================================
// Library Screen — requires login
// ============================================================

@Composable
fun LibraryScreen(viewModel: DesktopViewModel, playerState: PlayerState) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Your Library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        if (!viewModel.isLoggedIn) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.LibraryMusic, "Library",
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(16.dp))
                    Text("Sign in to see your library", style = MaterialTheme.typography.titleMedium)
                    Text("Your playlists, liked songs, and more", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { openGoogleLogin() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                        Icon(Icons.AutoMirrored.Rounded.Login, "Login", Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Sign in with Google")
                    }
                }
            }
        } else {
            Text("Logged in as ${viewModel.accountName ?: "Unknown"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text("Library browsing coming soon...", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============================================================
// Liked Songs Screen — requires login
// ============================================================

@Composable
fun LikedSongsScreen(viewModel: DesktopViewModel, playerState: PlayerState) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxWidth().height(200.dp)
                .background(
                    Brush.verticalGradient(listOf(
                        MaterialTheme.colorScheme.error.copy(alpha = 0.25f),
                        MaterialTheme.colorScheme.background,
                    ))
                )
                .padding(32.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            Column {
                Icon(Icons.Rounded.Favorite, "Liked",
                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text("Liked Songs", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            }
        }
        if (!viewModel.isLoggedIn) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Sign in to see your liked songs", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { openGoogleLogin() }) {
                        Text("Sign in with Google")
                    }
                }
            }
        } else {
            Text("Loading liked songs...",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============================================================
// Settings Screen
// ============================================================

@Composable
fun SettingsScreen(
    viewModel: DesktopViewModel,
    pureBlack: Boolean, onPureBlackChanged: (Boolean) -> Unit,
    themeColor: Color, onThemeColorChanged: (Color) -> Unit,
    syncClient: DesktopSyncClient,
) {
    var cookieInput by remember { mutableStateOf("") }
    var showCookieDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))

        // Account
        SettingsSection("Account", Icons.Rounded.AccountCircle) {
            if (viewModel.isLoggedIn) {
                ListItem(
                    headlineContent = { Text(viewModel.accountName ?: "Logged in") },
                    supportingContent = { Text(viewModel.accountEmail ?: "YouTube Music account") },
                    leadingContent = { Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        OutlinedButton(onClick = { viewModel.logout() }) { Text("Sign out") }
                    }
                )
            } else {
                ListItem(
                    headlineContent = { Text("Not signed in") },
                    supportingContent = { Text("Sign in to access your library & playlists") },
                    leadingContent = { Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openGoogleLogin() }) {
                        Icon(Icons.AutoMirrored.Rounded.Login, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sign in via browser")
                    }
                    OutlinedButton(onClick = { showCookieDialog = true }) {
                        Text("Paste cookie")
                    }
                }
            }
        }

        // Appearance
        SettingsSection("Appearance", Icons.Rounded.Palette) {
            ListItem(
                headlineContent = { Text("Pure black background") },
                supportingContent = { Text("Use AMOLED-friendly pure black") },
                trailingContent = { Switch(checked = pureBlack, onCheckedChange = onPureBlackChanged) }
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ListItem(headlineContent = { Text("Theme color") }, supportingContent = { Text("Changes accent & dynamic colors") })
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val colors = listOf(
                    Color(0xFFED5564) to "Red", Color(0xFF2196F3) to "Blue",
                    Color(0xFF4CAF50) to "Green", Color(0xFFFF9800) to "Orange",
                    Color(0xFF9C27B0) to "Purple", Color(0xFF00BCD4) to "Teal",
                    Color(0xFFE91E63) to "Pink", Color(0xFF607D8B) to "Gray",
                )
                colors.forEach { (color, _) ->
                    val isSelected = themeColor == color
                    Surface(
                        modifier = Modifier.size(36.dp), shape = CircleShape, color = color,
                        border = if (isSelected) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else null,
                        onClick = { onThemeColorChanged(color) },
                    ) {
                        if (isSelected) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Check, "Selected", tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }

        // Sync — real connection status driven by syncClient
        SettingsSection("Sync & Remote", Icons.Rounded.Devices) {
            val isSyncConnected by remember { derivedStateOf { syncClient.connected } }
            var syncEnabled by remember { mutableStateOf(viewModel.isLoggedIn) }
            ListItem(
                headlineContent = { Text("Cross-device sync") },
                supportingContent = { Text("Control playback from mobile/other devices") },
                trailingContent = {
                    Switch(
                        checked = syncEnabled,
                        onCheckedChange = { enabled ->
                            syncEnabled = enabled
                            val email = viewModel.accountEmail
                            if (enabled && email != null) syncClient.connect(email)
                            else syncClient.disconnect()
                        }
                    )
                }
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ListItem(
                headlineContent = { Text("Relay server") },
                supportingContent = { Text("metrolistsyncrelay-ooae5v0w.b4a.run") },
                trailingContent = {
                    val (bgColor, label) = if (isSyncConnected)
                        MaterialTheme.colorScheme.primaryContainer to "Connected"
                    else
                        MaterialTheme.colorScheme.errorContainer to "Disconnected"
                    Surface(shape = RoundedCornerShape(12.dp), color = bgColor) {
                        Text(
                            label,
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSyncConnected)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            )
            if (!viewModel.isLoggedIn) {
                ListItem(
                    headlineContent = { Text("Sign in required", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    supportingContent = { Text("Remote sync requires a signed-in Google account",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)) },
                    leadingContent = { Icon(Icons.Rounded.Info, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)) },
                )
            }
        }

        // About
        SettingsSection("About", Icons.Rounded.Info) {
            ListItem(headlineContent = { Text("Version") }, supportingContent = { Text("Metrolist Desktop v1.0.0") })
            ListItem(headlineContent = { Text("License") }, supportingContent = { Text("GPL-3.0 · Open Source") })
        }
    }

    // Cookie paste dialog
    if (showCookieDialog) {
        AlertDialog(
            onDismissRequest = { showCookieDialog = false },
            title = { Text("Paste YouTube Music Cookie") },
            text = {
                Column {
                    Text("Go to music.youtube.com, open DevTools (F12) → Application → Cookies, copy all cookies as a string.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = cookieInput, onValueChange = { cookieInput = it },
                        placeholder = { Text("Paste cookie string...") },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        maxLines = 5,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (cookieInput.isNotBlank()) {
                        viewModel.loginWithCookie(cookieInput.trim())
                        showCookieDialog = false
                        cookieInput = ""
                    }
                }) { Text("Sign in") }
            },
            dismissButton = {
                TextButton(onClick = { showCookieDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun SettingsSection(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            content()
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ============================================================
// Player Bar
// ============================================================

@Composable
fun PlayerBar(playerState: PlayerState, syncClient: DesktopSyncClient, viewModel: DesktopViewModel) {
    val song = playerState.currentSong ?: return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Column {
            Slider(
                value = playerState.progressFraction,
                onValueChange = { playerState.seekTo((it * playerState.duration).toLong()) },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Song info
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            url = song.albumArt,
                            contentDescription = song.title,
                            modifier = Modifier.fillMaxSize(),
                            placeholder = {
                                Box(
                                    Modifier.fillMaxSize()
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (playerState.isLoadingStream) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    } else {
                                        Icon(Icons.Rounded.MusicNote, "art",
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(24.dp))
                                    }
                                }
                            },
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(song.title, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val statusText = when {
                            playerState.isLoadingStream -> "Loading stream..."
                            playerState.streamError != null -> playerState.streamError ?: "Error"
                            else -> song.artist
                        }
                        Text(statusText, style = MaterialTheme.typography.bodySmall,
                            color = if (playerState.streamError != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Spacer(Modifier.width(8.dp))
                    val isLiked = playerState.currentSong?.let { viewModel.isLiked(it.id) } == true
                    IconButton(
                        onClick = {
                            val currentSong = playerState.currentSong
                            if (currentSong != null) viewModel.toggleLike(currentSong.id)
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            "Like",
                            tint = if (isLiked) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // Controls
                Row(modifier = Modifier.weight(1.2f), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { playerState.toggleShuffle() }, Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Shuffle, "Shuffle",
                            tint = if (playerState.isShuffled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { playerState.skipPrevious() }, Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, "Previous", modifier = Modifier.size(28.dp))
                    }
                    FilledIconButton(
                        onClick = { playerState.togglePlayPause() }, modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary),
                    ) {
                        Icon(if (playerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            "Play/Pause", modifier = Modifier.size(28.dp))
                    }
                    IconButton(onClick = { playerState.skipNext() }, Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.SkipNext, "Next", modifier = Modifier.size(28.dp))
                    }
                    IconButton(onClick = { playerState.cycleRepeat() }, Modifier.size(36.dp)) {
                        Icon(
                            if (playerState.repeatMode == PlayerState.RepeatMode.ONE) Icons.Rounded.RepeatOne
                            else Icons.Rounded.Repeat, "Repeat",
                            tint = if (playerState.repeatMode != PlayerState.RepeatMode.OFF) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }

                // Volume + extras
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${playerState.currentTimeFormatted} / ${playerState.durationFormatted}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Volume",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Slider(
                        value = playerState.volume, onValueChange = { playerState.volume = it },
                        modifier = Modifier.width(100.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.onSurface,
                            activeTrackColor = MaterialTheme.colorScheme.onSurface,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                    // Lyrics toggle button
                    IconButton(
                        onClick = { playerState.showLyrics = !playerState.showLyrics },
                        Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Rounded.MusicNote, "Lyrics",
                            tint = if (playerState.showLyrics) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    // Queue toggle button
                    IconButton(
                        onClick = { playerState.showQueue = !playerState.showQueue },
                        Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Rounded.List, "Queue",
                            tint = if (playerState.showQueue) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(onClick = {}, Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Devices, "Devices",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

// ============================================================
// Lyrics Panel
// ============================================================

@Composable
fun LyricsPanel(playerState: PlayerState) {
    val lyrics = playerState.lyrics
    val currentIndex = playerState.currentLyricIndex
    val listState = rememberLazyListState()

    // Resizable height state (default 360dp, clamped 200..600dp)
    var panelHeightDp by remember { mutableStateOf(360f) }
    val density = androidx.compose.ui.platform.LocalDensity.current

    // Auto-scroll to current lyric smoothly
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && currentIndex < lyrics.size) {
            listState.animateScrollToItem(
                index = currentIndex.coerceAtLeast(0),
                scrollOffset = -120,
            )
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(panelHeightDp.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column {
            // ── Drag-to-resize handle ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            // Dragging up (negative delta) = bigger panel
                            val deltaDp = with(density) { (-delta).toDp().value }
                            panelHeightDp = (panelHeightDp + deltaDp).coerceIn(200f, 600f)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // Visual handle pill
                Box(
                    Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                )
            }

            // ── Header ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.QueueMusic, "Lyrics",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Lyrics",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                // Height indicator
                Text(
                    "${panelHeightDp.toInt()}dp",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { playerState.showLyrics = false },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Rounded.KeyboardArrowDown, "Close",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            )

            when {
                playerState.lyricsLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
                lyrics.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Rounded.MusicNote, "No lyrics",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(32.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "No lyrics available",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        itemsIndexed(lyrics) { index, line ->
                            val isCurrent = index == currentIndex
                            val isPast = currentIndex >= 0 && index < currentIndex
                            val isSynced = line.timeMs >= 0

                            // Animated color for smooth transitions
                            val textColor by animateColorAsState(
                                targetValue = when {
                                    isCurrent -> MaterialTheme.colorScheme.primary
                                    isPast && isSynced -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                },
                                animationSpec = tween(durationMillis = 400),
                                label = "lyricColor_$index",
                            )

                            Text(
                                text = line.text.ifBlank { "♪" },
                                style = if (isCurrent) MaterialTheme.typography.headlineSmall
                                    else MaterialTheme.typography.titleMedium,
                                fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Normal,
                                color = textColor,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = if (isCurrent) 8.dp else 4.dp)
                                    .clickable {
                                        if (line.timeMs >= 0) playerState.seekTo(line.timeMs)
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// Utilities
// ============================================================

fun openGoogleLogin() {
    try {
        AwtDesktop.getDesktop().browse(
            URI("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
        )
    } catch (_: Exception) { }
}

fun getGreeting(): String {
    val hour = java.time.LocalTime.now().hour
    return when {
        hour < 12 -> "morning"
        hour < 17 -> "afternoon"
        else -> "evening"
    }
}
