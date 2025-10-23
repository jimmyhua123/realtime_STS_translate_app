package com.example.realtimeststranslate.util

import kotlin.math.abs

/**
 * 簡易能量門檻：避免背景雜訊觸發雲端 STT。
 */
class RtcVad(
    private val threshold: Int = 1200,
    private val requiredSpeechFrames: Int = 3,
    private val requiredSilenceFrames: Int = 5
) {
    private var speechCounter = 0
    private var silenceCounter = 0

    fun isSpeech(frame: ShortArray): Boolean {
        val energy = frame.fold(0.0) { acc, sample -> acc + abs(sample.toInt()) }
        val average = energy / frame.size
        val speech = average > threshold
        if (speech) {
            speechCounter++
            silenceCounter = 0
        } else {
            silenceCounter++
            speechCounter = 0
        }
        return speech && speechCounter >= requiredSpeechFrames
    }

    fun isSilence(): Boolean = silenceCounter >= requiredSilenceFrames
}
