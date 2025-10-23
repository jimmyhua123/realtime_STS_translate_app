package com.example.realtimeststranslate.cloud

import android.content.Context
import com.example.realtimeststranslate.BuildConfig
import com.google.auth.oauth2.GoogleCredentials
import java.io.File
import java.io.FileInputStream

/**
 * 從安全儲存的服務帳戶憑證載入 GoogleCredentials。
 * 建議於啟動時將加密後的憑證解密到 app 專屬目錄，再由此處讀取。
 */
class CredentialProvider(private val context: Context) {
    @Throws(IllegalStateException::class)
    fun provideCredentials(scopes: List<String>): GoogleCredentials {
        val credentialFile = File(context.filesDir, BuildConfig.GCP_CREDENTIAL_FILE)
        if (!credentialFile.exists()) {
            throw IllegalStateException("找不到服務帳戶憑證：請依 README 設定")
        }
        FileInputStream(credentialFile).use { stream ->
            return GoogleCredentials.fromStream(stream).createScoped(scopes)
        }
    }
}
