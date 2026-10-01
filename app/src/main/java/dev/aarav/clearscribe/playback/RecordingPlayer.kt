package dev.aarav.clearscribe.playback

import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaybackState(
    val path: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
)

/**
 * Thin MediaPlayer wrapper for local WAV playback + scrubbing. One instance
 * per app (held in ClearScribeApp) since MediaPlayer itself is a single
 * native resource at a time.
 */
class RecordingPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private var tickJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state

    fun playOrToggle(path: String) {
        val current = mediaPlayer
        if (current != null && _state.value.path == path) {
            // Same file: toggle play/pause.
            if (current.isPlaying) current.pause() else current.start()
            _state.update { it.copy(isPlaying = current.isPlaying) }
            if (current.isPlaying) startTicking() else tickJob?.cancel()
            return
        }

        // Different file (or nothing loaded yet): swap the player out.
        release()
        val mp = MediaPlayer().apply {
            setDataSource(path)
            prepare() // fine for local files; off the main thread would be nicer for long files
            start()
        }
        mediaPlayer = mp
        _state.value = PlaybackState(path = path, isPlaying = true, positionMs = 0, durationMs = mp.duration)
        mp.setOnCompletionListener {
            _state.update { it.copy(isPlaying = false, positionMs = it.durationMs) }
            tickJob?.cancel()
        }
        startTicking()
    }

    fun seekTo(ms: Int) {
        mediaPlayer?.seekTo(ms)
        _state.update { it.copy(positionMs = ms) }
    }

    fun stop() {
        release()
        _state.value = PlaybackState()
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (true) {
                val mp = mediaPlayer ?: break
                _state.update { it.copy(positionMs = mp.currentPosition) }
                delay(200)
            }
        }
    }

    private fun release() {
        tickJob?.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
