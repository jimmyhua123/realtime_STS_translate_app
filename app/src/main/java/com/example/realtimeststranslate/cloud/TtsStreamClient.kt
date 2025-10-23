package com.example.realtimeststranslate.cloud

import com.google.api.gax.core.FixedCredentialsProvider
import com.google.cloud.texttospeech.v1.AudioConfig
import com.google.cloud.texttospeech.v1.AudioEncoding
import com.google.cloud.texttospeech.v1.SynthesisInput
import com.google.cloud.texttospeech.v1.TextToSpeechClient
import com.google.cloud.texttospeech.v1.TextToSpeechSettings
import com.google.cloud.texttospeech.v1.VoiceSelectionParams
import com.google.cloud.texttospeech.v1.streaming.StreamingSynthesizeRequest
import com.google.cloud.texttospeech.v1.streaming.StreamingSynthesizeResponse
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Text-to-Speech 串流合成：接收音框即刻餵給 ScoPlayer 減少延遲。
 */
class TtsStreamClient(
    private val credentialProvider: CredentialProvider,
    private val scope: CoroutineScope,
    private val onChunk: (ByteArray) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    fun synthesize(text: String, languageCode: String, preferredVoice: String?, sampleRate: Int) {
        if (text.isBlank()) return
        scope.launch(Dispatchers.IO) {
            try {
                val credentials = credentialProvider.provideCredentials(TTS_SCOPES)
                val settings = TextToSpeechSettings.newBuilder()
                    .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                    .build()
                TextToSpeechClient.create(settings).use { client ->
                    val responseObserver = object : StreamObserver<StreamingSynthesizeResponse> {
                        override fun onNext(value: StreamingSynthesizeResponse) {
                            val audio = value.audioChunk
                            if (!audio.isEmpty) {
                                onChunk(audio.toByteArray())
                            }
                        }

                        override fun onError(t: Throwable) {
                            onError(t)
                        }

                        override fun onCompleted() {}
                    }
                    val requestObserver = client.streamingSynthesize(responseObserver)
                    val voiceName = preferredVoice ?: "ch_jp_chirp_3_hd"
                    val voice = VoiceSelectionParams.newBuilder()
                        .setLanguageCode(languageCode)
                        .setName(voiceName)
                        .build()
                    val audioConfig = AudioConfig.newBuilder()
                        .setAudioEncoding(AudioEncoding.LINEAR16)
                        .setSampleRateHertz(sampleRate)
                        .build()
                    val input = SynthesisInput.newBuilder().setText(text).build()
                    val request = StreamingSynthesizeRequest.newBuilder()
                        .setInput(input)
                        .setVoice(voice)
                        .setAudioConfig(audioConfig)
                        .build()
                    requestObserver.onNext(request)
                    requestObserver.onCompleted()
                }
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    companion object {
        private val TTS_SCOPES = listOf("https://www.googleapis.com/auth/cloud-platform")
    }
}
