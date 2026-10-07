package org.nearby.mesh.voice

import android.media.MediaPlayer
import java.io.File

/**
 * Audio playback controller for recorded voice notes.
 */
class VoicePlayer {

    private var mediaPlayer: MediaPlayer? = null
    var currentPlayingPath: String? = null
        private set

    val isPlaying: Boolean
        get() = try {
            mediaPlayer?.isPlaying == true
        } catch (e: Exception) {
            false
        }

    val currentPositionMs: Long
        get() = try {
            mediaPlayer?.currentPosition?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        }

    val durationMs: Long
        get() = try {
            mediaPlayer?.duration?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        }

    /**
     * Starts playing the audio file at [filePath].
     */
    @Synchronized
    fun play(filePath: String, onCompletion: () -> Unit = {}): Boolean {
        stop()

        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) {
            return false
        }

        return try {
            val player = MediaPlayer()
            player.setDataSource(filePath)
            player.prepare()
            player.setOnCompletionListener {
                currentPlayingPath = null
                onCompletion()
            }
            player.start()
            mediaPlayer = player
            currentPlayingPath = filePath
            true
        } catch (e: Exception) {
            stop()
            false
        }
    }

    /**
     * Pauses the current audio playback.
     */
    @Synchronized
    fun pause() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (ignored: Exception) {}
    }

    /**
     * Resumes playback if paused.
     */
    @Synchronized
    fun resume() {
        try {
            if (mediaPlayer != null && !isPlaying) {
                mediaPlayer?.start()
            }
        } catch (ignored: Exception) {}
    }

    /**
     * Seeks playback to the specified position in milliseconds.
     */
    @Synchronized
    fun seekTo(positionMs: Long) {
        try {
            mediaPlayer?.seekTo(positionMs.toInt())
        } catch (ignored: Exception) {}
    }

    /**
     * Stops playback and releases media resources.
     */
    @Synchronized
    fun stop() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (ignored: Exception) {}

        mediaPlayer = null
        currentPlayingPath = null
    }
}
