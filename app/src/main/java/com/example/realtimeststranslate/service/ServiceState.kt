package com.example.realtimeststranslate.service

import com.example.realtimeststranslate.ui.SegmentationStrategy

data class ServiceState(
    val availableDevices: List<DeviceUi> = emptyList(),
    val currentDevice: DeviceUi? = null,
    val sampleRate: Int = 16_000,
    val routeDescription: String = "未連線",
    val sttInterim: String = "",
    val sttFinal: String = "",
    val translation: String = "",
    val sttConnected: Boolean = false,
    val ttsConnected: Boolean = false,
    val speechStartTimestamp: Long = 0L,
    val latestLatencyMillis: Long? = null,
    val statusMessage: String = "",
    val segmentationStrategy: SegmentationStrategy = SegmentationStrategy.PUNCTUATION
)

data class DeviceUi(
    val id: Int,
    val name: String,
    val type: Int,
    val isSelected: Boolean,
    val supportsSplitInput: Boolean
)
