package com.example.realtimeststranslate.audio

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SCO/HFP 錄音器：優先以 16 kHz (mSBC) 錄製，若設備僅支援 8 kHz (CVSD) 則回退。
 * Frame 長度固定為 100 ms，透過 callback 送往雲端 STT。
 */
class ScoRecorder(
    private val scope: CoroutineScope,
    private val frameDurationMillis: Int = 100,
    private val preferredInput: AudioDeviceInfo?,
    private val enableAec: Boolean,
    private val enableNs: Boolean,
    private val enableAgc: Boolean,
    private val onSampleRateChanged: (Int) -> Unit,
    private val onFrameCaptured: (ByteArray) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (running.get()) return
        running.set(true)
        recordJob = scope.launch(Dispatchers.IO) {
            try {
                val candidateRates = listOf(16_000, 8_000)
                var created: AudioRecord? = null
                var sampleRate = 16_000
                for (rate in candidateRates) {
                    val bufferSize = AudioRecord.getMinBufferSize(
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                        continue
                    }
                    try {
                        val frameBytes = rate / 1000 * frameDurationMillis * 2
                        val audioBufferSize = maxOf(bufferSize, frameBytes * 2)
                        val builder = AudioRecord.Builder()
                            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(rate)
                                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                    .build()
                            )
                            .setBufferSizeInBytes(audioBufferSize)
                        preferredInput?.let { input ->
                            builder.setPreferredDevice(input)
                        }
                        val recorder = builder.build()
                        if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                            created = recorder
                            sampleRate = rate
                            break
                        } else {
                            recorder.release()
                        }
                    } catch (t: Throwable) {
                        // 若該取樣率建立失敗，繼續嘗試下一個。
                    }
                }
                if (created == null) {
                    throw IllegalStateException("無法建立 AudioRecord，請檢查 SCO 路徑")
                }
                audioRecord = created
                onSampleRateChanged(sampleRate)
                maybeAttachProcessors(created)
                created.startRecording()
                val frameSize = sampleRate / 1000 * frameDurationMillis * 2
                val buffer = ByteArray(frameSize)
                while (isActive && running.get()) {
                    val read = created.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val frame = buffer.copyOf(read)
                        onFrameCaptured(frame)
                    }
                }
                created.stop()
            } catch (t: Throwable) {
                onError(t)
            } finally {
                running.set(false)
                release()
            }
        }
    }

    fun stop() {
        running.set(false)
        scope.launch {
            recordJob?.cancelAndJoin()
            recordJob = null
        }
    }

    private fun maybeAttachProcessors(record: AudioRecord) {
        // 若裝置支援則啟用 AEC/NS/AGC，提供使用者設定。
        if (enableAec && AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
        }
        if (enableNs && NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
        }
        if (enableAgc && AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(record.audioSessionId)?.apply { enabled = true }
        }
    }

    fun release() {
        audioRecord?.release()
        audioRecord = null
    }
}
