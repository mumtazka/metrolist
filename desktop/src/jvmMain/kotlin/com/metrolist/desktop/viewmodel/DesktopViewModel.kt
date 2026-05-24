/**
 * Desktop ViewModel — manages real YouTube Music API calls
 */

package com.metrolist.desktop.viewmodel

import androidx.compose.runtime.*
import com.metrolist.desktop.auth.DesktopPreferences
import com.metrolist.desktop.data.LocalPlaylist
import com.metrolist.desktop.data.LocalPlaylistStore
import com.metrolist.desktop.search.SearchRanker
import com.metrolist.desktop.player.PlayerSong
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.pages.ArtistPage
import com.metrolist.innertube.pages.HomePage
import kotlinx.coroutines.*

class DesktopViewModel(private val scope: CoroutineScope) {

    // ── Home ──
    var homeSections by mutableStateOf<List<HomePage.Section>>(emptyList())
        private set
    var homeLoading by mutableStateOf(false)
        private set
    var homeError by mutableStateOf<String?>(null)
        private set

    // ── Search ──
    var searchResults by mutableStateOf<List<YTItem>>(emptyList())
        private set
    var searchLoading by mutableStateOf(false)
        private set

    // ── Account ──
    var accountName by mutableStateOf<String?>(null)
        private set
    var accountEmail by mutableStateOf<String?>(null)
        private set
    var isLoggedIn by mutableStateOf(false)
        private set
    var loginInProgress by mutableStateOf(false)
        private set
    var loginError by mutableStateOf<String?>(null)
        private set

    // ── Artist info (for now-playing panel) ──
    var artistPage by mutableStateOf<ArtistPage?>(null)
        private set
    var artistLoading by mutableStateOf(false)
        private set
    private var lastArtistId: String? = null

    // ── Playlist info ──
    var currentPlaylistId by mutableStateOf<String?>(null)
        private set
    var currentLocalPlaylistId by mutableStateOf<String?>(null)
        private set
    var currentPlaylistPage by mutableStateOf<com.metrolist.innertube.pages.PlaylistPage?>(null)
        private set
    var playlistLoading by mutableStateOf(false)
        private set
    var playlistError by mutableStateOf<String?>(null)
        private set

    val localPlaylists: List<LocalPlaylist>
        get() = LocalPlaylistStore.playlists

    val currentLocalPlaylist: LocalPlaylist?
        get() = currentLocalPlaylistId?.let(LocalPlaylistStore::getPlaylist)

    // ── User playlists (for left sidebar) ──
    var userPlaylists by mutableStateOf<List<PlaylistItem>>(emptyList())
        private set

    // ── Liked song IDs (local — used for search boost + heart icon) ──
    var likedSongIds by mutableStateOf<Set<String>>(emptySet())
        private set

    init {
        initYouTube()
    }

    private fun initYouTube() {
        val config = DesktopPreferences.load()
        if (config.cookie.isNotBlank()) {
            YouTube.cookie = config.cookie
            YouTube.visitorData = config.visitorData.ifBlank { null }
            YouTube.dataSyncId = config.dataSyncId.ifBlank { null }
            isLoggedIn = config.cookie.contains("SAPISID")
            accountName = config.accountName.ifBlank { null }
            accountEmail = config.accountEmail.ifBlank { null }
            if (isLoggedIn) loadUserPlaylists()
        }
    }

    fun loadHome(force: Boolean = false) {
        if (homeLoading && !force) return
        homeLoading = true
        homeError = null
        scope.launch(Dispatchers.IO) {
            YouTube.home().onSuccess { page ->
                homeSections = page.sections
                homeLoading = false
            }.onFailure { e ->
                homeError = e.message ?: "Failed to load"
                homeLoading = false
            }
        }
    }

    fun search(query: String) {
        if (query.isBlank()) {
            searchResults = emptyList()
            return
        }
        searchLoading = true
        scope.launch(Dispatchers.IO) {
            YouTube.searchSummary(query).onSuccess { page ->
                val raw = page.summaries.flatMap { it.items }
                // Apply client-side re-ranking for better result ordering
                searchResults = SearchRanker.rankResults(raw, query, likedSongIds)
                searchLoading = false
            }.onFailure {
                searchResults = emptyList()
                searchLoading = false
            }
        }
    }

    /**
     * Fetch artist page for the now-playing panel.
     * Only fetches if the artistId has actually changed (avoids re-fetching on every seek).
     */
    fun loadArtist(artistId: String) {
        if (artistId == lastArtistId) return
        lastArtistId = artistId
        artistPage = null
        artistLoading = true
        scope.launch(Dispatchers.IO) {
            YouTube.artist(artistId).onSuccess { page ->
                artistPage = page
                artistLoading = false
            }.onFailure {
                artistLoading = false
            }
        }
    }

    /** Clear artist panel (call when song changes to a different artist) */
    fun clearArtist() {
        lastArtistId = null
        artistPage = null
        artistLoading = false
    }

    /** Load user's playlists from YTMusic library — requires login. */
    fun loadUserPlaylists() {
        if (!isLoggedIn) return
        scope.launch(Dispatchers.IO) {
            YouTube.library("FEmusic_liked_playlists").onSuccess { result ->
                userPlaylists = result.items.filterIsInstance<PlaylistItem>()
            }.onFailure {
                userPlaylists = emptyList()
            }
        }
    }

    fun openPlaylist(playlistId: String) {
        currentPlaylistId = playlistId
        currentLocalPlaylistId = null
        playlistLoading = true
        playlistError = null
        currentPlaylistPage = null
        scope.launch(Dispatchers.IO) {
            YouTube.playlist(playlistId).onSuccess { page ->
                currentPlaylistPage = page
                playlistLoading = false
            }.onFailure { error ->
                playlistError = error.message ?: "Failed to load playlist"
                playlistLoading = false
            }
        }
    }

    fun retryCurrentPlaylist() {
        currentPlaylistId?.let(::openPlaylist)
    }

    fun openLocalPlaylist(playlistId: String) {
        currentLocalPlaylistId = playlistId
        currentPlaylistId = null
        currentPlaylistPage = null
        playlistLoading = false
        playlistError = null
    }

    fun clearPlaylistSelection() {
        currentLocalPlaylistId = null
        currentPlaylistId = null
        currentPlaylistPage = null
        playlistLoading = false
        playlistError = null
    }

    fun createLocalPlaylist(name: String): LocalPlaylist? {
        val normalized = name.trim()
        if (normalized.isBlank()) return null
        val playlist = LocalPlaylistStore.createPlaylist(normalized)
        openLocalPlaylist(playlist.id)
        return playlist
    }

    fun renameLocalPlaylist(playlistId: String, newName: String) {
        val normalized = newName.trim()
        if (normalized.isBlank()) return
        LocalPlaylistStore.renamePlaylist(playlistId, normalized)
    }

    fun deleteLocalPlaylist(playlistId: String) {
        LocalPlaylistStore.deletePlaylist(playlistId)
        if (currentLocalPlaylistId == playlistId) {
            clearPlaylistSelection()
        }
    }

    fun addSongToLocalPlaylist(playlistId: String, song: PlayerSong): Boolean {
        return LocalPlaylistStore.addSongToPlaylist(playlistId, song)
    }

    fun removeSongFromLocalPlaylist(playlistId: String, songId: String) {
        LocalPlaylistStore.removeSongFromPlaylist(playlistId, songId)
    }

    /**
     * Toggle like on a song by its video ID. Tracks liked IDs locally so the
     * heart icon and search ranking react immediately.
     */
    fun toggleLike(songId: String) {
        val nowLiked = songId !in likedSongIds
        likedSongIds = if (nowLiked) likedSongIds + songId else likedSongIds - songId
        // Persist to YouTube best-effort (fire and forget)
        scope.launch(Dispatchers.IO) {
            runCatching { YouTube.likeVideo(songId, nowLiked) }
        }
    }

    fun isLiked(songId: String): Boolean = songId in likedSongIds

    /** Search for an artist by name and load their page for the now-playing panel. */
    fun searchArtistForPanel(artistName: String) {
        artistLoading = true
        scope.launch(Dispatchers.IO) {
            YouTube.searchSummary(artistName).onSuccess { page ->
                val artistItem = page.summaries
                    .flatMap { it.items }
                    .filterIsInstance<ArtistItem>()
                    .firstOrNull()
                if (artistItem != null) {
                    loadArtist(artistItem.id)
                } else {
                    artistLoading = false
                }
            }.onFailure {
                artistLoading = false
            }
        }
    }

    fun clearLoginError() {
        loginError = null
    }

    fun loginWithCookie(
        cookie: String,
        visitorData: String = "",
        dataSyncId: String = "",
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {},
    ) {
        if (loginInProgress) return

        val normalizedCookie = cookie.trim()
        val normalizedVisitorData = visitorData.trim()
        val normalizedDataSyncId = dataSyncId.trim().substringBefore("||")

        if (!parseCookieString(normalizedCookie).containsKey("SAPISID")) {
            val message = "The sign-in session is missing SAPISID. Try signing in again."
            loginError = message
            onFailure(message)
            return
        }

        val previousCookie = YouTube.cookie
        val previousVisitorData = YouTube.visitorData
        val previousDataSyncId = YouTube.dataSyncId
        val previousLoggedIn = isLoggedIn
        val previousAccountName = accountName
        val previousAccountEmail = accountEmail

        loginInProgress = true
        loginError = null
        YouTube.cookie = normalizedCookie
        YouTube.visitorData = normalizedVisitorData.ifBlank { null }
        YouTube.dataSyncId = normalizedDataSyncId.ifBlank { null }

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                YouTube.accountInfo()
            }

            result.onSuccess { info ->
                isLoggedIn = true
                accountName = info.name
                accountEmail = info.email
                DesktopPreferences.save(
                    DesktopPreferences.load().copy(
                        cookie = normalizedCookie,
                        visitorData = normalizedVisitorData,
                        dataSyncId = normalizedDataSyncId,
                        accountName = info.name,
                        accountEmail = info.email ?: "",
                    )
                )
                loadHome(force = true)
                loadUserPlaylists()
                onSuccess()
            }.onFailure { error ->
                YouTube.cookie = previousCookie
                YouTube.visitorData = previousVisitorData
                YouTube.dataSyncId = previousDataSyncId
                isLoggedIn = previousLoggedIn
                accountName = previousAccountName
                accountEmail = previousAccountEmail

                val message = error.message ?: "Google sign-in failed. Please try again."
                loginError = message
                onFailure(message)
            }

            loginInProgress = false
        }
    }

    fun logout() {
        YouTube.cookie = null
        YouTube.visitorData = null
        YouTube.dataSyncId = null
        isLoggedIn = false
        accountName = null
        accountEmail = null
        userPlaylists = emptyList()
        val config = DesktopPreferences.load().copy(
            cookie = "", visitorData = "", dataSyncId = "",
            accountName = "", accountEmail = "",
        )
        DesktopPreferences.save(config)
        loadHome(force = true)
    }
}
