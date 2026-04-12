/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * DesktopAudioPlayer - Desktop implementation of AudioPlayer using vlcj.
 * Requires VLC Media Player to be installed on the system.
 */

package com.metrolist.shared.player.desktop

import com.metrolist.shared.player.AudioPlayer
import com.metrolist.shared.player.SongInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.AudioPlayerComponent

/**
 * Desktop audio player powered by VLC via vlcj.
 * Streams YouTube audio URLs (Opus/WebM) using VLC's native codecs.
 */
class DesktopAudioPlayer : AudioPlayer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var audioComponent: AudioPlayerComponent? = null
    private var positionUpdateJob: Job? = null

    // --- State Flows ---

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    override val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    override val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _currentSong = MutableStateFlow<SongInfo?>(null)
    override val currentSong: StateFlow<SongInfo?> = _currentSong.asStateFlow()

    private val _volume = MutableStateFlow(1.0f)
    override val volume: StateFlow<Float> = _volume.asStateFlow()

    // --- Queue ---

    private var queue: List<SongInfo> = emptyList()
    private var currentIndex: Int = 0

    init {
        // Discover VLC native libraries
        NativeDiscovery().discover()
        initializePlayer()
    }

    private fun initializePlayer() {
        audioComponent = AudioPlayerComponent().apply {
            mediaPlayer().events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer) {
                    _isPlaying.value = true
                    _duration.value = mediaPlayer.media().info()?.duration() ?: 0L
                    startPositionUpdates()
                }

                override fun paused(mediaPlayer: MediaPlayer) {
                    _isPlaying.value = false
                    stopPositionUpdates()
                }

                override fun stopped(mediaPlayer: MediaPlayer) {
                    _isPlaying.value = false
                    _currentPosition.value = 0L
                    stopPositionUpdates()
                }

                override fun finished(mediaPlayer: MediaPlayer) {
                    _isPlaying.value = false
                    stopPositionUpdates()
                    // Auto-advance to next track
                    if (currentIndex < queue.size - 1) {
                        skipNext()
                    }
                }

                override fun error(mediaPlayer: MediaPlayer) {
                    _isPlaying.value = false
                    stopPositionUpdates()
                }
            })
        }
    }

    // --- Playback Controls ---

    override fun play() {
        audioComponent?.mediaPlayer()?.let { mp ->
            if (mp.status().isPlayable) {
                mp.controls().play()
            } else {
                // If no media is loaded, try to play current song from queue
                _currentSong.value?.streamUrl?.let { url ->
                    mp.media().play(url)
                }
            }
        }
    }

    override fun pause() {
        audioComponent?.mediaPlayer()?.controls()?.pause()
    }

    override fun seekTo(positionMs: Long) {
        audioComponent?.mediaPlayer()?.let { mp ->
            val dur = mp.media().info()?.duration() ?: return
            if (dur > 0) {
                mp.controls().setPosition(positionMs.toFloat() / dur.toFloat())
                _currentPosition.value = positionMs
            }
        }
    }

    override fun skipNext() {
        if (currentIndex < queue.size - 1) {
            currentIndex++
            playCurrentQueueItem()
        }
    }

    override fun skipPrevious() {
        // If more than 3 seconds in, restart; otherwise go to previous
        if (_currentPosition.value > 3000 && currentIndex > 0) {
            seekTo(0)
        } else if (currentIndex > 0) {
            currentIndex--
            playCurrentQueueItem()
        } else {
            seekTo(0)
        }
    }

    override fun setQueue(songs: List<SongInfo>, startIndex: Int) {
        queue = songs
        currentIndex = startIndex.coerceIn(0, songs.size - 1)
        playCurrentQueueItem()
    }

    override fun setVolume(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        _volume.value = clamped
        audioComponent?.mediaPlayer()?.audio()?.setVolume((clamped * 200).toInt())
    }

    override fun release() {
        stopPositionUpdates()
        audioComponent?.mediaPlayer()?.release()
        audioComponent?.release()
        audioComponent = null
        scope.cancel()
    }

    // --- Internal ---

    private fun playCurrentQueueItem() {
        val song = queue.getOrNull(currentIndex) ?: return
        _currentSong.value = song
        _currentPosition.value = 0L

        song.streamUrl?.let { url ->
            audioComponent?.mediaPlayer()?.media()?.play(url)
        }
    }

    private fun startPositionUpdates() {
        stopPositionUpdates()
        positionUpdateJob = scope.launch {
            while (isActive) {
                audioComponent?.mediaPlayer()?.let { mp ->
                    val dur = mp.media().info()?.duration() ?: 0L
                    val pos = mp.status().position()
                    _currentPosition.value = (pos * dur).toLong()
                    _duration.value = dur
                }
                delay(250) // Update 4x per second
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }
}
