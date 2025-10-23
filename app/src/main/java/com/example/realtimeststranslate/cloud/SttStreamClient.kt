package com.example.realtimeststranslate.cloud

import com.google.cloud.speech.v2.AutoDetectDecodingConfig
import com.google.cloud.speech.v2.RecognitionConfig
import com.google.cloud.speech.v2.SpeechGrpc
import com.google.cloud.speech.v2.StreamingRecognitionConfig
import com.google.cloud.speech.v2.StreamingRecognizeRequest
import com.google.cloud.speech.v2.StreamingRecognizeResponse
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.StatusRuntimeException
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Speech-to-Text v2 gRPC 雙向串流客戶端。100 ms PCM frame 經由 sendAudioFrame() 傳送。
 */
class SttStreamClient(
    private val scope: CoroutineScope,
    private val credentialProvider: CredentialProvider,
    private val onInterim: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    private var channel: ManagedChannel? = null
    private var requestObserver: StreamObserver<StreamingRecognizeRequest>? = null
    private var languageCode: String? = null

    fun start(language: String?) {
        languageCode = language
        scope.launch(Dispatchers.IO) {
            try {
                val credentials = credentialProvider.provideCredentials(SPEECH_SCOPE)
                val managed = OkHttpChannelBuilder.forAddress(SPEECH_ENDPOINT, 443)
                    .useTransportSecurity()
                    .build()
                channel = managed
                val stub = SpeechGrpc.newStub(managed)
                    .withCallCredentials(io.grpc.auth.MoreCallCredentials.from(credentials))
                requestObserver = stub.streamingRecognize(object : StreamObserver<StreamingRecognizeResponse> {
                    override fun onNext(value: StreamingRecognizeResponse) {
                        val alternatives = value.resultsList
                        if (alternatives.isEmpty()) return
                        val top = alternatives.first()
                        val transcript = top.alternativesList.firstOrNull()?.transcript ?: ""
                        if (transcript.isEmpty()) return
                        if (top.isFinal) {
                            onFinal(transcript)
                        } else {
                            onInterim(transcript)
                        }
                    }

                    override fun onError(t: Throwable) {
                        onError(t)
                    }

                    override fun onCompleted() {}
                })
                val config = RecognitionConfig.newBuilder()
                    .setAutoDecodingConfig(AutoDetectDecodingConfig.newBuilder().build())
                    .apply {
                        languageCode?.let {
                            clearLanguageCodes()
                            addLanguageCodes(it)
                        }
                    }
                    .setModel("latest_short")
                    .build()
                val streamingConfig = StreamingRecognitionConfig.newBuilder()
                    .setConfig(config)
                    .setInterimResults(true)
                    .build()
                requestObserver?.onNext(
                    StreamingRecognizeRequest.newBuilder()
                        .setStreamingConfig(streamingConfig)
                        .build()
                )
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    fun sendAudioFrame(frame: ByteArray) {
        scope.launch(Dispatchers.IO) {
            try {
                requestObserver?.onNext(
                    StreamingRecognizeRequest.newBuilder()
                        .setAudioContent(ByteString.copyFrom(frame))
                        .build()
                )
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    suspend fun stop() {
        withContext(Dispatchers.IO) {
            try {
                requestObserver?.onCompleted()
            } catch (ignored: StatusRuntimeException) {
            }
            requestObserver = null
            channel?.shutdownNow()
            channel = null
        }
    }

    companion object {
        private const val SPEECH_ENDPOINT = "speech.googleapis.com"
        private val SPEECH_SCOPE = listOf("https://www.googleapis.com/auth/cloud-platform")
    }
}
