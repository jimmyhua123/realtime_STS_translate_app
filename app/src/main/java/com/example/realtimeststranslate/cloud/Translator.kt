package com.example.realtimeststranslate.cloud

import com.example.realtimeststranslate.BuildConfig
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.cloud.translate.v3.DetectLanguageRequest
import com.google.cloud.translate.v3.DetectLanguageResponse
import com.google.cloud.translate.v3.LocationName
import com.google.cloud.translate.v3.TranslateTextRequest
import com.google.cloud.translate.v3.TranslationServiceClient
import com.google.cloud.translate.v3.TranslationServiceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cloud Translation v3：語言偵測與翻譯。
 */
class Translator(private val credentialProvider: CredentialProvider) {
    private val parent: String = LocationName.of(BuildConfig.GCP_PROJECT_ID, BuildConfig.GCP_LOCATION).toString()

    suspend fun detectLanguage(text: String): String? {
        if (text.isBlank()) return null
        val request = DetectLanguageRequest.newBuilder()
            .setParent(parent)
            .setContent(text)
            .build()
        return withClient { client ->
            val response: DetectLanguageResponse = client.detectLanguage(request)
            response.languagesList.maxByOrNull { it.confidence }?.languageCode
        }
    }

    suspend fun translateText(text: String, sourceLanguage: String?, targetLanguage: String): String {
        if (text.isBlank()) return ""
        val builder = TranslateTextRequest.newBuilder()
            .setParent(parent)
            .addContents(text)
            .setTargetLanguageCode(targetLanguage)
        if (!sourceLanguage.isNullOrBlank()) {
            builder.sourceLanguageCode = sourceLanguage
        }
        val request = builder.build()
        return withClient { client ->
            val response = client.translateText(request)
            response.translationsList.firstOrNull()?.translatedText ?: ""
        }
    }

    private suspend fun <T> withClient(block: (TranslationServiceClient) -> T): T {
        return withContext(Dispatchers.IO) {
            val credentials = credentialProvider.provideCredentials(TRANSLATE_SCOPES)
            val settings = TranslationServiceSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build()
            TranslationServiceClient.create(settings).use(block)
        }
    }

    companion object {
        private val TRANSLATE_SCOPES = listOf("https://www.googleapis.com/auth/cloud-translation")
    }
}
