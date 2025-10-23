package com.example.realtimeststranslate.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 音訊路由管理：僅使用 setCommunicationDevice() 指向藍牙 SCO/HFP 路徑。
 * 在 UI 顯示 available 與 current communication device。
 */
class AudioRouter(
    context: Context,
    private val externalScope: CoroutineScope
) {
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val _availableDevices = MutableStateFlow<List<AudioDeviceInfo>>(emptyList())
    val availableDevices: StateFlow<List<AudioDeviceInfo>> = _availableDevices.asStateFlow()

    private val _currentDevice = MutableStateFlow(audioManager.communicationDevice)
    val currentDevice: StateFlow<AudioDeviceInfo?> = _currentDevice.asStateFlow()

    private val deviceCallback = object : AudioManager.AudioDeviceCallback() {
        override fun onCommunicationDeviceChanged(device: AudioDeviceInfo?) {
            // 取得最新的通話裝置並通知 UI。
            _currentDevice.value = device
        }

        override fun onAvailableCommunicationDevicesChanged(devices: MutableList<AudioDeviceInfo>) {
            // 更新可用的通話裝置列表。
            _availableDevices.value = devices.toList()
        }
    }

    fun start() {
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        refreshDevices()
    }

    fun stop() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    fun refreshDevices() {
        // 取得可用通話裝置 (TYPE_BLUETOOTH_SCO / 內建麥克風等)。
        val devices = audioManager.getAvailableCommunicationDevices()
        _availableDevices.value = devices.toList()
        _currentDevice.value = audioManager.communicationDevice
    }

    suspend fun setCommunicationDevice(target: AudioDeviceInfo, timeoutMillis: Long = 30_000L): Boolean {
        // 呼叫 setCommunicationDevice，等待 currentCommunicationDevice 成功更新。
        val success = audioManager.setCommunicationDevice(target)
        if (!success) {
            return false
        }
        val awaited = withTimeoutOrNull(timeoutMillis) {
            currentDevice.filter { it?.id == target.id }.first()
        }
        return awaited != null
    }

    fun resetCommunicationDevice() {
        audioManager.clearCommunicationDevice()
    }

    fun findBluetoothScoDevice(): AudioDeviceInfo? {
        return availableDevices.value.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }

    fun findBuiltInMic(): AudioDeviceInfo? {
        return availableDevices.value.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    fun supportsSplitInputOutput(): Boolean {
        val scoDevice = findBluetoothScoDevice() ?: return false
        val builtinMic = findBuiltInMic() ?: return false
        // 部分裝置不允許同時以藍牙耳機輸出並用手機麥克風輸入，這裡以 device role 判斷並回傳結果供 UI fallback。
        val scoHasOutput = scoDevice.isSink
        val builtInHasInput = builtinMic.isSource
        return scoHasOutput && builtInHasInput
    }

    /**
     * 提供給 ViewModel 觸發非同步裝置切換並更新狀態。
     */
    fun switchToDevice(target: AudioDeviceInfo, onResult: (Boolean) -> Unit) {
        externalScope.launch {
            val ok = setCommunicationDevice(target)
            onResult(ok)
        }
    }
}
