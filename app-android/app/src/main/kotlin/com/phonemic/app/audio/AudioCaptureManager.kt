package com.phonemic.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.phonemic.app.network.UdpAudioSender
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class AudioCaptureManager(
    private val sender: UdpAudioSender,
    private val onDbUpdate: (Float) -> Unit
) {
    private val tag = "AudioCaptureManager"

    val sampleRate = 48000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // 10ms buffer at 48kHz = 480 samples = 960 bytes
    private val chunkSamples = 480
    private val chunkBytes = chunkSamples * 2

    @Volatile
    var isRecording = false
        private set

    @Volatile
    var isMuted = false

    @Volatile
    var gain: Float = 1.0f

    @Volatile
    var isNoiseSuppressorEnabled = false
        set(value) {
            field = value
            noiseSuppressor?.enabled = value
        }

    @Volatile
    var isAcousticEchoCancelerEnabled = false
        set(value) {
            field = value
            echoCanceler?.enabled = value
        }

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var recordingThread: Thread? = null

    val isNsSupported: Boolean get() = NoiseSuppressor.isAvailable()
    val isAecSupported: Boolean get() = AcousticEchoCanceler.isAvailable()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isRecording) return true

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = max(minBufferSize, chunkBytes * 4)

        try {
            // VOICE_RECOGNITION provides studio-clear, uncompressed speech frequency response without Bluetooth telephony downsampling
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            val record = audioRecord ?: return false
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(tag, "AudioRecord failed to initialize")
                return false
            }

            val sessionId = record.audioSessionId

            // Attach hardware noise suppressor if available
            if (NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {
                        enabled = isNoiseSuppressorEnabled
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Could not create NoiseSuppressor: ${e.message}")
                }
            }

            // Attach hardware acoustic echo canceler if available
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                        enabled = isAcousticEchoCancelerEnabled
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Could not create AcousticEchoCanceler: ${e.message}")
                }
            }

            record.startRecording()
            isRecording = true

            recordingThread = Thread({ recordLoop(record) }, "PhoneMicRecordThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            return true

        } catch (e: Exception) {
            Log.e(tag, "Failed to start AudioRecord: ${e.message}")
            stop()
            return false
        }
    }

    private fun recordLoop(record: AudioRecord) {
        val shortBuffer = ShortArray(chunkSamples)
        val byteBuffer = ByteBuffer.allocate(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
        val silenceBytes = ByteArray(chunkBytes) // all zeros

        var frameCounter = 0

        while (isRecording) {
            val shortsRead = record.read(shortBuffer, 0, chunkSamples)
            if (shortsRead <= 0) continue

            if (isMuted) {
                // When muted, send silence packets to keep UDP pacing steady
                sender.send(silenceBytes, chunkBytes)
                if (++frameCounter % 5 == 0) {
                    onDbUpdate(-96f)
                }
                continue
            }

            // Apply gain and calculate RMS
            val currentGain = gain
            var sumSquare = 0.0
            byteBuffer.clear()

            for (i in 0 until shortsRead) {
                var sample = shortBuffer[i].toFloat()
                if (currentGain != 1.0f) {
                    sample *= currentGain
                    sample = max(-32768f, min(32767f, sample))
                }
                val sShort = sample.toInt().toShort()
                byteBuffer.putShort(sShort)
                sumSquare += sShort * sShort
            }

            // Send processed PCM bytes
            sender.send(byteBuffer.array(), shortsRead * 2)

            // Update RMS dB level (~20 times a second)
            if (++frameCounter % 5 == 0) {
                val rms = sqrt(sumSquare / shortsRead)
                val db = if (rms > 1e-4) {
                    20.0 * log10(rms / 32767.0)
                } else {
                    -96.0
                }
                onDbUpdate(max(-96f, min(0f, db.toFloat())))
            }
        }
    }

    fun stop() {
        isRecording = false
        recordingThread?.interrupt()
        recordingThread = null

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(tag, "Error stopping AudioRecord: ${e.message}")
        }

        try {
            noiseSuppressor?.release()
        } catch (e: Exception) {
            Log.w(tag, "Error releasing NoiseSuppressor: ${e.message}")
        } finally {
            noiseSuppressor = null
        }

        try {
            echoCanceler?.release()
        } catch (e: Exception) {
            Log.w(tag, "Error releasing AcousticEchoCanceler: ${e.message}")
        } finally {
            echoCanceler = null
        }

        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(tag, "Error releasing AudioRecord: ${e.message}")
        } finally {
            audioRecord = null
        }

        onDbUpdate(-96f)
    }
}
