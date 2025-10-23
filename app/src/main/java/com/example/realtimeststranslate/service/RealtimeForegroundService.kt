package com.example.realtimeststranslate.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.example.realtimeststranslate.R
import com.example.realtimeststranslate.audio.AudioRouter
import com.example.realtimeststranslate.audio.ScoPlayer
import com.example.realtimeststranslate.audio.ScoRecorder
import com.example.realtimeststranslate.cloud.CredentialProvider
import com.example.realtimeststranslate.cloud.SttStreamClient
import com.example.realtimeststranslate.cloud.Translator
import com.example.realtimeststranslate.cloud.TtsStreamClient
import com.example.realtimeststranslate.ui.MainActivity
import com.example.realtimeststranslate.ui.SegmentationStrategy
import com.example.realtimeststranslate.util.RtcVad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前景服務：管理音訊路由、錄音、雲端串流與播放。
 */
class RealtimeForegroundService : Service() {
    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var audioRouter: AudioRouter
    private lateinit var credentialProvider: CredentialProvider
    private lateinit var translator: Translator
    private lateinit var sttClient: SttStreamClient
    private lateinit var ttsClient: TtsStreamClient

    private var recorder: ScoRecorder? = null
    private var player: ScoPlayer? = null
    private var currentConfig: SessionConfig? = null
    private var currentSampleRate: Int = 16_000
    private var preferredVoice: String? = null
    private val vad = RtcVad()
    private var awaitingFirstTtsFrame = AtomicBoolean(false)

    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    inner class LocalBinder : Binder() {
        fun service(): RealtimeForegroundService = this@RealtimeForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        audioRouter = AudioRouter(this, scope)
        credentialProvider = CredentialProvider(this)
        translator = Translator(credentialProvider)
        sttClient = SttStreamClient(
            scope,
            credentialProvider,
            onInterim = { interim -> updateState { it.copy(sttInterim = interim) } },
            onFinal = { final -> onFinalTranscript(final) },
            onError = { throwable ->
                updateState { it.copy(statusMessage = "STT 錯誤：${throwable.message}") }
            }
        )
        ttsClient = TtsStreamClient(
            credentialProvider,
            scope,
            onChunk = { chunk ->
                player?.enqueueFrame(chunk)
                if (awaitingFirstTtsFrame.compareAndSet(true, false)) {
                    val latency = SystemClock.elapsedRealtime() - (_state.value.speechStartTimestamp)
                    updateState { it.copy(latestLatencyMillis = latency, speechStartTimestamp = 0L) }
                }
            },
            onError = { throwable ->
                updateState { it.copy(statusMessage = "TTS 錯誤：${throwable.message}") }
            }
        )
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("系統啟動中"))
        observeAudioRouter()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        recorder?.stop()
        player?.stop()
        audioRouter.stop()
    }

    private fun observeAudioRouter() {
        audioRouter.start()
        scope.launch {
            audioRouter.availableDevices.collectLatest { devices ->
                updateState {
                    it.copy(
                        availableDevices = devices.map { device ->
                            DeviceUi(
                                id = device.id,
                                name = deviceProductName(device),
                                type = device.type,
                                isSelected = _state.value.currentDevice?.id == device.id,
                                supportsSplitInput = audioRouter.supportsSplitInputOutput()
                            )
                        }
                    )
                }
            }
        }
        scope.launch {
            audioRouter.currentDevice.collectLatest { device ->
                val description = when (device?.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "SCO/HFP"
                    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "內建麥克風"
                    else -> device?.type?.toString() ?: "未連線"
                }
                updateState {
                    it.copy(
                        currentDevice = device?.let {
                            DeviceUi(
                                id = it.id,
                                name = deviceProductName(it),
                                type = it.type,
                                isSelected = true,
                                supportsSplitInput = audioRouter.supportsSplitInputOutput()
                            )
                        },
                        routeDescription = description
                    )
                }
            }
        }
    }

    fun startSession(config: SessionConfig) {
        currentConfig = config
        preferredVoice = config.preferredVoice
        scope.launch {
            val bluetooth = audioRouter.findBluetoothScoDevice()
            if (bluetooth == null) {
                updateState { it.copy(statusMessage = "找不到藍牙 SCO 裝置，請確認耳機已連線") }
                return@launch
            }
            val switchResult = withContext(Dispatchers.Main) {
                audioRouter.setCommunicationDevice(bluetooth)
            }
            if (!switchResult) {
                updateState { it.copy(statusMessage = "無法切換至藍牙通話裝置") }
                return@launch
            }
            val supportsSplit = audioRouter.supportsSplitInputOutput()
            val preferredInput = if (config.useBluetoothMic || !supportsSplit) {
                bluetooth
            } else {
                audioRouter.findBuiltInMic() ?: bluetooth
            }
            if (!supportsSplit && !config.useBluetoothMic) {
                updateState { it.copy(statusMessage = "此裝置無法同時使用手機麥克風，已回退至藍牙麥克風") }
            }
            restartRecorder(preferredInput, config)
            startStt(config)
            awaitingFirstTtsFrame.set(false)
            updateState {
                it.copy(
                    statusMessage = "前景服務已啟動（分句：${segmentationDescription(config.segmentationStrategy)}）",
                    segmentationStrategy = config.segmentationStrategy
                )
            }
        }
    }

    fun stopSession() {
        scope.launch {
            recorder?.stop()
            player?.stop()
            sttClient.stop()
            audioRouter.resetCommunicationDevice()
            updateState { ServiceState() }
        }
    }

    private fun restartRecorder(preferredInput: AudioDeviceInfo?, config: SessionConfig) {
        recorder?.stop()
        recorder = ScoRecorder(
            scope = scope,
            frameDurationMillis = config.frameDurationMillis,
            preferredInput = preferredInput,
            enableAec = config.enableAec,
            enableNs = config.enableNs,
            enableAgc = config.enableAgc,
            onSampleRateChanged = { sampleRate ->
                currentSampleRate = sampleRate
                updateState { it.copy(sampleRate = sampleRate) }
                restartPlayer(sampleRate, config.frameDurationMillis)
            },
            onFrameCaptured = { frame -> onAudioFrameCaptured(frame) },
            onError = { throwable ->
                updateState { it.copy(statusMessage = "錄音錯誤：${throwable.message}") }
            }
        )
        recorder?.start()
    }

    private fun restartPlayer(sampleRate: Int, frameDuration: Int) {
        player?.stop()
        player = ScoPlayer(
            scope = scope,
            sampleRate = sampleRate,
            frameDurationMillis = frameDuration,
            onError = { throwable ->
                updateState { it.copy(statusMessage = "播放錯誤：${throwable.message}") }
            }
        ).also { it.start() }
    }

    private fun onAudioFrameCaptured(frame: ByteArray) {
        val shortArray = ShortArray(frame.size / 2)
        for (i in shortArray.indices) {
            val low = frame[i * 2].toInt() and 0xFF
            val high = frame[i * 2 + 1].toInt()
            shortArray[i] = ((high shl 8) or low).toShort()
        }
        val speech = vad.isSpeech(shortArray)
        if (speech) {
            if (_state.value.speechStartTimestamp == 0L) {
                updateState { it.copy(speechStartTimestamp = SystemClock.elapsedRealtime()) }
            }
        } else if (currentConfig?.segmentationStrategy == SegmentationStrategy.SILENCE && vad.isSilence()) {
            if (_state.value.sttInterim.isNotBlank()) {
                updateState { it.copy(statusMessage = "偵測靜音，等待最終字幕") }
            }
        }
        sttClient.sendAudioFrame(frame)
    }

    private fun onFinalTranscript(finalText: String) {
        scope.launch {
            updateState { it.copy(sttFinal = finalText, sttInterim = "", sttConnected = true) }
            val config = currentConfig ?: return@launch
            val sourceLanguage = if (config.autoDetectLanguage) {
                translator.detectLanguage(finalText)
            } else {
                config.sourceLanguageCode
            }
            val translated = translator.translateText(finalText, sourceLanguage, config.targetLanguageCode)
            updateState { it.copy(translation = translated) }
            awaitingFirstTtsFrame.set(true)
            ttsClient.synthesize(
                text = translated,
                languageCode = config.targetLanguageCode,
                preferredVoice = preferredVoice,
                sampleRate = currentSampleRate
            )
            updateState { it.copy(ttsConnected = true) }
        }
    }

    private fun startStt(config: SessionConfig) {
        sttClient.start(config.sourceLanguageCode.takeUnless { config.autoDetectLanguage })
        updateState { it.copy(sttConnected = true, segmentationStrategy = config.segmentationStrategy) }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification_foreground)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun deviceProductName(device: AudioDeviceInfo): String {
        val product = device.productName?.toString() ?: "Unknown"
        val type = when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "內建麥克風"
            else -> device.type.toString()
        }
        return "$product ($type)"
    }

    private fun updateState(transform: (ServiceState) -> ServiceState) {
        _state.value = transform(_state.value)
        val text = _state.value.statusMessage.ifBlank { getString(R.string.notification_text) }
        val notification = buildNotification(text)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun segmentationDescription(strategy: SegmentationStrategy): String {
        return when (strategy) {
            SegmentationStrategy.PUNCTUATION -> "標點"
            SegmentationStrategy.SILENCE -> "靜音門檻"
        }
    }

    companion object {
        const val CHANNEL_ID = "realtime_sts_channel"
        const val NOTIFICATION_ID = 1001
    }
}
