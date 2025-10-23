package com.example.realtimeststranslate.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SCO/HFP 播放器：以 VOICE_COMMUNICATION 參數播放雲端 TTS 的串流音框。
 */
class ScoPlayer(
    private val scope: CoroutineScope,
    private val sampleRate: Int,
    private val frameDurationMillis: Int = 100,
    private val onError: (Throwable) -> Unit
) {
    private val frameQueue = ArrayBlockingQueue<ByteArray>(50)
    private var audioTrack: AudioTrack? = null
    private var job: Job? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (running.get()) return
        running.set(true)
        job = scope.launch(Dispatchers.IO) {
            try {
                val frameSize = sampleRate / 1000 * frameDurationMillis * 2
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(frameSize * 4)
                    .build()
                audioTrack = track
                track.play()
                while (isActive && running.get()) {
                    val frame = frameQueue.take()
                    track.write(frame, 0, frame.size)
                }
                track.stop()
            } catch (t: Throwable) {
                onError(t)
            } finally {
                running.set(false)
                audioTrack?.release()
                audioTrack = null
                frameQueue.clear()
            }
        }
    }

    fun enqueueFrame(data: ByteArray) {
        if (!frameQueue.offer(data)) {
            // 若佇列塞滿，清空並避免延遲累積。
            frameQueue.clear()
            frameQueue.offer(data)
        }
    }

    fun stop() {
        running.set(false)
        scope.launch {
            job?.cancelAndJoin()
            job = null
        }
    }
}
