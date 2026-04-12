/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * AudioPlayer - Platform-agnostic audio playback interface.
 * Android implements this with Media3/ExoPlayer.
 * Desktop implements this with vlcj.
 */

package com.metrolist.shared.player

import kotlinx.coroutines.flow.StateFlow

/**
 * Minimal song metadata passed to the player.
 */
data class SongInfo(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val streamUrl: String? = null,
    val durationMs: Long = 0L,
)

/**
 * Shared interface for audio playback across platforms.
 * Each platform provides its own implementation.
 */
interface AudioPlayer {
    /** Whether the player is currently playing audio. */
    val isPlaying: StateFlow<Boolean>

    /** Current playback position in milliseconds. */
    val currentPosition: StateFlow<Long>

    /** Total duration of the current track in milliseconds. */
    val duration: StateFlow<Long>

    /** The currently loaded song, or null if nothing is loaded. */
    val currentSong: StateFlow<SongInfo?>

    /** Current volume level from 0.0 to 1.0. */
    val volume: StateFlow<Float>

    /** Resume or start playback. */
    fun play()

    /** Pause playback. */
    fun pause()

    /** Seek to a position (milliseconds). */
    fun seekTo(positionMs: Long)

    /** Skip to the next track in the queue. */
    fun skipNext()

    /** Skip to the previous track in the queue. */
    fun skipPrevious()

    /** Load a queue of songs and start playing from [startIndex]. */
    fun setQueue(songs: List<SongInfo>, startIndex: Int = 0)

    /** Set volume (0.0 to 1.0). */
    fun setVolume(level: Float)

    /** Release all resources. Call when the player is no longer needed. */
    fun release()
}
