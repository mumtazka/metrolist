/**
 * Metrolist Desktop — Real YouTube Music Data
 * Material Design 3 icons, dynamic theming, live API
 */

package com.metrolist.desktop

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.metrolist.desktop.auth.DesktopPreferences
import com.metrolist.desktop.player.PlayerState
import com.metrolist.desktop.ui.theme.*
import com.metrolist.desktop.viewmodel.DesktopViewModel
import com.metrolist.innertube.models.*
import com.metrolist.innertube.pages.HomePage
import kotlinx.coroutines.delay
import java.awt.Desktop as AwtDesktop
import java.net.URI

// ============================================================
// Navigation
// ============================================================

enum class Screen(val label: String, val icon: ImageVector, val activeIcon: ImageVector) {
    HOME("Home", Icons.Rounded.Home, Icons.Rounded.Home),
    SEARCH("Explore", Icons.Rounded.Explore, Icons.Rounded.Explore),
    LIBRARY("Library", Icons.Rounded.LibraryMusic, Icons.Rounded.LibraryMusic),
    LIKED("Liked Songs", Icons.Rounded.Favorite, Icons.Rounded.Favorite),
    SETTINGS("Settings", Icons.Rounded.Settings, Icons.Rounded.Settings),
}

// ============================================================
// Entry Point
// ============================================================

fun main() = application {
    val windowState = rememberWindowState(width = 1280.dp, height = 820.dp)

    Window(
        onCloseRequest = ::exitApplication,
        title = "Metrolist",
        state = windowState,
    ) {
        val scope = rememberCoroutineScope()
        val viewModel = remember { DesktopViewModel(scope) }
        val playerState = remember { PlayerState() }
        var currentScreen by remember { mutableStateOf(Screen.HOME) }
        var searchQuery by remember { mutableStateOf("") }
        var pureBlack by remember { mutableStateOf(true) }
        var themeColor by remember { mutableStateOf(DefaultThemeColor) }

        // Load home on first launch
        LaunchedEffect(Unit) {
            viewModel.loadHome()
        }

        MetrolistTheme(pureBlack = pureBlack, themeColor = themeColor) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f)) {
                        Sidebar(currentScreen, { currentScreen = it }, viewModel)
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.background)
                        ) {
                            when (currentScreen) {
                                Screen.HOME -> HomeScreen(viewModel, playerState)
                                Screen.SEARCH -> SearchScreen(viewModel, searchQuery, { searchQuery = it }, playerState)
                                Screen.LIBRARY -> LibraryScreen(viewModel, playerState)
                                Screen.LIKED -> LikedSongsScreen(viewModel, playerState)
                                Screen.SETTINGS -> SettingsScreen(
                                    viewModel,
                                    pureBlack, { pureBlack = it },
                                    themeColor, { themeColor = it },
                                )
                            }
                        }
                    }

                    if (playerState.currentSong != null) {
                        PlayerBar(playerState)
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

@Composable
fun Sidebar(currentScreen: Screen, onNavigate: (Screen) -> Unit, viewModel: DesktopViewModel) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Spacer(Modifier.height(12.dp))

        Icon(
            Icons.Rounded.MusicNote,
            contentDescription = "Metrolist",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Text(
            "Metrolist",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        Screen.entries.take(3).forEach { screen ->
            val selected = currentScreen == screen
            NavigationRailItem(
                selected = selected,
                onClick = { onNavigate(screen) },
                icon = { Icon(if (selected) screen.activeIcon else screen.icon, screen.label) },
                label = { Text(screen.label, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }

        Spacer(Modifier.weight(1f))

        NavigationRailItem(
            selected = currentScreen == Screen.LIKED,
            onClick = { onNavigate(Screen.LIKED) },
            icon = {
                Icon(Icons.Rounded.Favorite, "Liked Songs",
                    tint = if (currentScreen == Screen.LIKED) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
            },
            label = { Text("Liked", style = MaterialTheme.typography.labelSmall) },
        )

        NavigationRailItem(
            selected = currentScreen == Screen.SETTINGS,
            onClick = { onNavigate(Screen.SETTINGS) },
            icon = { Icon(Icons.Rounded.Settings, "Settings") },
            label = { Text("Settings", style = MaterialTheme.typography.labelSmall) },
        )

        Spacer(Modifier.height(12.dp))
    }
}

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
                ).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                // Icon placeholder based on item type
                Icon(
                    when (item) {
                        is SongItem -> Icons.Rounded.MusicNote
                        is AlbumItem -> Icons.Rounded.Album
                        is ArtistItem -> Icons.Rounded.Person
                        is PlaylistItem -> Icons.Rounded.QueueMusic
                        else -> Icons.Rounded.MusicNote
                    },
                    item.title,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(40.dp),
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

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        OutlinedTextField(
            value = query, onValueChange = onQueryChange,
            placeholder = { Text("Search songs, artists, albums...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            shape = RoundedCornerShape(28.dp), singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Rounded.Close, "Clear") }
                }
            },
        )

        Spacer(Modifier.height(24.dp))

        when {
            query.isBlank() -> {
                Text("Browse All", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                val categories = listOf(
                    "Pop" to Color(0xFFE91E63), "Hip-Hop" to Color(0xFFFF5722),
                    "Rock" to Color(0xFFFF9800), "R&B" to Color(0xFF2196F3),
                    "Electronic" to Color(0xFF00BCD4), "Jazz" to Color(0xFFFFC107),
                    "Classical" to Color(0xFF673AB7), "Indie" to Color(0xFF4CAF50),
                    "K-Pop" to Color(0xFFE91E63).copy(alpha = 0.8f), "Lo-Fi" to Color(0xFF607D8B),
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(categories) { (name, color) ->
                        Surface(
                            modifier = Modifier.height(90.dp),
                            shape = RoundedCornerShape(12.dp), color = color,
                            onClick = { onQueryChange(name) },
                        ) {
                            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomStart) {
                                Text(name, style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold, color = Color.White)
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
                LazyColumn {
                    val searchSongs = viewModel.searchResults.filterIsInstance<SongItem>()
                    items(viewModel.searchResults) { item ->
                        SearchResultRow(item, playerState, searchSongs)
                    }
                }
            }
        }
    }
}

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
                Modifier.size(48.dp).clip(RoundedCornerShape(if (item is ArtistItem) 24.dp else 4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when (item) {
                        is SongItem -> Icons.Rounded.MusicNote
                        is AlbumItem -> Icons.Rounded.Album
                        is ArtistItem -> Icons.Rounded.Person
                        is PlaylistItem -> Icons.Rounded.QueueMusic
                        else -> Icons.Rounded.MusicNote
                    },
                    null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
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
            if (hovered && item is SongItem) {
                IconButton(onClick = { 
                    if (contextSongs.isNotEmpty()) {
                        val queue = contextSongs.map { s -> com.metrolist.desktop.player.PlayerSong(s.id, s.title, s.artists.joinToString { a -> a.name }, s.thumbnail, (s.duration ?: 210) * 1000L) }
                        val index = contextSongs.indexOf(item).coerceAtLeast(0)
                        playerState.playQueue(queue, index)
                    } else {
                        playerState.playSongItem(item)
                    }
                }) {
                    Icon(Icons.Rounded.PlayArrow, "Play")
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
                        Icon(Icons.Rounded.Login, "Login", Modifier.size(18.dp))
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
                        Icon(Icons.Rounded.Login, null, Modifier.size(18.dp))
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

        // Sync
        SettingsSection("Sync & Remote", Icons.Rounded.Devices) {
            var syncEnabled by remember { mutableStateOf(true) }
            ListItem(
                headlineContent = { Text("Cross-device sync") },
                supportingContent = { Text("Control playback from other devices") },
                trailingContent = { Switch(checked = syncEnabled, onCheckedChange = { syncEnabled = it }) }
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ListItem(
                headlineContent = { Text("Relay server") },
                supportingContent = { Text("metrolistsyncrelay-ooae5v0w.b4a.run") },
                trailingContent = {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text("Connected", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            )
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
fun PlayerBar(playerState: PlayerState) {
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
                    Surface(Modifier.size(48.dp), shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (playerState.isLoadingStream) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                            } else {
                                Icon(Icons.Rounded.MusicNote, "art",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
                            }
                        }
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
                    IconButton(onClick = {}, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.FavoriteBorder, "Like",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
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
                    Icon(Icons.Rounded.VolumeUp, "Volume",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Slider(
                        value = playerState.volume, onValueChange = { playerState.volume = it },
                        modifier = Modifier.width(100.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.onSurface,
                            activeTrackColor = MaterialTheme.colorScheme.onSurface,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                    IconButton(onClick = {}, Modifier.size(36.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
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
