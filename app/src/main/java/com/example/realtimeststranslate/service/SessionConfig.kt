package com.example.realtimeststranslate.service

import com.example.realtimeststranslate.ui.SegmentationStrategy

data class SessionConfig(
    val useBluetoothMic: Boolean,
    val sourceLanguageCode: String?,
    val autoDetectLanguage: Boolean,
    val targetLanguageCode: String,
    val frameDurationMillis: Int = 100,
    val enableAec: Boolean = true,
    val enableNs: Boolean = true,
    val enableAgc: Boolean = true,
    val preferredVoice: String? = null,
    val segmentationStrategy: SegmentationStrategy = SegmentationStrategy.PUNCTUATION
)
