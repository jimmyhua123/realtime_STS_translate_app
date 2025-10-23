package com.example.realtimeststranslate.ui

data class LanguageOption(val displayName: String, val languageCode: String?)

enum class InputSelection { BLUETOOTH_MIC, BUILTIN_MIC }

enum class SegmentationStrategy { PUNCTUATION, SILENCE }

data class MainUiState(
    val hasRecordPermission: Boolean = false,
    val hasBluetoothPermission: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val inputSelection: InputSelection = InputSelection.BLUETOOTH_MIC,
    val sourceLanguages: List<LanguageOption> = defaultSourceLanguages(),
    val targetLanguages: List<LanguageOption> = listOf(LanguageOption("繁體中文 (zh-TW)", "zh-TW")),
    val selectedSource: LanguageOption = sourceLanguages.first(),
    val selectedTarget: LanguageOption = LanguageOption("繁體中文 (zh-TW)", "zh-TW"),
    val autoDetect: Boolean = true,
    val frameDurationMillis: Int = 100,
    val enableAec: Boolean = true,
    val enableNs: Boolean = true,
    val enableAgc: Boolean = true,
    val segmentationStrategy: SegmentationStrategy = SegmentationStrategy.PUNCTUATION,
    val sttInterim: String = "",
    val sttFinal: String = "",
    val translation: String = "",
    val routeDescription: String = "未連線",
    val sampleRate: Int = 16_000,
    val cloudStatus: String = "Disconnected",
    val latencyMillis: Long? = null,
    val statusMessage: String = ""
)

fun defaultSourceLanguages(): List<LanguageOption> = listOf(
    LanguageOption("自動偵測", null),
    LanguageOption("英文 (en-US)", "en-US"),
    LanguageOption("日文 (ja-JP)", "ja-JP"),
    LanguageOption("韓文 (ko-KR)", "ko-KR"),
    LanguageOption("法文 (fr-FR)", "fr-FR")
)
