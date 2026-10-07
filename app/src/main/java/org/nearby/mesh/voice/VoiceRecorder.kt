package org.nearby.mesh.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.io.IOException

/**
 * High-efficiency audio recorder producing compact AAC-encoded audio files (.m4a).
 */
class VoiceRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var recordingStartTimeMs: Long = 0L

    val isRecording: Boolean
        get() = mediaRecorder != null

    /**
     * Starts recording voice audio to the designated output [File].
     * Returns true if recording started successfully, false otherwise.
     */
    @Synchronized
    fun startRecording(outputFile: File): Boolean {
        if (isRecording) {
            cancelRecording()
        }

        return try {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) {
                outputFile.delete()
            }

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(32_000) // 32 kbps compact voice bitrate
                setAudioSamplingRate(24_000)    // 24 kHz speech sampling rate
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            currentOutputFile = outputFile
            recordingStartTimeMs = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            cancelRecording()
            false
        }
    }

    /**
     * Stops the active recording and returns the total duration in milliseconds.
     * Returns 0L if no recording was in progress or on failure.
     */
    @Synchronized
    fun stopRecording(): Long {
        val recorder = mediaRecorder ?: return 0L
        val startTime = recordingStartTimeMs
        val durationMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)

        try {
            recorder.stop()
        } catch (e: Exception) {
            // Can happen if stopped immediately after start
            currentOutputFile?.delete()
            return 0L
        } finally {
            try {
                recorder.release()
            } catch (ignored: Exception) {}
            mediaRecorder = null
            currentOutputFile = null
            recordingStartTimeMs = 0L
        }

        return durationMs
    }

    /**
     * Cancels active recording and deletes the partial audio file.
     */
    @Synchronized
    fun cancelRecording() {
        try {
            mediaRecorder?.let {
                try {
                    it.stop()
                } catch (ignored: Exception) {}
                it.release()
            }
        } catch (ignored: Exception) {}

        currentOutputFile?.let {
            if (it.exists()) {
                it.delete()
            }
        }

        mediaRecorder = null
        currentOutputFile = null
        recordingStartTimeMs = 0L
    }

    /**
     * Gets current audio amplitude for dynamic waveform visualizations (0..32767).
     */
    fun getMaxAmplitude(): Int {
        return try {
            mediaRecorder?.maxAmplitude ?: 0
        } catch (e: Exception) {
            0
        }
    }
}
