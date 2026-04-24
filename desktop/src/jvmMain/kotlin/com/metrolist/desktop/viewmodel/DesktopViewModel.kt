/**
 * Desktop ViewModel — manages real YouTube Music API calls
 */

package com.metrolist.desktop.viewmodel

import androidx.compose.runtime.*
import com.metrolist.desktop.auth.DesktopPreferences
import com.metrolist.desktop.search.SearchRanker
import com.metrolist.innertube.YouTube
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

    // ── Artist info (for now-playing panel) ──
    var artistPage by mutableStateOf<ArtistPage?>(null)
        private set
    var artistLoading by mutableStateOf(false)
        private set
    private var lastArtistId: String? = null

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

    fun loadHome() {
        if (homeLoading) return
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

    fun loginWithCookie(cookie: String, visitorData: String = "", dataSyncId: String = "") {
        YouTube.cookie = cookie
        YouTube.visitorData = visitorData.ifBlank { null }
        YouTube.dataSyncId = dataSyncId.ifBlank { null }
        isLoggedIn = cookie.contains("SAPISID")

        scope.launch(Dispatchers.IO) {
            YouTube.accountInfo().onSuccess { info ->
                accountName = info.name
                accountEmail = info.email
                val config = DesktopPreferences.load().copy(
                    cookie = cookie,
                    visitorData = visitorData,
                    dataSyncId = dataSyncId,
                    accountName = info.name,
                    accountEmail = info.email ?: "",
                )
                DesktopPreferences.save(config)
            }
            // Reload home + playlists with auth
            loadHome()
            loadUserPlaylists()
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
        loadHome()
    }
}
