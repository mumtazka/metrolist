/**
 * Desktop ViewModel — manages real YouTube Music API calls
 */

package com.metrolist.desktop.viewmodel

import androidx.compose.runtime.*
import com.metrolist.desktop.auth.DesktopPreferences
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
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
                searchResults = page.summaries.flatMap { it.items }
                searchLoading = false
            }.onFailure {
                searchResults = emptyList()
                searchLoading = false
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
            // Reload home with auth
            loadHome()
        }
    }

    fun logout() {
        YouTube.cookie = null
        YouTube.visitorData = null
        YouTube.dataSyncId = null
        isLoggedIn = false
        accountName = null
        accountEmail = null
        val config = DesktopPreferences.load().copy(
            cookie = "", visitorData = "", dataSyncId = "",
            accountName = "", accountEmail = "",
        )
        DesktopPreferences.save(config)
        loadHome()
    }
}
