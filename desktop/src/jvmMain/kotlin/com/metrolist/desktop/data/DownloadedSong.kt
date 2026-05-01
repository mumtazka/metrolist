package com.metrolist.desktop.data

import com.metrolist.desktop.player.PlayerSong
import kotlinx.serialization.Serializable

/**
 * Represents a song that has been downloaded to local disk.
 * Stored in ~/.config/metrolist/downloads.json via kotlinx.serialization.
 */
@Serializable
data class DownloadedSong(
    val id: String,
    val title: String,
    val artist: String,
    val albumArt: String?,
    val durationMs: Long,
    /** Absolute path to the downloaded audio file on disk. */
    val localPath: String,
    /** Epoch millis when the download completed. */
    val downloadedAt: Long,
) {
    fun toPlayerSong() = PlayerSong(
        id        = id,
        title     = title,
        artist    = artist,
        albumArt  = albumArt,
        durationMs = durationMs,
    )
}
