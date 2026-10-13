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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.res.painterResource
import com.metrolist.desktop.auth.DesktopPreferences
import com.metrolist.desktop.data.AppUpdater
import com.metrolist.desktop.data.DESKTOP_APP_VERSION
import com.metrolist.desktop.data.UpdateDownloadState
import com.metrolist.desktop.player.LyricLine
import com.metrolist.desktop.player.PlayerState
import com.metrolist.desktop.player.PlayerSong
import com.metrolist.desktop.search.SearchRanker
import com.metrolist.desktop.ui.theme.*
import com.metrolist.desktop.viewmodel.DesktopViewModel
import com.metrolist.innertube.models.*
import com.metrolist.innertube.pages.HomePage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import com.metrolist.desktop.sync.DesktopSyncClient
import com.metrolist.desktop.sync.DesktopListenTogetherClient
import com.metrolist.desktop.media.createDesktopMediaSession
import com.metrolist.desktop.media.toMediaSnapshot
import com.metrolist.desktop.ui.AsyncImage
import com.metrolist.desktop.ui.AddToPlaylistButton
import com.metrolist.desktop.ui.LeftSidebarPanel
import com.metrolist.desktop.ui.LocalPlaylistNameDialog
import com.metrolist.desktop.ui.NavScreen
import com.metrolist.desktop.ui.NowPlayingPanel
import com.metrolist.desktop.ui.TopBar
import com.metrolist.desktop.ui.component.PlayingIndicator
import com.metrolist.desktop.ui.component.FilterChipsRow
import com.metrolist.desktop.ui.component.Material3SettingsGroup
import com.metrolist.desktop.ui.component.Material3SettingsGroupContainer
import com.metrolist.desktop.ui.component.Material3SettingsCard
import com.metrolist.desktop.ui.component.Material3SettingsRow
import com.metrolist.desktop.ui.component.Material3SettingsItem
import com.metrolist.desktop.ui.component.PlaylistCollageThumbnail
import com.metrolist.desktop.ui.component.settingsCardShape
import java.awt.Desktop as AwtDesktop
import java.awt.Dimension
import java.net.URI

private const val DefaultLyricsPanelHeightDp = 360f
private const val MinLyricsPanelHeightDp = 200f
private const val MaxLyricsPanelHeightDp = 600f

private fun normalizeLyricsPanelHeight(panelHeightDp: Float): Float =
    if (panelHeightDp.isFinite()) {
        panelHeightDp.coerceIn(MinLyricsPanelHeightDp, MaxLyricsPanelHeightDp)
    } else {
        DefaultLyricsPanelHeightDp
    }

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
    var windowVisible by remember { mutableStateOf(true) }

    val scope = rememberCoroutineScope()
    val viewModel = remember { DesktopViewModel(scope) }
    val playerState = remember { PlayerState() }
    val listenTogetherClient = remember { DesktopListenTogetherClient(playerState) }

    Window(
        onCloseRequest = {
            // Minimize to tray instead of exiting (tray has "Quit" menu item)
            windowVisible = false
        },
        visible = windowVisible,
        title = "Metrolist",
        icon = painterResource("icon.png"),
        state = windowState,
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown) {
                when (event.key) {
                    Key.MediaPlayPause -> {
                        playerState.togglePlayPause()
                        true
                    }
                    Key.MediaNext -> {
                        playerState.skipNext()
                        true
                    }
                    Key.MediaPrevious -> {
                        playerState.skipPrevious()
                        true
                    }
                    Key.MediaStop -> {
                        playerState.stop()
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        },
    ) {
        // Set minimum window size for proper Hyprland/tiling WM behavior
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(900, 600)
        }

        val focusRequester = remember { FocusRequester() }

        // Request focus for media key event handling when window is visible
        LaunchedEffect(windowVisible) {
            if (windowVisible) {
                kotlinx.coroutines.delay(200)
                focusRequester.requestFocus()
            }
        }

        var cleanupDone by remember { mutableStateOf(false) }

        // ── Windows: show mpv setup dialog if mpv is not available ──
        val isWindows = remember { System.getProperty("os.name").lowercase().contains("win") }
        var showMpvSetupDialog by remember {
            mutableStateOf(isWindows && !com.metrolist.desktop.player.MpvSetupHelper.isMpvAvailable())
        }

        val mediaSession = remember {
            createDesktopMediaSession(
                playerState = playerState,
                awtWindow = window,
                onShowWindow = {
                    windowVisible = true
                    window.toFront()
                    window.requestFocus()
                },
                onExit = {
                    windowVisible = false
                    if (!cleanupDone) {
                        cleanupDone = true
                        exitApplication()
                    }
                },
            )
        }
        val syncClient = remember { com.metrolist.desktop.sync.DesktopSyncClient(
            relayUrl = "wss://metrolistsyncrelay-ooae5v0w.b4a.run/sync",
            playerState = playerState,
        ) }
        // ── Browser-style navigation history ──────────────────────────────────
        val navHistory = remember { com.metrolist.desktop.ui.NavigationHistory(Screen.HOME) }
        // Derive current screen from history — Compose reacts when navHistory.current changes.
        val currentScreen by remember { androidx.compose.runtime.derivedStateOf { navHistory.current.screen } }

        /** Apply a [NavEntry] produced by back/forward: restore playlist data if needed. */
        fun applyNavEntry(entry: com.metrolist.desktop.ui.NavEntry) {
            playerState.showLyrics = false
            when {
                entry.screen == Screen.PLAYLIST && entry.playlistId != null ->
                    viewModel.openPlaylist(entry.playlistId)
                entry.screen == Screen.PLAYLIST && entry.localPlaylistId != null ->
                    viewModel.openLocalPlaylist(entry.localPlaylistId)
                else -> { /* no supplemental action needed */ }
            }
        }

        fun openPlaylistScreen(playlistId: String) {
            playerState.showLyrics = false
            navHistory.navigate(com.metrolist.desktop.ui.NavEntry(Screen.PLAYLIST, playlistId = playlistId))
            viewModel.openPlaylist(playlistId)
        }

        fun openLocalPlaylistScreen(playlistId: String) {
            playerState.showLyrics = false
            navHistory.navigate(com.metrolist.desktop.ui.NavEntry(Screen.PLAYLIST, localPlaylistId = playlistId))
            viewModel.openLocalPlaylist(playlistId)
        }

        var searchQuery by remember { mutableStateOf("") }
        var showCreateLocalPlaylistDialog by remember { mutableStateOf(false) }
        var searchFieldBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        var recentDropdownBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current


        // Load persisted settings from disk
        val savedConfig = remember { DesktopPreferences.load() }
        var pureBlack by remember { mutableStateOf(savedConfig.pureBlack) }
        var themeColor by remember {
            val savedArgb = savedConfig.themeColorArgb
            mutableStateOf(if (savedArgb != null) Color(savedArgb) else DefaultThemeColor)
        }
        var lyricsPanelHeightDp by remember {
            mutableStateOf(
                normalizeLyricsPanelHeight(savedConfig.lyricsPanelHeightDp),
            )
        }
        // Cache size: load saved value and initialize the StreamCache singleton
        var audioCacheSize by remember {
            val size = com.metrolist.desktop.data.CacheSize.fromMb(savedConfig.audioCacheSizeMb)
            com.metrolist.desktop.data.StreamCache.maxCacheSize = size
            mutableStateOf(size)
        }
        // Dynamic color from album art
        var dynamicColor by remember { mutableStateOf(savedConfig.dynamicColorFromAlbumArt) }
        // The user's manually-chosen color (preserved when dynamic color is on)
        var manualThemeColor by remember {
            val savedArgb = savedConfig.themeColorArgb
            mutableStateOf(if (savedArgb != null) Color(savedArgb) else DefaultThemeColor)
        }

        fun saveDesktopConfig() {
            val argb = ((manualThemeColor.alpha * 255).toInt() shl 24) or
                ((manualThemeColor.red * 255).toInt() shl 16) or
                ((manualThemeColor.green * 255).toInt() shl 8) or
                (manualThemeColor.blue * 255).toInt()
            val config = DesktopPreferences.load().copy(
                pureBlack = pureBlack,
                themeColorArgb = argb,
                lyricsPanelHeightDp = normalizeLyricsPanelHeight(lyricsPanelHeightDp),
                audioCacheSizeMb = (audioCacheSize.bytes / 1024 / 1024).toInt(),
                dynamicColorFromAlbumArt = dynamicColor,
            )
            DesktopPreferences.save(config)
        }

        LaunchedEffect(Unit) {
            AppUpdater.startPeriodicCheck()
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

        LaunchedEffect(mediaSession, playerState) {
            snapshotFlow { playerState.toMediaSnapshot() }
                .collect { mediaSession.update(it) }
        }

        // Clean up on close
        DisposableEffect(Unit) {
            onDispose {
                saveDesktopConfig()
                mediaSession.close()
                syncClient.disconnect()
                listenTogetherClient.disconnect()
                playerState.cleanup()
            }
        }

        // Persist settings when they change
        LaunchedEffect(pureBlack, manualThemeColor, dynamicColor) {
            saveDesktopConfig()
        }

        // Dynamic color: extract vibrant colour from album art on each song change
        LaunchedEffect(playerState.currentSong?.id, dynamicColor) {
            if (!dynamicColor) {
                themeColor = manualThemeColor
                return@LaunchedEffect
            }
            val art = playerState.currentSong?.albumArt
            if (art != null) {
                val extracted = com.metrolist.desktop.ui.theme.ThumbnailColorExtractor
                    .extractVibrantColor(art)
                themeColor = extracted ?: manualThemeColor
                println("[DynamicColor] ${playerState.currentSong?.title} → $themeColor")
            } else {
                themeColor = manualThemeColor
            }
        }

        LaunchedEffect(playerState.showLyrics) {
            if (!playerState.showLyrics) {
                saveDesktopConfig()
            }
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
                modifier = Modifier
                    .fillMaxSize()
                    .focusable()
                    .focusRequester(focusRequester)
                    // Handle space after focused children. A text field consumes
                    // its own space key first, so typing cannot toggle playback.
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Spacebar) {
                            playerState.togglePlayPause()
                            true
                        } else {
                            false
                        }
                    }
                    .onFocusEvent { state ->
                        if (state.isFocused) {
                            println("[KeyEvent] Window focused — media keys active")
                        }
                    }
                    // Click-outside-search dismissal: when search is active and user clicks elsewhere,
                    // clear focus and exit search mode. We use PointerEventPass.Initial so we see
                    // the event first but do NOT consume it — other elements still get their clicks.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press && viewModel.isTextInputActive) {
                                    val pos = event.changes.firstOrNull()?.position
                                    if (pos != null) {
                                        val inSearch = searchFieldBounds?.contains(pos) == true
                                        val inDropdown = recentDropdownBounds?.contains(pos) == true
                                        if (!inSearch && !inDropdown) {
                                            // Clearing the child focus alone can leave
                                            // Compose without a focused owner. Explicitly
                                            // return focus to the root so global shortcuts
                                            // become active again after an outside click.
                                            focusManager.clearFocus(force = true)
                                            viewModel.isTextInputActive = false
                                            focusRequester.requestFocus()
                                        }
                                    }
                                }
                            }
                        }
                    },
                color = MaterialTheme.colorScheme.background,
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val totalWidth = maxWidth
                    // Responsive breakpoints
                    val showLeftSidebar  = totalWidth >= 600.dp
                    val showRightPanel   = totalWidth >= 1100.dp && playerState.showRightPanel && (playerState.currentSong != null || playerState.showQueue)
                    val leftWidth        = when {
                        totalWidth >= 1600.dp -> 300.dp  // Full HD maximized (1920x1080)
                        totalWidth >= 1200.dp -> 285.dp  // Standard desktop / 1080p windowed
                        totalWidth >= 900.dp  -> 265.dp  // Medium window
                        else                  -> 245.dp  // Compact window
                    }
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
                                    onNavigate = {
                                        playerState.showLyrics = false
                                        navHistory.navigate(it)
                                    },
                                    viewModel = viewModel,
                                    selectedPlaylistId = viewModel.currentPlaylistId,
                                    selectedLocalPlaylistId = viewModel.currentLocalPlaylistId,
                                    onPlaylistSelected = { openPlaylistScreen(it.id) },
                                    onLocalPlaylistSelected = { openLocalPlaylistScreen(it.id) },
                                    onCreatePlaylist = {
                                        playerState.showLyrics = false
                                        navHistory.navigate(Screen.PLAYLIST)
                                        showCreateLocalPlaylistDialog = true
                                    },
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
                                    onQueryChange = {
                                        searchQuery = it
                                        if (it.isNotBlank()) playerState.showLyrics = false
                                    },
                                    onSearchFocused = {
                                        playerState.showLyrics = false
                                        navHistory.navigate(Screen.SEARCH)
                                    },
                                    viewModel = viewModel,
                                    canGoBack    = navHistory.canGoBack,
                                    canGoForward = navHistory.canGoForward,
                                    onBack = {
                                        navHistory.back()?.let { applyNavEntry(it) }
                                    },
                                    onForward = {
                                        navHistory.forward()?.let { applyNavEntry(it) }
                                    },
                                    onRecentSearchPlay = { entry ->
                                        playerState.showLyrics = false
                                        playerState.playSong(
                                            com.metrolist.desktop.player.PlayerSong(
                                                id         = entry.videoId,
                                                title      = entry.title,
                                                artist     = entry.artistName,
                                                albumArt   = entry.thumbnailUrl,
                                                durationMs = 0L,
                                            )
                                        )
                                        // Bump to top of recent list
                                        viewModel.addRecentSearch(entry)
                                    },
                                    onSearchBoundsChanged = { searchFieldBounds = it },
                                    onDropdownBoundsChanged = { recentDropdownBounds = it },
                                )
                                Box(modifier = Modifier.weight(1f)) {
                                    if (playerState.showLyrics && playerState.currentSong != null) {
                                        SpotifyLyricsScreen(
                                            playerState = playerState,
                                            themeColor = themeColor,
                                            onClose = { playerState.showLyrics = false },
                                        )
                                    } else {
                                        when (currentScreen) {
                                            Screen.HOME      -> HomeScreen(
                                                viewModel,
                                                playerState,
                                                onOpenPlaylist = { openPlaylistScreen(it.id) },
                                            )
                                            Screen.SEARCH    -> SearchScreen(
                                                viewModel,
                                                searchQuery,
                                                { searchQuery = it },
                                                playerState,
                                                onOpenPlaylist = { openPlaylistScreen(it.id) },
                                            )
                                            Screen.LIBRARY   -> LibraryScreen(viewModel, playerState)
                                            Screen.PLAYLIST  -> PlaylistScreen(
                                                viewModel = viewModel,
                                                playerState = playerState,
                                                onRetry = viewModel::retryCurrentPlaylist,
                                                onCreatePlaylist = { showCreateLocalPlaylistDialog = true },
                                            )
                                            Screen.LIKED     -> LikedSongsScreen(viewModel, playerState)
                                            Screen.DOWNLOADS -> DownloadsScreen(playerState, viewModel)
                                            Screen.CACHE     -> CachedScreen(playerState, viewModel)
                                            Screen.LISTEN_TOGETHER -> ListenTogetherScreen(listenTogetherClient)
                                            Screen.SETTINGS  -> SettingsScreen(
                                                viewModel,
                                                pureBlack, { pureBlack = it },
                                                manualThemeColor, { newColor ->
                                                    manualThemeColor = newColor
                                                    if (!dynamicColor) themeColor = newColor
                                                },
                                                syncClient,
                                                audioCacheSize, { newSize ->
                                                    audioCacheSize = newSize
                                                    com.metrolist.desktop.data.StreamCache.applyLimit(newSize)
                                                    saveDesktopConfig()
                                                },
                                                dynamicColor, { dynamicColor = it },
                                            )
                                        }
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

                        if (playerState.currentSong != null) {
                            PlayerBar(playerState, syncClient, viewModel)
                        }
                    }
                }
            }

            if (showCreateLocalPlaylistDialog) {
                LocalPlaylistNameDialog(
                    title = "Create playlist",
                    initialValue = "",
                    confirmLabel = "Create",
                    onDismiss = { showCreateLocalPlaylistDialog = false },
                    onConfirm = { name ->
                        val playlist = viewModel.createLocalPlaylist(name)
                        showCreateLocalPlaylistDialog = false
                        playlist?.let { openLocalPlaylistScreen(it.id) }
                    },
                    viewModel = viewModel,
                )
            }

            // Show mpv setup dialog on Windows when mpv is missing.
            // After a successful download the dialog dismisses itself and playback works normally.
            if (showMpvSetupDialog) {
                com.metrolist.desktop.player.MpvSetupDialog(
                    onDismiss = { showMpvSetupDialog = false },
                    onMpvReady = { showMpvSetupDialog = false },
                )
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
fun HomeScreen(
    viewModel: DesktopViewModel,
    playerState: PlayerState,
    onOpenPlaylist: (PlaylistItem) -> Unit,
) {
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
                    HomeSectionView(
                        section = section,
                        playerState = playerState,
                        onOpenPlaylist = onOpenPlaylist,
                        viewModel = viewModel,
                    )
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeSectionView(
    section: HomePage.Section,
    playerState: PlayerState,
    onOpenPlaylist: (PlaylistItem) -> Unit,
    viewModel: DesktopViewModel,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val canScrollForward by remember { derivedStateOf { listState.canScrollForward } }
    val canScrollBackward by remember { derivedStateOf { listState.canScrollBackward } }

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

        // Items carousel container with overlay navigation buttons
        Box(
            modifier = Modifier.fillMaxWidth(),
        ) {
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .onPointerEvent(PointerEventType.Scroll) { event ->
                        val change = event.changes.firstOrNull() ?: return@onPointerEvent
                        val deltaX = change.scrollDelta.x
                        val deltaY = change.scrollDelta.y
                        val horizontalDelta = when {
                            deltaX != 0f -> deltaX
                            event.keyboardModifiers.isShiftPressed && deltaY != 0f -> deltaY
                            else -> 0f
                        }
                        if (horizontalDelta != 0f) {
                            coroutineScope.launch {
                                listState.scrollBy(horizontalDelta * 80f)
                            }
                            change.consume()
                        }
                    },
            ) {
                val songs = section.items.filterIsInstance<SongItem>()
                items(section.items) { item ->
                    YTItemCard(
                        item = item,
                        playerState = playerState,
                        contextSongs = songs,
                        onOpenPlaylist = onOpenPlaylist,
                        onSongPlayed = { song ->
                            viewModel.addRecentSearch(
                                com.metrolist.desktop.auth.RecentSearchEntry(
                                    videoId = song.id,
                                    title = song.title,
                                    artistName = song.artists.joinToString { it.name },
                                    thumbnailUrl = song.thumbnail,
                                )
                            )
                        },
                    )
                }
            }

            // Left navigation button (Previous)
            androidx.compose.animation.AnimatedVisibility(
                visible = canScrollBackward,
                enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.8f, animationSpec = tween(180)),
                exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.8f, animationSpec = tween(180)),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 66.dp),
            ) {
                CarouselNavButton(
                    icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = "Previous",
                    onClick = {
                        coroutineScope.launch {
                            val viewportWidth = listState.layoutInfo.viewportSize.width.toFloat()
                            val scrollAmount = if (viewportWidth > 0f) viewportWidth * 0.75f else 540f
                            listState.animateScrollBy(
                                -scrollAmount,
                                animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)
                            )
                        }
                    },
                )
            }

            // Right navigation button (Next)
            androidx.compose.animation.AnimatedVisibility(
                visible = canScrollForward,
                enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.8f, animationSpec = tween(180)),
                exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.8f, animationSpec = tween(180)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 12.dp, top = 66.dp),
            ) {
                CarouselNavButton(
                    icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = "Next",
                    onClick = {
                        coroutineScope.launch {
                            val viewportWidth = listState.layoutInfo.viewportSize.width.toFloat()
                            val scrollAmount = if (viewportWidth > 0f) viewportWidth * 0.75f else 540f
                            listState.animateScrollBy(
                                scrollAmount,
                                animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)
                            )
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CarouselNavButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isHovered by remember { mutableStateOf(false) }
    val elevation by animateDpAsState(if (isHovered) 8.dp else 4.dp, animationSpec = tween(150))
    val scale by animateFloatAsState(if (isHovered) 1.08f else 1.0f, animationSpec = tween(150))
    val bgColor by animateColorAsState(
        if (isHovered)
            MaterialTheme.colorScheme.surfaceContainerHighest
        else
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        animationSpec = tween(150)
    )

    PlayerBarTooltip(contentDescription) {
        Surface(
            onClick = onClick,
            modifier = modifier
                .size(40.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .pointerHoverIcon(PointerIcon.Hand)
                .onPointerEvent(PointerEventType.Enter) { isHovered = true }
                .onPointerEvent(PointerEventType.Exit) { isHovered = false },
            shape = CircleShape,
            color = bgColor,
            shadowElevation = elevation,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun YTItemCard(
    item: YTItem,
    playerState: PlayerState,
    contextSongs: List<SongItem> = emptyList(),
    onOpenPlaylist: (PlaylistItem) -> Unit = {},
    onSongPlayed: ((SongItem) -> Unit)? = null,
) {
    var hovered by remember { mutableStateOf(false) }
    val thumbnailUrl = item.thumbnail
    val isPlaying = item is SongItem && playerState.currentSong?.id == item.id && playerState.isPlaying

    val cardBg by animateColorAsState(
        targetValue = when {
            isPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            hovered -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)
            else -> Color.Transparent
        },
        animationSpec = tween(150),
    )

    Surface(
        modifier = Modifier.width(172.dp),
        shape = RoundedCornerShape(14.dp),
        color = cardBg,
        onClick = {
            when (item) {
                is SongItem -> {
                    if (contextSongs.isNotEmpty()) {
                        val queue = contextSongs.map { s -> com.metrolist.desktop.player.PlayerSong(s.id, s.title, s.artists.joinToString { a -> a.name }, s.thumbnail, (s.duration ?: 210) * 1000L) }
                        val index = contextSongs.indexOf(item).coerceAtLeast(0)
                        playerState.playQueue(queue, index)
                    } else {
                        playerState.playSongItem(item)
                    }
                    onSongPlayed?.invoke(item)
                }
                is PlaylistItem -> onOpenPlaylist(item)
                else -> Unit
            }
        },
    ) {
        Column(
            modifier = Modifier
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .padding(10.dp),
        ) {
            // Thumbnail
            val isArtist = item is ArtistItem
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(if (isArtist) CircleShape else RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
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
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.size(42.dp),
                            )
                        }
                    },
                )

                // Playing overlay
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        PlayingIndicator(
                            color = Color.White,
                            modifier = Modifier.height(20.dp),
                        )
                    }
                } else if (hovered && item is SongItem) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .size(42.dp)
                            .shadow(6.dp, CircleShape)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable {
                                if (contextSongs.isNotEmpty()) {
                                    val queue = contextSongs.map { s -> com.metrolist.desktop.player.PlayerSong(s.id, s.title, s.artists.joinToString { a -> a.name }, s.thumbnail, (s.duration ?: 210) * 1000L) }
                                    val index = contextSongs.indexOf(item).coerceAtLeast(0)
                                    playerState.playQueue(queue, index)
                                } else {
                                    playerState.playSongItem(item)
                                }
                                onSongPlayed?.invoke(item)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.PlayArrow, "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Subtitle based on type
            val subtitle = when (item) {
                is SongItem -> item.artists.joinToString { it.name }
                is AlbumItem -> item.artists?.joinToString { it.name } ?: "Album"
                is ArtistItem -> "Artist"
                is PlaylistItem -> item.author?.name ?: "Playlist"
                else -> ""
            }
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ============================================================
// Search Screen — Real YouTube Music API
// ============================================================

@Composable
fun SearchScreen(
    viewModel: DesktopViewModel,
    query: String,
    onQueryChange: (String) -> Unit = {},
    playerState: PlayerState,
    onOpenPlaylist: (PlaylistItem) -> Unit,
) {
    if (query.isBlank()) {
        HomeScreen(
            viewModel = viewModel,
            playerState = playerState,
            onOpenPlaylist = onOpenPlaylist,
        )
        return
    }

    // Debounced search
    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            delay(400) // debounce
            viewModel.search(query)
        }
    }

    // Search results
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Spacer(Modifier.height(8.dp))

        when {
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
                var selectedFilter by remember(query) { mutableStateOf<String?>(null) }

                Text("Results for \"$query\"", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))

                FilterChipsRow(
                    chips = listOf("Songs", "Albums", "Artists", "Playlists"),
                    selectedChip = selectedFilter,
                    onChipSelected = { selectedFilter = it },
                    chipLabel = { it },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )

                val allResults = viewModel.searchResults
                val topResult  = SearchRanker.pickTopResult(allResults, query, viewModel.likedSongIds)
                val restResults = allResults.filter { it !== topResult }
                val searchSongs = allResults.filterIsInstance<SongItem>()

                val filteredResults = when (selectedFilter) {
                    "Songs" -> restResults.filterIsInstance<SongItem>()
                    "Albums" -> restResults.filterIsInstance<AlbumItem>()
                    "Artists" -> restResults.filterIsInstance<ArtistItem>()
                    "Playlists" -> restResults.filterIsInstance<PlaylistItem>()
                    else -> restResults
                }

                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val isWide = maxWidth >= 800.dp
                    if (isWide) {
                        // ── Wide layout: Top Result card left, list right ──
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            // Left: Top Result card
                            if (topResult != null && selectedFilter == null) {
                                TopResultCard(
                                    item = topResult,
                                    playerState = playerState,
                                    viewModel = viewModel,
                                    searchSongs = searchSongs,
                                    onOpenPlaylist = onOpenPlaylist,
                                    modifier = Modifier.width(260.dp).fillMaxHeight(),
                                )
                            }
                            LazyColumn(Modifier.weight(1f)) {
                                items(filteredResults, key = { it.id }) { item ->
                                    SearchResultRow(item, playerState, viewModel, searchSongs, onOpenPlaylist)
                                }
                            }
                        }
                    } else {
                        // ── Narrow layout: stacked ──
                        LazyColumn(Modifier.fillMaxSize()) {
                            if (topResult != null && selectedFilter == null) {
                                item {
                                    TopResultCard(
                                        item = topResult,
                                        playerState = playerState,
                                        viewModel = viewModel,
                                        searchSongs = searchSongs,
                                        onOpenPlaylist = onOpenPlaylist,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(16.dp))
                                }
                            }
                            items(filteredResults, key = { it.id }) { item ->
                                SearchResultRow(item, playerState, viewModel, searchSongs, onOpenPlaylist)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Top Result Card ────────────────────────────────────────────────────────

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TopResultCard(
    item: YTItem,
    playerState: PlayerState,
    viewModel: DesktopViewModel,
    searchSongs: List<SongItem>,
    onOpenPlaylist: (PlaylistItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var hovered by remember { mutableStateOf(false) }

    val typeLabel = when (item) {
        is SongItem     -> if (item.isVideoSong) "Video" else "Song"
        is ArtistItem   -> "Artist"
        is AlbumItem    -> "Album"
        is PlaylistItem -> "Playlist"
        else            -> "Result"
    }
    val subtitle = when (item) {
        is SongItem   -> item.artists.joinToString { it.name }
        is ArtistItem -> "Artist"
        is AlbumItem  -> item.artists?.joinToString { it.name } ?: ""
        else          -> ""
    }

    // Download / cache state (songs only)
    val dlState    = if (item is SongItem) com.metrolist.desktop.data.DownloadManager.downloads[item.id] else null
    val dlProgress = if (item is SongItem) com.metrolist.desktop.data.DownloadManager.progress[item.id] ?: 0f else 0f
    val isDownloaded = dlState == com.metrolist.desktop.data.DownloadState.DONE
    val isDownloading = dlState == com.metrolist.desktop.data.DownloadState.DOWNLOADING ||
                        dlState == com.metrolist.desktop.data.DownloadState.QUEUED
    val isCached = !isDownloaded &&
        com.metrolist.desktop.data.StreamCache.getCachedPath(item.id) != null

    val borderColor by animateColorAsState(
        if (hovered) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.Transparent,
        animationSpec = tween(200), label = "border",
    )

    Column(
        modifier = modifier
            .then(Modifier.clip(RoundedCornerShape(16.dp)))
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
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
                        viewModel.addRecentSearch(
                            com.metrolist.desktop.auth.RecentSearchEntry(
                                videoId = item.id,
                                title = item.title,
                                artistName = item.artists.joinToString { it.name },
                                thumbnailUrl = item.thumbnail,
                            )
                        )
                    }
                    is PlaylistItem -> onOpenPlaylist(item)
                    else -> Unit
                }
            }
            .padding(20.dp),
    ) {
        // ── Thumbnail with hover-overlay play button ──
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
            // Hover overlay — use alpha animation (valid inside BoxScope)
            if (item is SongItem) {
                val overlayAlpha by animateFloatAsState(
                    if (hovered) 1f else 0f, tween(150), label = "overlay"
                )
                Box(
                    Modifier.fillMaxSize()
                        .alpha(overlayAlpha)
                        .background(Color.Black.copy(alpha = 0.38f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(52.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PlayArrow, "Play",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(30.dp))
                        }
                    }
                }
            }

            // Downloaded / Cached badge on thumbnail corner
            if (isDownloaded || isCached || isDownloading) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                isDownloaded  -> MaterialTheme.colorScheme.tertiary
                                isDownloading -> MaterialTheme.colorScheme.secondary
                                else          -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)
                            }
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        when {
                            isDownloaded -> {
                                Icon(Icons.Rounded.DownloadDone, null, Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onTertiary)
                                Text("Saved", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiary)
                            }
                            isDownloading -> {
                                CircularProgressIndicator(
                                    progress = { dlProgress },
                                    modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp,
                                    color = MaterialTheme.colorScheme.onSecondary,
                                )
                                Text("${(dlProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondary)
                            }
                            isCached -> {
                                Icon(Icons.Rounded.CloudDownload, null, Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSecondary)
                                Text("Cached", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondary)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── Type badge + offline indicators row ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    typeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            if (isDownloaded) {
                Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(Icons.Rounded.WifiOff, null, Modifier.size(10.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text("Available offline", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            } else if (isCached) {
                Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(Icons.Rounded.CloudDone, null, Modifier.size(10.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text("Cached", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Title ──
        Text(
            item.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // ── Artist + duration row ──
        if (subtitle.isNotBlank() || (item is SongItem && item.duration != null)) {
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (item is SongItem) {
                    item.duration?.let { dur ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                com.metrolist.desktop.player.PlayerState.formatTime(dur * 1000L),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }

        // ── Download progress bar (while downloading) ──
        if (isDownloading) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { dlProgress },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }

        // ── Action buttons (songs only) ──
        if (item is SongItem) {
            Spacer(Modifier.height(14.dp))

            var showAddToPlaylistDialog by remember { mutableStateOf(false) }
            val isThisSongPlaying = playerState.currentSong?.id == item.id && playerState.isPlaying

            if (showAddToPlaylistDialog) {
                com.metrolist.desktop.ui.AddToPlaylistDialog(
                    song = item.toPlayerSong(),
                    viewModel = viewModel,
                    onDismiss = { showAddToPlaylistDialog = false },
                )
            }

            // ── Row 1: Play/Pause + Add to Playlist (equal weight) ──
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = {
                        if (isThisSongPlaying) {
                            playerState.togglePlayPause()
                        } else {
                            val idx = searchSongs.indexOf(item).coerceAtLeast(0)
                            val queue = searchSongs.map { s ->
                                com.metrolist.desktop.player.PlayerSong(
                                    s.id, s.title,
                                    s.artists.joinToString { a -> a.name },
                                    s.thumbnail, (s.duration ?: 210) * 1000L,
                                )
                            }
                            playerState.playQueue(queue, idx)
                            viewModel.addRecentSearch(
                                com.metrolist.desktop.auth.RecentSearchEntry(
                                    videoId = item.id,
                                    title = item.title,
                                    artistName = item.artists.joinToString { it.name },
                                    thumbnailUrl = item.thumbnail,
                                )
                            )
                        }
                    },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f).height(40.dp),
                ) {
                    Icon(
                        if (isThisSongPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (isThisSongPlaying) "Pause" else "Play",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                OutlinedButton(
                    onClick = { showAddToPlaylistDialog = true },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f).height(40.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add", style = MaterialTheme.typography.labelLarge)
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Row 2: Queue + Save (compact secondary row) ──
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = { playerState.addToQueue(item) },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f).height(36.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Queue", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = {
                        if (dlState == null || dlState == com.metrolist.desktop.data.DownloadState.ERROR) {
                            com.metrolist.desktop.data.DownloadManager.downloadSong(
                                com.metrolist.desktop.player.PlayerSong(
                                    id = item.id,
                                    title = item.title,
                                    artist = item.artists.joinToString { a -> a.name },
                                    albumArt = item.thumbnail,
                                    durationMs = (item.duration ?: 210) * 1000L,
                                )
                            )
                        }
                    },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f).height(36.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = if (isDownloaded) ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                    ) else ButtonDefaults.outlinedButtonColors(),
                ) {
                    when {
                        isDownloaded -> {
                            Icon(Icons.Rounded.DownloadDone, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Saved", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                        }
                        isDownloading -> {
                            CircularProgressIndicator(progress = { dlProgress }, modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(5.dp))
                            Text("${(dlProgress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        }
                        else -> {
                            Icon(Icons.Rounded.Download, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Save", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // ── UP NEXT queue preview ──
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(Modifier.height(12.dp))

            val isCurrentSong = playerState.currentSong?.id == item.id
            val upNextTracks = if (isCurrentSong && playerState.queue.isNotEmpty()) {
                playerState.queue.drop(playerState.queueIndex + 1).take(6)
            } else {
                searchSongs.filter { it.id != item.id }.take(6).map { s ->
                    com.metrolist.desktop.player.PlayerSong(
                        s.id, s.title,
                        s.artists.joinToString { a -> a.name },
                        s.thumbnail,
                        (s.duration ?: 210) * 1000L,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "UP NEXT",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    modifier = Modifier.weight(1f),
                )
                if (upNextTracks.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(
                            "${upNextTracks.size}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            if (upNextTracks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Queue is empty",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    upNextTracks.forEach { track ->
                        TopResultQueueRow(
                            track = track,
                            playerState = playerState,
                            searchSongs = searchSongs,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TopResultQueueRow(
    track: com.metrolist.desktop.player.PlayerSong,
    playerState: PlayerState,
    searchSongs: List<SongItem>,
) {
    var rowHovered by remember { mutableStateOf(false) }
    val isPlaying = playerState.currentSong?.id == track.id && playerState.isPlaying

    val rowBg by animateColorAsState(
        targetValue = when {
            isPlaying   -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            rowHovered  -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f)
            else        -> Color.Transparent
        },
        animationSpec = tween(150),
        label = "queueRowBg",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(rowBg)
            .onPointerEvent(PointerEventType.Enter) { rowHovered = true }
            .onPointerEvent(PointerEventType.Exit)  { rowHovered = false }
            .clickable {
                val songItem = searchSongs.firstOrNull { it.id == track.id }
                if (songItem != null) {
                    val idx = searchSongs.indexOf(songItem).coerceAtLeast(0)
                    playerState.playQueue(
                        searchSongs.map { s ->
                            com.metrolist.desktop.player.PlayerSong(
                                s.id, s.title,
                                s.artists.joinToString { a -> a.name },
                                s.thumbnail,
                                (s.duration ?: 210) * 1000L,
                            )
                        },
                        idx,
                    )
                }
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Thumbnail with hover/playing overlay
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (track.albumArt != null) {
                AsyncImage(
                    url = track.albumArt,
                    contentDescription = track.title,
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
            val overlayAlpha by animateFloatAsState(
                if (rowHovered || isPlaying) 1f else 0f,
                animationSpec = tween(120),
                label = "qRowOverlay",
            )
            if (overlayAlpha > 0f) {
                Box(
                    Modifier.fillMaxSize().alpha(overlayAlpha).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isPlaying) {
                        PlayingIndicator(color = Color.White, modifier = Modifier.height(14.dp))
                    } else {
                        Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Title + artist
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Duration
        Text(
            remember(track.durationMs) { com.metrolist.desktop.player.PlayerState.formatTime(track.durationMs) },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        )
    }
}



@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SearchResultRow(
    item: YTItem,
    playerState: PlayerState,
    viewModel: DesktopViewModel,
    contextSongs: List<SongItem> = emptyList(),
    onOpenPlaylist: (PlaylistItem) -> Unit = {},
) {
    var hovered by remember { mutableStateOf(false) }
    // Hoisted outside hover block so dialog survives mouse-leave events
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    val songForPlaylist: com.metrolist.desktop.player.PlayerSong? = if (item is SongItem) item.toPlayerSong() else null

    if (showAddToPlaylistDialog && songForPlaylist != null) {
        com.metrolist.desktop.ui.AddToPlaylistDialog(
            song = songForPlaylist,
            viewModel = viewModel,
            onDismiss = { showAddToPlaylistDialog = false },
        )
    }

    val isPlaying = item is SongItem && playerState.currentSong?.id == item.id && playerState.isPlaying
    val animatedBg by animateColorAsState(
        targetValue = when {
            isPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            hovered -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> Color.Transparent
        },
        animationSpec = tween(150),
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = animatedBg,
        onClick = {
            when (item) {
                is SongItem -> {
                    if (contextSongs.isNotEmpty()) {
                        val queue = contextSongs.map { s -> com.metrolist.desktop.player.PlayerSong(s.id, s.title, s.artists.joinToString { a -> a.name }, s.thumbnail, (s.duration ?: 210) * 1000L) }
                        val index = contextSongs.indexOf(item).coerceAtLeast(0)
                        playerState.playQueue(queue, index)
                    } else {
                        playerState.playSongItem(item)
                    }
                    viewModel.addRecentSearch(
                        com.metrolist.desktop.auth.RecentSearchEntry(
                            videoId = item.id,
                            title = item.title,
                            artistName = item.artists.joinToString { it.name },
                            thumbnailUrl = item.thumbnail,
                        )
                    )
                }
                is PlaylistItem -> onOpenPlaylist(item)
                else -> Unit
            }
        },
    ) {
        Row(
            modifier = Modifier
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail
            Box(
                Modifier.size(48.dp).clip(if (item is ArtistItem) CircleShape else RoundedCornerShape(8.dp)),
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
            Spacer(Modifier.width(14.dp))
            // Title + subtitle
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                    color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                val playlistSong = item.toPlayerSong()
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
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Add to playlist") } },
                    state = rememberTooltipState(),
                ) {
                    IconButton(
                        onClick = { showAddToPlaylistDialog = true },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Rounded.AddCircleOutline, "Add to playlist",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                // Download button (with tooltip)
                val dlState = com.metrolist.desktop.data.DownloadManager.downloads[item.id]
                val dlProgress = com.metrolist.desktop.data.DownloadManager.progress[item.id] ?: 0f
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text(when (dlState) {
                        com.metrolist.desktop.data.DownloadState.DONE -> "Downloaded"
                        com.metrolist.desktop.data.DownloadState.DOWNLOADING -> "Downloading… ${(dlProgress * 100).toInt()}%"
                        com.metrolist.desktop.data.DownloadState.QUEUED -> "Queued"
                        else -> "Download"
                    }) } },
                    state = rememberTooltipState(),
                ) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        when (dlState) {
                            com.metrolist.desktop.data.DownloadState.DONE -> IconButton(
                                onClick = {},
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.Rounded.DownloadDone, "Downloaded",
                                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                            com.metrolist.desktop.data.DownloadState.DOWNLOADING,
                            com.metrolist.desktop.data.DownloadState.QUEUED -> {
                                CircularProgressIndicator(
                                    progress = { dlProgress },
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            else -> IconButton(
                                onClick = {
                                    val song = com.metrolist.desktop.player.PlayerSong(
                                        id = item.id, title = item.title,
                                        artist = item.artists.joinToString { a -> a.name },
                                        albumArt = item.thumbnail,
                                        durationMs = (item.duration ?: 210) * 1000L,
                                    )
                                    com.metrolist.desktop.data.DownloadManager.downloadSong(song)
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.Rounded.Download, "Download",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                        }
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
// Playlist Screen
// ============================================================

private fun SongItem.toPlayerSong() = com.metrolist.desktop.player.PlayerSong(
    id = id,
    title = title,
    artist = artists.joinToString { it.name },
    albumArt = thumbnail,
    durationMs = (duration ?: 210) * 1000L,
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LocalPlaylistSongRow(
    song: com.metrolist.desktop.player.PlayerSong,
    index: Int,
    playlistSongs: List<com.metrolist.desktop.player.PlayerSong>,
    playerState: PlayerState,
    onRemove: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val dlState = com.metrolist.desktop.data.DownloadManager.downloads[song.id]
    val dlProgress = com.metrolist.desktop.data.DownloadManager.progress[song.id] ?: 0f
    val isPlaying = playerState.currentSong?.id == song.id && playerState.isPlaying

    val animatedBg by animateColorAsState(
        targetValue = when {
            isPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            hovered -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> Color.Transparent
        },
        animationSpec = tween(150),
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = animatedBg,
        onClick = { playerState.playQueue(playlistSongs, index) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.bodySmall,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.width(28.dp),
            )
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    url = song.albumArt,
                    contentDescription = song.title,
                    modifier = Modifier.fillMaxSize(),
                    placeholder = {
                        Icon(
                            Icons.Rounded.MusicNote,
                            null,
                            Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        )
                    },
                )

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
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                    color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                PlayerState.formatTime(song.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(Modifier.width(8.dp))
            AnimatedVisibility(hovered) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { playerState.playQueue(playlistSongs, index) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, "Play", modifier = Modifier.size(20.dp))
                    }
                    IconButton(
                        onClick = {
                            if (dlState == null || dlState == com.metrolist.desktop.data.DownloadState.ERROR) {
                                com.metrolist.desktop.data.DownloadManager.downloadSong(song)
                            }
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        when (dlState) {
                            com.metrolist.desktop.data.DownloadState.DONE ->
                                Icon(Icons.Rounded.DownloadDone, "Downloaded", modifier = Modifier.size(18.dp))
                            com.metrolist.desktop.data.DownloadState.DOWNLOADING,
                            com.metrolist.desktop.data.DownloadState.QUEUED ->
                                CircularProgressIndicator(
                                    progress = { dlProgress },
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            else ->
                                Icon(Icons.Rounded.Download, "Download", modifier = Modifier.size(18.dp))
                        }
                    }
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Delete,
                            "Remove from playlist",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistScreen(
    viewModel: DesktopViewModel,
    playerState: PlayerState,
    onRetry: () -> Unit,
    onCreatePlaylist: () -> Unit,
) {
    val localPlaylist = viewModel.currentLocalPlaylist
    val playlistPage = viewModel.currentPlaylistPage
    val onlinePlaylist = playlistPage?.playlist
    var showRenameDialog by remember(localPlaylist?.id) { mutableStateOf(false) }
    var showDeleteDialog by remember(localPlaylist?.id) { mutableStateOf(false) }

    when {
        localPlaylist != null -> {
            val songs = localPlaylist.songs.map { it.toPlayerSong() }

            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                                    MaterialTheme.colorScheme.background,
                                )
                            )
                        )
                        .padding(32.dp),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        PlaylistCollageThumbnail(
                            playlist = localPlaylist,
                            modifier = Modifier.size(140.dp),
                            shape = RoundedCornerShape(18.dp),
                            iconSize = 52.dp,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Your playlist",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                localPlaylist.name,
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "${songs.size} song${if (songs.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Button(
                                    onClick = { if (songs.isNotEmpty()) playerState.playQueue(songs, 0) },
                                    enabled = songs.isNotEmpty(),
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, "Play", Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Play")
                                }
                                OutlinedButton(
                                    onClick = { if (songs.isNotEmpty()) playerState.playQueue(songs.shuffled(), 0) },
                                    enabled = songs.isNotEmpty(),
                                ) {
                                    Icon(Icons.Rounded.Shuffle, "Shuffle", Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Shuffle")
                                }
                                OutlinedButton(
                                    onClick = { com.metrolist.desktop.data.DownloadManager.downloadSongs(songs) },
                                    enabled = songs.isNotEmpty(),
                                ) {
                                    Icon(Icons.Rounded.Download, "Download all", Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Download all")
                                }
                                OutlinedButton(onClick = { showRenameDialog = true }) {
                                    Icon(Icons.Rounded.Edit, "Rename", Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Rename")
                                }
                                OutlinedButton(onClick = { showDeleteDialog = true }) {
                                    Icon(Icons.Rounded.Delete, "Delete", Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Delete")
                                }
                            }
                        }
                    }
                }

                if (songs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Rounded.MusicOff,
                                "No songs",
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            )
                            Spacer(Modifier.height(16.dp))
                            Text("This playlist is empty", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Add songs from search, cache, or downloads.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(localPlaylist.songs, key = { _, song -> song.id }) { index, song ->
                            LocalPlaylistSongRow(
                                song = song.toPlayerSong(),
                                index = index,
                                playlistSongs = songs,
                                playerState = playerState,
                                onRemove = { viewModel.removeSongFromLocalPlaylist(localPlaylist.id, song.id) },
                            )
                        }
                    }
                }
            }

            if (showRenameDialog) {
                LocalPlaylistNameDialog(
                    title = "Rename playlist",
                    initialValue = localPlaylist.name,
                    confirmLabel = "Save",
                    onDismiss = { showRenameDialog = false },
                    onConfirm = { newName ->
                        viewModel.renameLocalPlaylist(localPlaylist.id, newName)
                        showRenameDialog = false
                    },
                    viewModel = viewModel,
                )
            }

            if (showDeleteDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(28.dp),
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .widthIn(min = 340.dp, max = 420.dp)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(28.dp),
                        ),
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primaryContainer,
                                            MaterialTheme.colorScheme.surfaceContainerHighest,
                                        )
                                    )
                                )
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(16.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    },
                    title = {
                        Text(
                            text = "Delete playlist?",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    text = {
                        Text(
                            text = "Delete \"${localPlaylist.name}\" and remove its saved song list?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.deleteLocalPlaylist(localPlaylist.id)
                                showDeleteDialog = false
                            },
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            modifier = Modifier.height(40.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Delete", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showDeleteDialog = false },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.height(40.dp),
                        ) {
                            Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                )
            }
        }
        viewModel.playlistLoading && playlistPage == null -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Loading playlist...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        viewModel.playlistError != null && playlistPage == null -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.CloudOff,
                        "Playlist error",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Failed to load playlist", style = MaterialTheme.typography.titleMedium)
                    Text(
                        viewModel.playlistError ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = onRetry) {
                        Icon(Icons.Rounded.Refresh, "Retry", Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Retry")
                    }
                }
            }
        }
        playlistPage == null || onlinePlaylist == null -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.AutoMirrored.Rounded.QueueMusic,
                        "Playlist",
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Open a playlist to view it", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Playlist pages work without signing in.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onCreatePlaylist) {
                        Icon(Icons.Rounded.Add, "Create", Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Create playlist")
                    }
                }
            }
        }
        else -> {
            val songs = playlistPage.songs
            val subtitle = listOfNotNull(
                onlinePlaylist.author?.name,
                onlinePlaylist.songCountText,
            ).joinToString(" · ")
            val playerSongs = songs.map { it.toPlayerSong() }

            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                                    MaterialTheme.colorScheme.background,
                                )
                            )
                        )
                        .padding(32.dp),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(140.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            AsyncImage(
                                url = onlinePlaylist.thumbnail,
                                contentDescription = onlinePlaylist.title,
                                modifier = Modifier.fillMaxSize(),
                                placeholder = {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.QueueMusic,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                        modifier = Modifier.size(52.dp),
                                    )
                                },
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Playlist",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                onlinePlaylist.title,
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            if (subtitle.isNotBlank()) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Button(
                                    onClick = { if (playerSongs.isNotEmpty()) playerState.playQueue(playerSongs, 0) },
                                    enabled = songs.isNotEmpty(),
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, "Play", Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Play")
                                }
                                OutlinedButton(
                                    onClick = { if (playerSongs.isNotEmpty()) playerState.playQueue(playerSongs.shuffled(), 0) },
                                    enabled = songs.isNotEmpty(),
                                ) {
                                    Icon(Icons.Rounded.Shuffle, "Shuffle", Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Shuffle")
                                }
                                OutlinedButton(
                                    onClick = { com.metrolist.desktop.data.DownloadManager.downloadSongs(playerSongs) },
                                    enabled = songs.isNotEmpty(),
                                ) {
                                    Icon(Icons.Rounded.Download, "Download all", Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Download all")
                                }
                                OutlinedButton(onClick = onRetry) {
                                    Icon(Icons.Rounded.Refresh, "Reload", Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Reload")
                                }
                            }
                        }
                    }
                }

                if (songs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Rounded.MusicOff,
                                "No songs",
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            )
                            Spacer(Modifier.height(16.dp))
                            Text("This playlist is empty", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        item {
                            Text(
                                "${songs.size} song${if (songs.size == 1) "" else "s"}",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(songs, key = { it.id }) { song ->
                            SearchResultRow(
                                item = song,
                                playerState = playerState,
                                viewModel = viewModel,
                                contextSongs = songs,
                            )
                        }
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
                    Button(onClick = { openGoogleLogin(viewModel) },
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
    LaunchedEffect(viewModel.isLoggedIn) {
        if (viewModel.isLoggedIn) {
            viewModel.loadLikedSongs()
        }
    }

    val page = viewModel.likedSongsPage
    val songs: List<PlayerSong> = remember(page) {
        page?.songs?.map { item ->
            PlayerSong(
                id = item.id,
                title = item.title,
                artist = item.artists.joinToString(", ") { it.name },
                albumArt = item.thumbnail,
                durationMs = (item.duration ?: 0) * 1000L,
            )
        } ?: emptyList()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Hero Header (Spotify-like gradient)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                            MaterialTheme.colorScheme.background,
                        )
                    )
                )
                .padding(horizontal = 32.dp, vertical = 24.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Gradient squircle cover
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary,
                                )
                            )
                        )
                        .shadow(12.dp, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Favorite,
                        contentDescription = "Liked Songs",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(60.dp),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Playlist",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Liked Songs",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (viewModel.isLoggedIn) {
                            "${songs.size} song${if (songs.size == 1) "" else "s"}"
                        } else {
                            "Sign in to sync your YouTube Music likes"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Action bar (Play all, Shuffle)
        if (songs.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Button(
                    onClick = {
                        playerState.playQueue(songs, 0)
                    },
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Play", style = MaterialTheme.typography.titleSmall)
                }

                OutlinedButton(
                    onClick = {
                        val shuffled = songs.shuffled()
                        playerState.playQueue(shuffled, 0)
                    },
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Shuffle", style = MaterialTheme.typography.titleSmall)
                }
            }
        }

        // Song list content
        when {
            !viewModel.isLoggedIn -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Rounded.FavoriteBorder,
                            null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                        Text(
                            "Sign in to see your liked songs",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Your likes from YouTube Music will sync automatically",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { openGoogleLogin(viewModel) },
                            shape = RoundedCornerShape(50),
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Login, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Sign in with Google")
                        }
                    }
                }
            }

            viewModel.likedSongsLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(40.dp))
                        Text(
                            "Loading liked songs...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            viewModel.likedSongsError != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            viewModel.likedSongsError ?: "Failed to load liked songs",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = { viewModel.loadLikedSongs(force = true) },
                            shape = RoundedCornerShape(50),
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }

            songs.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Rounded.FavoriteBorder,
                            null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        )
                        Text(
                            "Songs you like will appear here",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Click the heart icon on any track to add it to your Liked Songs",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    itemsIndexed(songs, key = { index, song -> "${song.id}_$index" }) { index, song ->
                        val isCurrentSong = playerState.currentSong?.id == song.id
                        val isPlaying = isCurrentSong && playerState.isPlaying

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = if (isCurrentSong)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            else
                                Color.Transparent,
                            onClick = {
                                playerState.playQueue(songs, index)
                            },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier.width(32.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isPlaying) {
                                        PlayingIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    } else {
                                        Text(
                                            "${index + 1}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isCurrentSong)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                Spacer(Modifier.width(8.dp))

                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    AsyncImage(
                                        url = song.albumArt,
                                        contentDescription = song.title,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                }

                                Spacer(Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        song.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isCurrentSong) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isCurrentSong)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        song.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }

                                IconButton(
                                    onClick = { viewModel.toggleLike(song.id) },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.Favorite,
                                        contentDescription = "Unlike",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }

                                if (song.durationMs > 0) {
                                    val totalSeconds = song.durationMs / 1000
                                    val minutes = totalSeconds / 60
                                    val seconds = totalSeconds % 60
                                    Text(
                                        "%d:%02d".format(minutes, seconds),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 12.dp, end = 8.dp),
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

// ============================================================
// Settings Screen
// ============================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: DesktopViewModel,
    pureBlack: Boolean, onPureBlackChanged: (Boolean) -> Unit,
    themeColor: Color, onThemeColorChanged: (Color) -> Unit,
    syncClient: DesktopSyncClient,
    audioCacheSize: com.metrolist.desktop.data.CacheSize,
    onAudioCacheSizeChanged: (com.metrolist.desktop.data.CacheSize) -> Unit,
    dynamicColor: Boolean, onDynamicColorChanged: (Boolean) -> Unit,
) {
    var cookieInput by remember { mutableStateOf("") }
    var showCookieDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp),
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))

            // Account
            Material3SettingsGroupContainer(title = "Account") {
                if (viewModel.isLoggedIn) {
                    Material3SettingsCard(shape = settingsCardShape(0, 1)) {
                        Material3SettingsRow(
                            title = viewModel.accountName ?: "Logged in",
                            subtitle = viewModel.accountEmail ?: "YouTube Music account",
                            icon = Icons.Rounded.Person,
                            isHighlighted = true,
                            trailingContent = {
                                OutlinedButton(
                                    onClick = { viewModel.logout() },
                                    shape = RoundedCornerShape(50),
                                ) {
                                    Text("Sign out")
                                }
                            },
                        )
                    }
                } else {
                    Material3SettingsCard(shape = settingsCardShape(0, 1)) {
                        Material3SettingsRow(
                            title = "Not signed in",
                            subtitle = "Sign in to access your library, playlists & remote sync",
                            icon = Icons.Rounded.AccountCircle,
                            bottomContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                viewModel.clearLoginError()
                                                openGoogleLogin(viewModel)
                                            },
                                            enabled = !viewModel.loginInProgress,
                                            shape = RoundedCornerShape(50),
                                        ) {
                                            Icon(Icons.AutoMirrored.Rounded.Login, null, Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(if (viewModel.loginInProgress) "Signing in..." else "Sign in with Google")
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                viewModel.clearLoginError()
                                                showCookieDialog = true
                                            },
                                            enabled = !viewModel.loginInProgress,
                                            shape = RoundedCornerShape(50),
                                        ) {
                                            Text("Paste cookie")
                                        }
                                    }
                                    viewModel.loginError?.let { message ->
                                        Text(
                                            text = message,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }

            // Appearance
            Material3SettingsGroupContainer(title = "Appearance") {
                // Card 1: Dynamic color
                Material3SettingsCard(shape = settingsCardShape(0, 3)) {
                    Material3SettingsRow(
                        title = "Dynamic color from album art",
                        subtitle = if (dynamicColor)
                            "Theme adapts to the current song's artwork"
                        else
                            "Theme uses your chosen fallback color below",
                        icon = Icons.Rounded.AutoAwesome,
                        isHighlighted = dynamicColor,
                        trailingContent = {
                            Switch(checked = dynamicColor, onCheckedChange = onDynamicColorChanged)
                        },
                    )
                }

                // Card 2: Pure black AMOLED background
                Material3SettingsCard(shape = settingsCardShape(1, 3)) {
                    Material3SettingsRow(
                        title = "Pure black background",
                        subtitle = "AMOLED-friendly deep black background across panels",
                        icon = Icons.Rounded.DarkMode,
                        trailingContent = {
                            Switch(checked = pureBlack, onCheckedChange = onPureBlackChanged)
                        },
                    )
                }

                // Card 3: Fallback theme color + Palette
                Material3SettingsCard(shape = settingsCardShape(2, 3)) {
                    Material3SettingsRow(
                        title = "Fallback theme color",
                        subtitle = if (dynamicColor)
                            "Used when no album art is available"
                        else
                            "Active — dynamic color is off",
                        icon = Icons.Rounded.Palette,
                        bottomContent = {
                            Row(
                                modifier = Modifier
                                    .alpha(if (dynamicColor) 0.45f else 1f)
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
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
                                        modifier = Modifier.size(36.dp),
                                        shape = CircleShape,
                                        color = color,
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
                        },
                    )
                }
            }

            // Sync & Remote
            Material3SettingsGroupContainer(title = "Sync & Remote") {
                val isSyncConnected by remember { derivedStateOf { syncClient.connected } }
                var syncEnabled by remember { mutableStateOf(viewModel.isLoggedIn) }
                val totalSyncItems = if (!viewModel.isLoggedIn) 3 else 2

                Material3SettingsCard(shape = settingsCardShape(0, totalSyncItems)) {
                    Material3SettingsRow(
                        title = "Cross-device sync",
                        subtitle = "Control playback remotely from mobile & other clients",
                        icon = Icons.Rounded.Devices,
                        isHighlighted = syncEnabled,
                        trailingContent = {
                            Switch(
                                checked = syncEnabled,
                                onCheckedChange = { enabled ->
                                    syncEnabled = enabled
                                    val email = viewModel.accountEmail
                                    if (enabled && email != null) syncClient.connect(email)
                                    else syncClient.disconnect()
                                },
                            )
                        },
                    )
                }

                Material3SettingsCard(shape = settingsCardShape(1, totalSyncItems)) {
                    val (bgColor, textColor, label) = if (isSyncConnected)
                        Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, "Connected")
                    else
                        Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, "Disconnected")

                    Material3SettingsRow(
                        title = "Relay server",
                        subtitle = "metrolistsyncrelay-ooae5v0w.b4a.run",
                        icon = Icons.Rounded.Cloud,
                        trailingContent = {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = bgColor,
                            ) {
                                Text(
                                    text = label,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = textColor,
                                )
                            }
                        },
                    )
                }

                if (!viewModel.isLoggedIn) {
                    Material3SettingsCard(shape = settingsCardShape(2, 3)) {
                        Material3SettingsRow(
                            title = "Sign in required",
                            subtitle = "Remote sync requires a signed-in Google account",
                            icon = Icons.Rounded.Info,
                            enabled = false,
                        )
                    }
                }
            }

            // Audio Cache
            Material3SettingsGroupContainer(title = "Audio Cache") {
                val usedMb = com.metrolist.desktop.data.StreamCache.usedBytes / 1024 / 1024
                val totalSongs = com.metrolist.desktop.data.StreamCache.entries.size
                val isCachingActive = audioCacheSize != com.metrolist.desktop.data.CacheSize.DISABLED
                val totalCacheCards = if (isCachingActive) 2 else 1

                Material3SettingsCard(shape = settingsCardShape(0, totalCacheCards)) {
                    Material3SettingsRow(
                        title = "Cache size limit",
                        subtitle = if (!isCachingActive)
                            "Caching disabled — songs always stream live"
                        else
                            "$usedMb MB used · $totalSongs songs cached",
                        icon = Icons.Rounded.Storage,
                        isHighlighted = isCachingActive,
                        bottomContent = {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 4.dp),
                            ) {
                                com.metrolist.desktop.data.CacheSize.entries.forEach { size ->
                                    val selected = audioCacheSize == size
                                    FilterChip(
                                        selected = selected,
                                        onClick = { onAudioCacheSizeChanged(size) },
                                        label = { Text(size.label) },
                                        leadingIcon = if (selected) {{
                                            Icon(Icons.Rounded.Check, null, Modifier.size(16.dp))
                                        }} else null,
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                }
                            }
                        },
                    )
                }

                if (isCachingActive) {
                    val fillFraction = com.metrolist.desktop.data.StreamCache.fillFraction
                    Material3SettingsCard(shape = settingsCardShape(1, 2)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Cached,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        "Storage Usage",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        "${(fillFraction * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { fillFraction },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = when {
                                        fillFraction > 0.9f -> MaterialTheme.colorScheme.error
                                        fillFraction > 0.7f -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.primary
                                    },
                                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                )
                            }

                            OutlinedButton(
                                onClick = { com.metrolist.desktop.data.StreamCache.clearAll() },
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.height(36.dp),
                            ) {
                                Icon(Icons.Rounded.DeleteSweep, "Clear cache", Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Clear", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            // App Updates
            Material3SettingsGroupContainer(title = "App Updates") {
                Material3SettingsCard(shape = settingsCardShape(0, 2)) {
                    Material3SettingsRow(
                        title = "Current version",
                        subtitle = "Metrolist Desktop v$DESKTOP_APP_VERSION",
                        icon = Icons.Rounded.SystemUpdate,
                        trailingContent = {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        runCatching { AppUpdater.checkForUpdate() }
                                            .onFailure { println("[Updater] Manual check failed: ${it.message}") }
                                    }
                                },
                                shape = RoundedCornerShape(50),
                            ) {
                                Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Check now", style = MaterialTheme.typography.labelMedium)
                            }
                        },
                    )
                }

                Material3SettingsCard(shape = settingsCardShape(1, 2)) {
                    if (!AppUpdater.updateAvailable) {
                        Material3SettingsRow(
                            title = "You're up to date",
                            subtitle = if (AppUpdater.latestVersion != null)
                                "Latest: v${AppUpdater.latestVersion}"
                            else
                                "Checking for updates...",
                            icon = Icons.Rounded.CheckCircle,
                            isHighlighted = true,
                        )
                    } else {
                        val downloadState = AppUpdater.downloadState
                        val downloadProgress = AppUpdater.downloadProgress

                        Material3SettingsRow(
                            title = "Update available - v${AppUpdater.latestVersion}",
                            subtitle = "Your version: v$DESKTOP_APP_VERSION",
                            icon = Icons.Rounded.NewReleases,
                            isHighlighted = true,
                            bottomContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AppUpdater.releaseNotes?.let { notes ->
                                        var expanded by remember(notes) { mutableStateOf(false) }
                                        TextButton(onClick = { expanded = !expanded }) {
                                            Icon(
                                                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                                null,
                                                Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(if (expanded) "Hide changelog" else "View changelog")
                                        }
                                        AnimatedVisibility(expanded) {
                                            Text(
                                                notes,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        when (downloadState) {
                                            UpdateDownloadState.IDLE -> {
                                                Button(
                                                    onClick = { AppUpdater.downloadUpdate() },
                                                    shape = RoundedCornerShape(50),
                                                ) {
                                                    Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                                                    Spacer(Modifier.width(6.dp))
                                                    Text("Download & Install")
                                                }
                                            }
                                            UpdateDownloadState.DOWNLOADING -> {
                                                LinearProgressIndicator(
                                                    progress = { downloadProgress },
                                                    modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                                                )
                                                Text(
                                                    "${(downloadProgress * 100).toInt()}%",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            UpdateDownloadState.DONE -> {
                                                Icon(
                                                    Icons.Rounded.DownloadDone,
                                                    null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                                Button(
                                                    onClick = { AppUpdater.applyUpdate() },
                                                    shape = RoundedCornerShape(50),
                                                ) {
                                                    Icon(Icons.Rounded.InstallDesktop, null, Modifier.size(18.dp))
                                                    Spacer(Modifier.width(6.dp))
                                                    Text("Install Now")
                                                }
                                                TextButton(onClick = { AppUpdater.openDownloadedInstallerLocation() }) {
                                                    Text("Show file")
                                                }
                                            }
                                            UpdateDownloadState.ERROR -> {
                                                Icon(
                                                    Icons.Rounded.ErrorOutline,
                                                    null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                                Text(
                                                    "Download failed",
                                                    color = MaterialTheme.colorScheme.error,
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                                TextButton(onClick = { AppUpdater.downloadUpdate() }) {
                                                    Text("Retry")
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }

            // About
            Material3SettingsGroup(
                title = "About",
                items = listOf(
                    Material3SettingsItem(
                        title = "Version",
                        subtitle = "Metrolist Desktop v$DESKTOP_APP_VERSION",
                        icon = Icons.Rounded.Info,
                    ),
                    Material3SettingsItem(
                        title = "License",
                        subtitle = "GPL-3.0 · Open Source",
                        icon = Icons.Rounded.Code,
                    ),
                ),
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    // Cookie paste dialog
    if (showCookieDialog) {
        DisposableEffect(Unit) {
            onDispose {
                viewModel.isTextInputActive = false
            }
        }
        AlertDialog(
            onDismissRequest = {
                if (!viewModel.loginInProgress) {
                    showCookieDialog = false
                }
            },
            title = { Text("Paste YouTube Music Cookie") },
            text = {
                Column {
                    Text("Go to music.youtube.com, open DevTools (F12) → Application → Cookies, copy all cookies as a string.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = cookieInput, onValueChange = { cookieInput = it },
                        placeholder = { Text("Paste cookie string...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .onFocusChanged { focusState ->
                                viewModel.isTextInputActive = focusState.isFocused
                            },
                        maxLines = 5,
                    )
                    viewModel.loginError?.let { message ->
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (cookieInput.isNotBlank()) {
                            viewModel.loginWithCookie(
                                cookie = cookieInput.trim(),
                                onSuccess = {
                                    showCookieDialog = false
                                    cookieInput = ""
                                },
                            )
                        }
                    },
                    enabled = cookieInput.isNotBlank() && !viewModel.loginInProgress,
                ) {
                    Text(if (viewModel.loginInProgress) "Signing in..." else "Sign in")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCookieDialog = false },
                    enabled = !viewModel.loginInProgress,
                ) { Text("Cancel") }
            }
        )
    }
}

// ============================================================
// Cached Screen
// ============================================================

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CachedScreen(playerState: PlayerState, viewModel: DesktopViewModel) {
    val songs = com.metrolist.desktop.data.StreamCache.entries
    val usedMb = com.metrolist.desktop.data.StreamCache.usedBytes / 1024 / 1024
    val maxSize = com.metrolist.desktop.data.StreamCache.maxCacheSize

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Box(
            modifier = Modifier.fillMaxWidth().height(160.dp)
                .background(
                    Brush.verticalGradient(listOf(
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f),
                        MaterialTheme.colorScheme.background,
                    ))
                )
                .padding(32.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            Column {
                Icon(Icons.Rounded.CloudDownload, "Cached",
                    tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text("Cached", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${songs.size} song${if (songs.size != 1) "s" else ""} · ${usedMb} MB used" +
                        if (maxSize != com.metrolist.desktop.data.CacheSize.DISABLED)
                            " of ${maxSize.label}"
                        else " · Caching disabled",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.CloudDownload, "No cache",
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(16.dp))
                    Text("Nothing cached yet", style = MaterialTheme.typography.titleMedium)
                    Text("Songs are cached automatically as you stream them",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            // Sort newest-accessed first so recently heard songs appear at top
            val sorted = remember(songs.toList()) {
                songs.sortedByDescending { it.lastAccessedMs }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(sorted) { index, entry ->
                    val playlistSong = com.metrolist.desktop.player.PlayerSong(
                        id = entry.id,
                        title = entry.title,
                        artist = entry.artist,
                        albumArt = entry.albumArt,
                        durationMs = entry.durationMs,
                    )
                    var hovered by remember { mutableStateOf(false) }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = if (hovered) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                        onClick = {
                            playerState.playSong(playlistSong)
                        },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                                .onPointerEvent(PointerEventType.Exit)  { hovered = false }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Index
                            Text("${index + 1}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.width(28.dp))

                            // Thumbnail
                            Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (entry.albumArt != null) {
                                    AsyncImage(url = entry.albumArt, contentDescription = entry.title,
                                        modifier = Modifier.fillMaxSize(),
                                        placeholder = {
                                            Icon(Icons.Rounded.MusicNote, null, Modifier.size(22.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
                                        })
                                } else {
                                    Icon(Icons.Rounded.MusicNote, null, Modifier.size(22.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
                                }
                                // Cloud badge
                                Box(
                                    Modifier.align(Alignment.BottomEnd).size(14.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondary),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.CloudDownload, null, Modifier.size(8.dp),
                                        tint = MaterialTheme.colorScheme.onSecondary)
                                }
                            }
                            Spacer(Modifier.width(12.dp))

                            // Title + artist
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(entry.artist, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }

                            // File size
                            Text("${entry.sizeBytes / 1024 / 1024} MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            Spacer(Modifier.width(8.dp))

                            // Play
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                tooltip = { PlainTooltip { Text("Play cached") } },
                                state = rememberTooltipState(),
                            ) {
                                IconButton(
                                    onClick = { playerState.playSong(playlistSong) },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, "Play",
                                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                }
                            }
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                tooltip = { PlainTooltip { Text("Add to playlist") } },
                                state = rememberTooltipState(),
                            ) {
                                AddToPlaylistButton(
                                    song = playlistSong,
                                    viewModel = viewModel,
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                            // Remove from cache
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                tooltip = { PlainTooltip { Text("Remove from cache") } },
                                state = rememberTooltipState(),
                            ) {
                                IconButton(
                                    onClick = {
                                        try { java.io.File(entry.localPath).delete() } catch (_: Exception) {}
                                        com.metrolist.desktop.data.StreamCache.entries.removeIf { it.id == entry.id }
                                    },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(Icons.Rounded.CloudOff, "Remove from cache",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                        modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// Downloads Screen
// ============================================================

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(playerState: PlayerState, viewModel: DesktopViewModel) {
    val songs = com.metrolist.desktop.data.DownloadManager.downloadedSongs

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Box(
            modifier = Modifier.fillMaxWidth().height(160.dp)
                .background(
                    Brush.verticalGradient(listOf(
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f),
                        MaterialTheme.colorScheme.background,
                    ))
                )
                .padding(32.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            Column {
                Icon(Icons.Rounded.Download, "Downloads",
                    tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text("Downloads", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("${songs.size} song${if (songs.size != 1) "s" else ""} · Plays offline",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Download, "No downloads",
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                    Spacer(Modifier.height(16.dp))
                    Text("No downloads yet", style = MaterialTheme.typography.titleMedium)
                    Text("Hover over any song and click the download icon",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(songs) { index, song ->
                    var hovered by remember { mutableStateOf(false) }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = if (hovered) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                        onClick = {
                            // Play from local file
                            playerState.playSong(song.toPlayerSong())
                        },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                                .onPointerEvent(PointerEventType.Exit)  { hovered = false }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Index
                            Text("${index + 1}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.width(28.dp))

                            // Thumbnail
                            Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (song.albumArt != null) {
                                    AsyncImage(url = song.albumArt, contentDescription = song.title,
                                        modifier = Modifier.fillMaxSize(),
                                        placeholder = {
                                            Icon(Icons.Rounded.MusicNote, null, Modifier.size(22.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
                                        })
                                } else {
                                    Icon(Icons.Rounded.MusicNote, null, Modifier.size(22.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
                                }
                                // Offline badge
                                Box(
                                    Modifier.align(Alignment.BottomEnd).size(14.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.tertiary),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.Download, null, Modifier.size(8.dp),
                                        tint = MaterialTheme.colorScheme.onTertiary)
                                }
                            }
                            Spacer(Modifier.width(12.dp))

                            // Title + artist
                            Column(Modifier.weight(1f)) {
                                Text(song.title, style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.artist, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }

                            // Duration
                            Text(PlayerState.formatTime(song.durationMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            Spacer(Modifier.width(8.dp))

                            // Action buttons (always visible on this screen)
                            // Play button
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                tooltip = { PlainTooltip { Text("Play offline") } },
                                state = rememberTooltipState(),
                            ) {
                                IconButton(
                                    onClick = { playerState.playSong(song.toPlayerSong()) },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, "Play",
                                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                }
                            }
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                tooltip = { PlainTooltip { Text("Add to playlist") } },
                                state = rememberTooltipState(),
                            ) {
                                AddToPlaylistButton(
                                    song = song.toPlayerSong(),
                                    viewModel = viewModel,
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                            // Delete button
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                tooltip = { PlainTooltip { Text("Delete download") } },
                                state = rememberTooltipState(),
                            ) {
                                IconButton(
                                    onClick = { com.metrolist.desktop.data.DownloadManager.deleteSong(song.id) },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(Icons.Rounded.Delete, "Delete",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                        modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// Player Bar
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerBarTooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip(
                shape = RoundedCornerShape(8.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        state = rememberTooltipState(),
        modifier = modifier,
        content = content,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AnimatedShuffleButton(
    isShuffled: Boolean,
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFirstRender by remember { mutableStateOf(true) }
    val animTranslationX = remember { Animatable(0f) }
    val animScaleX = remember { Animatable(1f) }
    val animScaleY = remember { Animatable(1f) }
    val ghostOffset = remember { Animatable(0f) }
    val ghostAlpha = remember { Animatable(0f) }

    LaunchedEffect(isShuffled) {
        if (isFirstRender) {
            isFirstRender = false
            return@LaunchedEffect
        }
        if (isShuffled) {
            // ── Turn ON: The Criss-Cross Snap & Trail Split ──
            launch {
                // Step 1: Wind-up anticipation to the left
                animTranslationX.animateTo(-3.5f, tween(65, easing = LinearEasing))
                // Step 2: Forward whip with elastic spring bounce
                animTranslationX.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = 0.45f,
                        stiffness = Spring.StiffnessMediumLow,
                    )
                )
            }

            launch {
                // Horizontal squash & extreme stretch
                animScaleX.animateTo(0.78f, tween(65, easing = LinearEasing))
                animScaleX.animateTo(1.32f, tween(85, easing = FastOutSlowInEasing))
                animScaleX.animateTo(
                    targetValue = 1.0f,
                    animationSpec = spring(
                        dampingRatio = 0.5f,
                        stiffness = Spring.StiffnessMedium,
                    )
                )
            }

            launch {
                // Vertical counterpart (squash & stretch volume balance)
                animScaleY.animateTo(1.20f, tween(65, easing = LinearEasing))
                animScaleY.animateTo(0.75f, tween(85, easing = FastOutSlowInEasing))
                animScaleY.animateTo(
                    targetValue = 1.0f,
                    animationSpec = spring(
                        dampingRatio = 0.5f,
                        stiffness = Spring.StiffnessMedium,
                    )
                )
            }

            launch {
                // Ghost crossover split: fires during forward whip
                delay(55)
                ghostAlpha.snapTo(0.65f)
                ghostOffset.snapTo(0f)
                launch {
                    ghostOffset.animateTo(
                        targetValue = 4.5f,
                        animationSpec = tween(90, easing = FastOutSlowInEasing)
                    )
                    ghostOffset.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
                    )
                }
                launch {
                    ghostAlpha.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(220, easing = FastOutSlowInEasing)
                    )
                }
            }
        } else {
            // ── Turn OFF: Tension Release & Reset ──
            launch {
                animTranslationX.animateTo(2f, tween(60, easing = FastOutSlowInEasing))
                animTranslationX.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
                )
            }
            launch {
                animScaleX.animateTo(0.88f, tween(70, easing = FastOutSlowInEasing))
                animScaleX.animateTo(
                    targetValue = 1.0f,
                    animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
                )
            }
            launch {
                animScaleY.animateTo(1.10f, tween(70, easing = FastOutSlowInEasing))
                animScaleY.animateTo(
                    targetValue = 1.0f,
                    animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
                )
            }
        }
    }

    val dotScale by animateFloatAsState(
        targetValue = if (isShuffled) 1f else 0f,
        animationSpec = if (isShuffled)
            spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)
        else
            tween(150, easing = FastOutSlowInEasing),
        label = "shuffleDotScale"
    )
    val dotAlpha by animateFloatAsState(
        targetValue = if (isShuffled) 1f else 0f,
        animationSpec = tween(150),
        label = "shuffleDotAlpha"
    )
    val iconColor by animateColorAsState(
        targetValue = if (isShuffled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(220),
        label = "shuffleColor"
    )

    PlayerBarTooltip(tooltip) {
        IconButton(
            onClick = onClick,
            modifier = modifier
                .size(36.dp)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                val currentGhostAlpha = ghostAlpha.value
                val currentGhostOffset = ghostOffset.value

                if (currentGhostAlpha > 0.01f) {
                    // Upper crossing track trail
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = currentGhostAlpha * 0.40f),
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer {
                                translationX = currentGhostOffset
                                translationY = -currentGhostOffset * 0.7f
                                scaleX = 0.95f
                                scaleY = 0.95f
                            },
                    )
                    // Lower crossing track trail
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = currentGhostAlpha * 0.40f),
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer {
                                translationX = currentGhostOffset
                                translationY = currentGhostOffset * 0.7f
                                scaleX = 0.95f
                                scaleY = 0.95f
                            },
                    )
                }

                // Main icon with horizontal whip, squash & stretch
                Icon(
                    imageVector = Icons.Rounded.Shuffle,
                    contentDescription = tooltip,
                    tint = iconColor,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer {
                            translationX = animTranslationX.value
                            scaleX = animScaleX.value
                            scaleY = animScaleY.value
                        },
                )

                // Active dot underneath icon
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 2.dp)
                        .size(3.5.dp)
                        .graphicsLayer {
                            scaleX = dotScale
                            scaleY = dotScale
                            alpha = dotAlpha
                        }
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AnimatedRepeatButton(
    repeatMode: PlayerState.RepeatMode,
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFirstRender by remember { mutableStateOf(true) }
    val rotation = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }
    val isActive = repeatMode != PlayerState.RepeatMode.OFF

    LaunchedEffect(repeatMode) {
        if (isFirstRender) {
            isFirstRender = false
            return@LaunchedEffect
        }
        when (repeatMode) {
            PlayerState.RepeatMode.ALL -> {
                launch {
                    scale.animateTo(0.72f, tween(90, easing = FastOutSlowInEasing))
                    scale.animateTo(1.22f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow))
                    scale.animateTo(1.0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium))
                }
                launch {
                    rotation.snapTo(0f)
                    rotation.animateTo(360f, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessLow))
                }
            }
            PlayerState.RepeatMode.ONE -> {
                launch {
                    scale.animateTo(0.70f, tween(90, easing = FastOutSlowInEasing))
                    scale.animateTo(1.28f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
                    scale.animateTo(1.0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium))
                }
                launch {
                    rotation.snapTo(0f)
                    rotation.animateTo(360f, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessLow))
                }
            }
            PlayerState.RepeatMode.OFF -> {
                launch {
                    scale.animateTo(0.82f, tween(100, easing = FastOutSlowInEasing))
                    scale.animateTo(1.0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                }
                launch {
                    rotation.snapTo(0f)
                    rotation.animateTo(-40f, tween(120, easing = FastOutSlowInEasing))
                    rotation.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                }
            }
        }
    }

    val dotScale by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = if (isActive)
            spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)
        else
            tween(150, easing = FastOutSlowInEasing),
        label = "repeatDotScale"
    )
    val dotAlpha by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = tween(150),
        label = "repeatDotAlpha"
    )
    val iconColor by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(220),
        label = "repeatColor"
    )

    PlayerBarTooltip(tooltip) {
        IconButton(
            onClick = onClick,
            modifier = modifier
                .size(36.dp)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            rotationZ = rotation.value
                            scaleX = scale.value
                            scaleY = scale.value
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = repeatMode,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(180)) + scaleIn(initialScale = 0.55f, animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)))
                                .togetherWith(fadeOut(animationSpec = tween(140)) + scaleOut(targetScale = 0.55f, animationSpec = tween(140)))
                        },
                        label = "repeatIconTransition",
                    ) { mode ->
                        Icon(
                            imageVector = if (mode == PlayerState.RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                            contentDescription = tooltip,
                            tint = iconColor,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // Active dot underneath icon
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 2.dp)
                        .size(3.5.dp)
                        .graphicsLayer {
                            scaleX = dotScale
                            scaleY = dotScale
                            alpha = dotAlpha
                        }
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlayerBar(playerState: PlayerState, syncClient: DesktopSyncClient, viewModel: DesktopViewModel) {
    val song = playerState.currentSong ?: return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── Top Seeker Scrubber ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Slider(
                    value = playerState.progressFraction,
                    onValueChange = { playerState.seekTo((it * playerState.duration).toLong()) },
                    modifier = Modifier.fillMaxWidth().height(14.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                )
            }

            // ── Main Controls Row ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // ── Left: Song Info ──
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .shadow(4.dp, RoundedCornerShape(10.dp))
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
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
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    } else {
                                        Icon(
                                            Icons.Rounded.MusicNote, "art",
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                            },
                        )

                        if (playerState.isPlaying) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.4f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                PlayingIndicator(
                                    color = Color.White,
                                    modifier = Modifier.height(18.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            song.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val statusText = when {
                            playerState.isLoadingStream -> "Buffering stream..."
                            playerState.streamError != null -> playerState.streamError ?: "Error"
                            else -> song.artist
                        }
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (playerState.streamError != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(Modifier.width(10.dp))

                    val isLiked = playerState.currentSong?.let { viewModel.isLiked(it.id) } == true
                    val likeTooltip = if (isLiked) "Remove from Liked Songs" else "Save to Liked Songs"
                    PlayerBarTooltip(likeTooltip) {
                        IconButton(
                            onClick = {
                                val currentSong = playerState.currentSong
                                if (currentSong != null) viewModel.toggleLike(currentSong.id)
                            },
                            modifier = Modifier.size(38.dp),
                        ) {
                            Icon(
                                if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = likeTooltip,
                                tint = if (isLiked) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }

                // ── Center: Playback Controls & Timestamps ──
                Column(
                    modifier = Modifier.weight(1.3f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val shuffleTooltip = if (playerState.isShuffled) "Disable shuffle" else "Enable shuffle"
                        AnimatedShuffleButton(
                            isShuffled = playerState.isShuffled,
                            tooltip = shuffleTooltip,
                            onClick = { playerState.toggleShuffle() },
                        )

                        PlayerBarTooltip("Previous") {
                            IconButton(
                                onClick = { playerState.skipPrevious() },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(Icons.Rounded.SkipPrevious, "Previous", modifier = Modifier.size(28.dp))
                            }
                        }

                        val playPauseTooltip = if (playerState.isPlaying) "Pause" else "Play"
                        PlayerBarTooltip(playPauseTooltip) {
                            FilledIconButton(
                                onClick = { playerState.togglePlayPause() },
                                modifier = Modifier.size(46.dp),
                                shape = CircleShape,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            ) {
                                Icon(
                                    if (playerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    playPauseTooltip,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }

                        PlayerBarTooltip("Next") {
                            IconButton(
                                onClick = { playerState.skipNext() },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(Icons.Rounded.SkipNext, "Next", modifier = Modifier.size(28.dp))
                            }
                        }

                        val repeatTooltip = when (playerState.repeatMode) {
                            PlayerState.RepeatMode.OFF -> "Enable repeat"
                            PlayerState.RepeatMode.ALL -> "Enable repeat one"
                            PlayerState.RepeatMode.ONE -> "Disable repeat"
                        }
                        AnimatedRepeatButton(
                            repeatMode = playerState.repeatMode,
                            tooltip = repeatTooltip,
                            onClick = { playerState.cycleRepeat() },
                        )
                    }

                    Text(
                        "${playerState.currentTimeFormatted} / ${playerState.durationFormatted}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    )
                }

                // ── Right: Volume & Extra Controls ──
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val volumeIcon = when {
                        playerState.volume <= 0.001f -> Icons.AutoMirrored.Rounded.VolumeMute
                        playerState.volume < 0.5f -> Icons.AutoMirrored.Rounded.VolumeDown
                        else -> Icons.AutoMirrored.Rounded.VolumeUp
                    }
                    var previousVolume by remember { mutableStateOf(1f) }
                    val volumeTooltip = if (playerState.volume <= 0.001f) "Unmute" else "Mute"
                    PlayerBarTooltip(volumeTooltip) {
                        IconButton(
                            onClick = {
                                if (playerState.volume > 0.001f) {
                                    previousVolume = playerState.volume
                                    playerState.volume = 0f
                                } else {
                                    playerState.volume = if (previousVolume > 0.001f) previousVolume else 0.7f
                                }
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                volumeIcon, volumeTooltip,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }

                    Slider(
                        value = playerState.volume,
                        onValueChange = { playerState.volume = it },
                        modifier = Modifier.width(90.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    )

                    Spacer(Modifier.width(8.dp))

                    val currentDlState = song.let { com.metrolist.desktop.data.DownloadManager.downloads[it.id] }
                    val currentDlProgress = song.let { com.metrolist.desktop.data.DownloadManager.progress[it.id] } ?: 0f
                    val downloadTooltip = when (currentDlState) {
                        com.metrolist.desktop.data.DownloadState.DONE -> "Downloaded"
                        com.metrolist.desktop.data.DownloadState.DOWNLOADING -> "Downloading ${(currentDlProgress * 100).toInt()}%"
                        com.metrolist.desktop.data.DownloadState.QUEUED -> "Queued"
                        else -> "Download"
                    }
                    PlayerBarTooltip(downloadTooltip) {
                        IconButton(
                            onClick = {
                                if (currentDlState == null || currentDlState == com.metrolist.desktop.data.DownloadState.ERROR) {
                                    com.metrolist.desktop.data.DownloadManager.downloadSong(playerState.currentSong!!)
                                }
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            when (currentDlState) {
                                com.metrolist.desktop.data.DownloadState.DONE ->
                                    Icon(
                                        Icons.Rounded.DownloadDone, "Downloaded",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                com.metrolist.desktop.data.DownloadState.DOWNLOADING,
                                com.metrolist.desktop.data.DownloadState.QUEUED ->
                                    CircularProgressIndicator(
                                        progress = { currentDlProgress },
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                else ->
                                    Icon(
                                        Icons.Rounded.Download, "Download",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                            }
                        }
                    }

                    // Lyrics toggle button
                    val lyricsTooltip = if (playerState.showLyrics) "Hide lyrics" else "Lyrics"
                    PlayerBarTooltip(lyricsTooltip) {
                        IconButton(
                            onClick = { playerState.showLyrics = !playerState.showLyrics },
                            modifier = Modifier.size(36.dp),
                        ) {
                            val active = playerState.showLyrics
                            Surface(
                                shape = CircleShape,
                                color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Rounded.Mic, lyricsTooltip,
                                        tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                            }
                        }
                    }

                    // Queue toggle button
                    val queueTooltip = if (playerState.showRightPanel && playerState.showQueue) "Hide queue" else "Queue"
                    PlayerBarTooltip(queueTooltip) {
                        IconButton(
                            onClick = {
                                if (playerState.showRightPanel && playerState.showQueue) {
                                    playerState.showRightPanel = false
                                } else {
                                    playerState.showRightPanel = true
                                    playerState.showQueue = true
                                }
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            val active = playerState.showRightPanel && playerState.showQueue
                            Surface(
                                shape = CircleShape,
                                color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.QueueMusic, queueTooltip,
                                        tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                            }
                        }
                    }

                    // Now Playing panel toggle button
                    val infoTooltip = if (playerState.showRightPanel && !playerState.showQueue) "Hide song info" else "Song info"
                    PlayerBarTooltip(infoTooltip) {
                        IconButton(
                            onClick = {
                                if (playerState.showRightPanel && !playerState.showQueue) {
                                    playerState.showRightPanel = false
                                } else {
                                    playerState.showRightPanel = true
                                    playerState.showQueue = false
                                }
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            val active = playerState.showRightPanel && !playerState.showQueue
                            Surface(
                                shape = CircleShape,
                                color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Rounded.Info, infoTooltip,
                                        tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(19.dp),
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

// ============================================================
// Lyrics Panel
// ============================================================

// ============================================================
// Spotify-Style Full-Height Lyrics Screen
// ============================================================

@Composable
fun SpotifyLyricsScreen(
    playerState: PlayerState,
    themeColor: Color,
    onClose: () -> Unit,
) {
    val lyrics = playerState.lyrics
    val currentIndex = playerState.currentLyricIndex
    val listState = rememberLazyListState()

    // Smooth auto-scroll to current lyric line (~35% down viewport)
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && currentIndex < lyrics.size) {
            listState.animateScrollToItem(
                index = currentIndex.coerceAtLeast(0),
                scrollOffset = -180,
            )
        }
    }

    // Atmospheric ambient background color derived from album art/theme
    val darkAmbientTop = remember(themeColor) {
        Color(
            red = (themeColor.red * 0.22f + 0.04f).coerceIn(0.04f, 0.20f),
            green = (themeColor.green * 0.22f + 0.04f).coerceIn(0.04f, 0.20f),
            blue = (themeColor.blue * 0.22f + 0.04f).coerceIn(0.04f, 0.20f),
            alpha = 1f,
        )
    }
    val darkAmbientBottom = remember(darkAmbientTop) {
        Color(
            red = darkAmbientTop.red * 0.45f,
            green = darkAmbientTop.green * 0.45f,
            blue = darkAmbientTop.blue * 0.45f,
            alpha = 1f,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        darkAmbientTop,
                        darkAmbientBottom,
                        Color(0xFF0E0E0E),
                    )
                )
            ),
    ) {
        when {
            playerState.lyricsLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Loading lyrics...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.6f),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            lyrics.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.06f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.MicNone,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.4f),
                                modifier = Modifier.size(36.dp),
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        Text(
                            text = "Looks like we don't have the lyrics for this song yet.",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        playerState.currentSong?.let { song ->
                            Text(
                                text = "${song.title} • ${song.artist}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.45f),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            else -> {
                val hasSync = lyrics.any { it.timeMs >= 0 }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 80.dp, bottom = 260.dp, start = 56.dp, end = 56.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(lyrics) { index, line ->
                        val isCurrent = hasSync && index == currentIndex
                        val isPast = hasSync && currentIndex >= 0 && index < currentIndex
                        val isSynced = line.timeMs >= 0

                        SpotifyLyricLineRow(
                            text = line.text.ifBlank { "♪" },
                            isCurrent = isCurrent,
                            isPast = isPast,
                            isSynced = isSynced,
                            onClick = {
                                if (isSynced) {
                                    playerState.seekTo(line.timeMs)
                                }
                            },
                        )
                    }

                    item {
                        Spacer(Modifier.height(48.dp))
                        Text(
                            text = "Lyrics provided by LRCLIB",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.35f),
                        )
                    }
                }
            }
        }

        // Top-right floating close button
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp)
                .size(38.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f)),
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Close lyrics",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(20.dp),
            )
        }

        // Subtle gradient overlay at top edge so text fades under header smoothly
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            darkAmbientTop.copy(alpha = 0.85f),
                            Color.Transparent,
                        )
                    )
                )
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SpotifyLyricLineRow(
    text: String,
    isCurrent: Boolean,
    isPast: Boolean,
    isSynced: Boolean,
    onClick: () -> Unit,
) {
    var isHovered by remember { mutableStateOf(false) }

    val targetAlpha = when {
        isCurrent -> 1.0f
        isHovered -> 0.80f
        !isSynced -> 0.80f
        isPast -> 0.38f
        else -> 0.38f
    }

    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "lyricAlpha",
    )

    val targetScale by animateFloatAsState(
        targetValue = if (isCurrent) 1.02f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "lyricScale",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false }
            .clickable(
                enabled = isSynced,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontSize = if (isCurrent) 32.sp else 30.sp,
                lineHeight = if (isCurrent) 42.sp else 40.sp,
                fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Bold,
                letterSpacing = (-0.5).sp,
            ),
            color = Color.White.copy(alpha = animatedAlpha),
            textAlign = TextAlign.Start,
            modifier = Modifier.graphicsLayer {
                scaleX = targetScale
                scaleY = targetScale
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
        )
    }
}

// ============================================================
// Utilities
// ============================================================

fun openGoogleLogin(viewModel: DesktopViewModel) {
    com.metrolist.desktop.auth.showGoogleLoginDialog(viewModel)
}

fun getGreeting(): String {
    val hour = java.time.LocalTime.now().hour
    return when {
        hour < 12 -> "morning"
        hour < 17 -> "afternoon"
        else -> "evening"
    }
}
