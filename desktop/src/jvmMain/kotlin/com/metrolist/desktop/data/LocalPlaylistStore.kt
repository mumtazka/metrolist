package com.metrolist.desktop.data

import androidx.compose.runtime.mutableStateListOf
import com.metrolist.desktop.player.PlayerSong
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch


@Serializable
data class LocalPlaylistSong(
    val id: String,
    val title: String,
    val artist: String,
    val albumArt: String?,
    val durationMs: Long,
    val addedAt: Long,
) {
    fun toPlayerSong() = PlayerSong(
        id = id,
        title = title,
        artist = artist,
        albumArt = albumArt,
        durationMs = durationMs,
    )
}

@Serializable
data class LocalPlaylist(
    val id: String,
    val name: String,
    val songs: List<LocalPlaylistSong>,
    val createdAt: Long,
    val updatedAt: Long,
)

object LocalPlaylistStore {
    val playlists = mutableStateListOf<LocalPlaylist>()

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val configDir = runCatching {
        File(System.getProperty("user.home") ?: ".", ".config/metrolist").also { it.mkdirs() }
    }.getOrElse {
        File(".metrolist_config").also { it.mkdirs() }
    }
    private val playlistFile = File(configDir, "local_playlists.json")

    init {
        load()
    }

    private fun load() {
        if (!playlistFile.exists()) return
        runCatching {
            json.decodeFromString<List<LocalPlaylist>>(playlistFile.readText())
        }.onSuccess { stored ->
            playlists.clear()
            playlists.addAll(stored.sortedByDescending { it.updatedAt })
        }.onFailure { error ->
            println("[Playlist] Failed to load playlists: ${error.message}")
        }
    }

    private fun save() {
        val listToSave = playlists.toList()
        scope.launch {
            runCatching {
                playlistFile.writeText(json.encodeToString(listToSave))
            }.onFailure { error ->
                println("[Playlist] Failed to save playlists: ${error.message}")
            }
        }
    }

    fun getPlaylist(playlistId: String): LocalPlaylist? = playlists.firstOrNull { it.id == playlistId }

    fun createPlaylist(name: String): LocalPlaylist {
        val now = System.currentTimeMillis()
        val playlist = LocalPlaylist(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            songs = emptyList(),
            createdAt = now,
            updatedAt = now,
        )
        playlists.add(0, playlist)
        save()
        return playlist
    }

    fun renamePlaylist(playlistId: String, newName: String) {
        updatePlaylist(playlistId) { playlist ->
            playlist.copy(
                name = newName.trim(),
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    fun deletePlaylist(playlistId: String) {
        playlists.removeAll { it.id == playlistId }
        save()
    }

    fun addSongToPlaylist(playlistId: String, song: PlayerSong): Boolean {
        val playlist = getPlaylist(playlistId) ?: return false
        if (playlist.songs.any { it.id == song.id }) return false

        val updated = playlist.copy(
            songs = playlist.songs + LocalPlaylistSong(
                id = song.id,
                title = song.title,
                artist = song.artist,
                albumArt = song.albumArt,
                durationMs = song.durationMs,
                addedAt = System.currentTimeMillis(),
            ),
            updatedAt = System.currentTimeMillis(),
        )
        replacePlaylist(updated)
        return true
    }

    fun removeSongFromPlaylist(playlistId: String, songId: String) {
        updatePlaylist(playlistId) { playlist ->
            playlist.copy(
                songs = playlist.songs.filterNot { it.id == songId },
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    private fun updatePlaylist(playlistId: String, transform: (LocalPlaylist) -> LocalPlaylist) {
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index < 0) return
        val updated = transform(playlists[index])
        replacePlaylist(updated)
    }

    private fun replacePlaylist(updated: LocalPlaylist) {
        val index = playlists.indexOfFirst { it.id == updated.id }
        if (index < 0) return
        if (index > 0) {
            playlists.removeAt(index)
            playlists.add(0, updated)
        } else {
            playlists[0] = updated
        }
        save()
    }
}
