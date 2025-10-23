package com.example.realtimeststranslate.ui

import androidx.lifecycle.ViewModel
import com.example.realtimeststranslate.service.ServiceState
import com.example.realtimeststranslate.service.SessionConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceUi>>(emptyList())
    val devices: StateFlow<List<DeviceUi>> = _devices.asStateFlow()

    fun updatePermissions(record: Boolean, bluetooth: Boolean, notification: Boolean) {
        _uiState.value = _uiState.value.copy(
            hasRecordPermission = record,
            hasBluetoothPermission = bluetooth,
            hasNotificationPermission = notification
        )
    }

    fun selectInput(selection: InputSelection) {
        _uiState.value = _uiState.value.copy(inputSelection = selection)
    }

    fun selectSource(option: LanguageOption) {
        _uiState.value = _uiState.value.copy(
            selectedSource = option,
            autoDetect = option.languageCode == null
        )
    }

    fun selectTarget(option: LanguageOption) {
        _uiState.value = _uiState.value.copy(selectedTarget = option)
    }

    fun toggleAec(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(enableAec = enabled)
    }

    fun toggleNs(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(enableNs = enabled)
    }

    fun toggleAgc(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(enableAgc = enabled)
    }

    fun updateFrameDuration(value: Int) {
        _uiState.value = _uiState.value.copy(frameDurationMillis = value)
    }

    fun updateSegmentation(strategy: SegmentationStrategy) {
        _uiState.value = _uiState.value.copy(segmentationStrategy = strategy)
    }

    fun onServiceStateChanged(state: ServiceState) {
        _devices.value = state.availableDevices
        _uiState.value = _uiState.value.copy(
            sttInterim = state.sttInterim,
            sttFinal = state.sttFinal,
            translation = state.translation,
            routeDescription = state.routeDescription,
            sampleRate = state.sampleRate,
            cloudStatus = buildCloudStatus(state),
            latencyMillis = state.latestLatencyMillis,
            statusMessage = state.statusMessage,
            segmentationStrategy = state.segmentationStrategy
        )
    }

    private fun buildCloudStatus(state: ServiceState): String {
        val stt = if (state.sttConnected) "STT:Connected" else "STT:Idle"
        val tts = if (state.ttsConnected) "TTS:Connected" else "TTS:Idle"
        return "$stt / $tts"
    }

    fun buildSessionConfig(): SessionConfig {
        val ui = _uiState.value
        return SessionConfig(
            useBluetoothMic = ui.inputSelection == InputSelection.BLUETOOTH_MIC,
            sourceLanguageCode = ui.selectedSource.languageCode,
            autoDetectLanguage = ui.autoDetect,
            targetLanguageCode = ui.selectedTarget.languageCode ?: "zh-TW",
            frameDurationMillis = ui.frameDurationMillis,
            enableAec = ui.enableAec,
            enableNs = ui.enableNs,
            enableAgc = ui.enableAgc,
            segmentationStrategy = ui.segmentationStrategy
        )
    }
}
