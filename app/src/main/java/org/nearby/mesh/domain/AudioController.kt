package org.nearby.mesh.domain

import java.io.File

/**
 * Domain contract for voice note recording and playback.
 * Enforces architectural separation between UI and the voice subsystem (Rules.md §3).
 */
interface AudioController {
    val isRecording: Boolean
    val isPlaying: Boolean
    val currentPlayingPath: String?

    fun startRecording(outputFile: File): Boolean
    fun stopRecording(): Long
    fun cancelRecording()
    fun playVoiceNote(filePath: String, onComplete: () -> Unit = {}): Boolean
    fun pauseVoiceNote()
    fun stopVoiceNote()
}
