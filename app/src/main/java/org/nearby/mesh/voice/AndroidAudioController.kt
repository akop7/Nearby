package org.nearby.mesh.voice

import android.content.Context
import org.nearby.mesh.domain.AudioController
import java.io.File

/**
 * Android framework implementation of [AudioController] wrapping [VoiceRecorder] and [VoicePlayer].
 */
class AndroidAudioController(context: Context) : AudioController {

    private val recorder = VoiceRecorder(context)
    private val player = VoicePlayer()

    override val isRecording: Boolean
        get() = recorder.isRecording

    override val isPlaying: Boolean
        get() = player.isPlaying

    override val currentPlayingPath: String?
        get() = player.currentPlayingPath

    override fun startRecording(outputFile: File): Boolean {
        player.stop()
        return recorder.startRecording(outputFile)
    }

    override fun stopRecording(): Long {
        return recorder.stopRecording()
    }

    override fun cancelRecording() {
        recorder.cancelRecording()
    }

    override fun playVoiceNote(filePath: String, onComplete: () -> Unit): Boolean {
        return player.play(filePath, onComplete)
    }

    override fun pauseVoiceNote() {
        player.pause()
    }

    override fun stopVoiceNote() {
        player.stop()
    }
}
