# Realtime SCO/HFP STS Translator

此專案提供 Samsung Galaxy A54（Android 14、targetSdk 34）結合 Google Pixel Buds Pro 的 SCO/HFP 低延遲即時語音翻譯骨架。所有音訊皆透過 `AudioManager.setCommunicationDevice()` 走藍牙通話路徑（SCO/HFP），完全不使用舊式 `startBluetoothSco()`、`stopBluetoothSco()` 或 `setSpeakerphoneOn()` API。

## 專案結構

```
.
├── app
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src
│       ├── main
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/example/realtimeststranslate
│       │   │   ├── audio
│       │   │   │   ├── AudioRouter.kt
│       │   │   │   ├── ScoPlayer.kt
│       │   │   │   └── ScoRecorder.kt
│       │   │   ├── cloud
│       │   │   │   ├── CredentialProvider.kt
│       │   │   │   ├── SttStreamClient.kt
│       │   │   │   ├── Translator.kt
│       │   │   │   └── TtsStreamClient.kt
│       │   │   ├── service
│       │   │   │   ├── RealtimeForegroundService.kt
│       │   │   │   ├── ServiceState.kt
│       │   │   │   └── SessionConfig.kt
│       │   │   ├── ui
│       │   │   │   ├── MainActivity.kt
│       │   │   │   ├── MainUiState.kt
│       │   │   │   ├── MainViewModel.kt
│       │   │   │   └── SettingsActivity.kt
│       │   │   └── util
│       │   │       └── RtcVad.kt
│       │   └── res
│       │       ├── drawable/ic_notification_foreground.xml
│       │       ├── values/strings.xml
│       │       └── values/themes.xml
├── build.gradle.kts
├── gradle.properties
└── settings.gradle.kts
```

## 關鍵設計

- **音訊路由**：`AudioRouter` 使用 `getAvailableCommunicationDevices()` 列舉裝置，並以 `setCommunicationDevice()` 切換到 `TYPE_BLUETOOTH_SCO`。UI 會顯示目前的 `currentCommunicationDevice` 與路由描述。
- **音訊 I/O**：
  - `ScoRecorder` 以 `AudioRecord.Builder()` 建立 PCM 16-bit、Mono 錄音，優先使用 16 kHz（mSBC），若失敗回落至 8 kHz（CVSD），UI 會同步顯示取樣率。可選擇啟用 AEC/NS/AGC。
  - `ScoPlayer` 以 `AudioTrack`（`USAGE_VOICE_COMMUNICATION` / `CONTENT_TYPE_SPEECH`）播放 Google TTS 串流音框，維持 100 ms frame 與環形佇列避免延遲累積。
  - 若裝置不支援「手機麥克風輸入＋藍牙耳機輸出」，會自動回退到藍牙麥克風並顯示提示。
- **雲端管線**：
  - `SttStreamClient` 使用 Speech-to-Text v2 gRPC 雙向串流 (`speech.googleapis.com`)，送出 100 ms PCM frame，並處理 interim/final 結果。
  - `Translator` 透過 Cloud Translation v3 `detectLanguage`（必要時）與 `translateText` 翻譯成目標語言（預設 `zh-TW`）。
  - `TtsStreamClient` 呼叫 Text-to-Speech 串流合成，優先以 Chirp 3 HD 聲線，音框即到即播。
- **前景服務**：`RealtimeForegroundService` 宣告 `foregroundServiceType="microphone|connectedDevice"`，持續監控藍牙連線、處理錄音／雲端串流／播放，並量測「開口到第一個 TTS 音框」延遲。
- **UI**：
  - 主畫面提供輸入來源（藍牙耳機／手機麥克風）、來源語言（含自動偵測）、目標語言選擇、Google Cloud 連線狀態、即時字幕與翻譯。
  - 設定頁允許調整 AEC/NS/AGC、語音框長度、翻譯分句策略（標點/靜音門檻）。

## 權限與前景服務（Android 14）

- Manifest 已宣告 `RECORD_AUDIO`、`BLUETOOTH_CONNECT`、`INTERNET`、`ACCESS_NETWORK_STATE`、`POST_NOTIFICATIONS` 與前景服務型別。
- `MainActivity` 於啟動時請求「錄音」「附近裝置」「通知」權限，並在未授權時提示使用者。

## Google Cloud 憑證設定

1. 建立 Google Cloud 專案並啟用 Speech-to-Text v2、Translation v3、Text-to-Speech API。
2. 建立具備上述 API 權限的服務帳戶，下載 JSON 憑證。
3. 將憑證安全地寫入應用程式私有目錄，例如：
   ```bash
   adb push your-service-account.json /sdcard/Download/
   adb shell run-as com.example.realtimeststranslate mkdir -p files
   adb shell run-as com.example.realtimeststranslate cp /sdcard/Download/your-service-account.json files/gcp_service_account.json
   ```
4. 在 `local.properties` 中設定 `gcpProjectId=YOUR_PROJECT_ID`，並於建置腳本或 CI 中以 `-P` 參數覆寫 `BuildConfig.GCP_PROJECT_ID`（預設為 `YOUR_GCP_PROJECT_ID`）。
5. 務必使用安全機制（例如硬體金鑰或加密儲存）保護憑證，避免直接打包在 APK 內。

## 建置與執行

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

啟動前請確保：
- Pixel Buds Pro 已與手機配對並處於通話模式。
- 裝置已授權所有必要權限。

## 測試流程

1. **藍牙耳機測試**：
   - 連線 Pixel Buds Pro，於主畫面選擇「藍牙耳機麥克風」，開始前景服務。
   - 以英文／日文／韓文說話，確認即時字幕顯示 interim/final，並聽到繁中語音回放。
2. **手機麥克風 fallback**：
   - 切換至「手機內建麥克風」。若平台支援將維持藍牙輸出；若不支援，UI 會提示並回退至藍牙麥克風。
3. **參數調整**：
   - 在設定頁切換 AEC/NS/AGC、修改語音框長度（100 ms 為預設）、切換分句策略，觀察 STT/TTS 穩定度。
4. **延遲量測**：
   - 使用主畫面顯示的延遲指標，量測「開始說話→第一個 TTS 音框播放」的毫秒數。

## 已知限制

- 部分裝置僅支援 8 kHz CVSD，會自動回落並在 UI 標註取樣率。
- 若硬體不允許「手機麥克風輸入＋藍牙耳機輸出」，會回退至藍牙麥克風並顯示提示。
- Google Cloud 官方 SDK 體積較大，建議於實際產品中採用精簡客製 gRPC stub。

## 參考資料

- Android Developers：`AudioManager.setCommunicationDevice()` 取代舊式 SCO API。
- HFP 音訊編碼：CVSD 8 kHz 與 mSBC 16 kHz。
- Google Cloud Speech-to-Text v2（gRPC）、Translation v3、Text-to-Speech 串流合成官方文件。
