package com.example.realtimeststranslate.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.realtimeststranslate.R
import com.example.realtimeststranslate.service.DeviceUi
import com.example.realtimeststranslate.service.RealtimeForegroundService
import com.example.realtimeststranslate.ui.SegmentationStrategy
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var service: RealtimeForegroundService? = null
    private var serviceJob: Job? = null
    private var isBound = false
    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        if (data.hasExtra(SettingsActivity.EXTRA_AEC)) {
            viewModel.toggleAec(data.getBooleanExtra(SettingsActivity.EXTRA_AEC, true))
        }
        if (data.hasExtra(SettingsActivity.EXTRA_NS)) {
            viewModel.toggleNs(data.getBooleanExtra(SettingsActivity.EXTRA_NS, true))
        }
        if (data.hasExtra(SettingsActivity.EXTRA_AGC)) {
            viewModel.toggleAgc(data.getBooleanExtra(SettingsActivity.EXTRA_AGC, true))
        }
        if (data.hasExtra(SettingsActivity.EXTRA_FRAME_DURATION)) {
            viewModel.updateFrameDuration(data.getIntExtra(SettingsActivity.EXTRA_FRAME_DURATION, 100))
        }
        if (data.hasExtra(SettingsActivity.EXTRA_SEGMENTATION)) {
            val name = data.getStringExtra(SettingsActivity.EXTRA_SEGMENTATION)
            val strategy = SegmentationStrategy.valueOf(name ?: SegmentationStrategy.PUNCTUATION.name)
            viewModel.updateSegmentation(strategy)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? RealtimeForegroundService.LocalBinder ?: return
            service = localBinder.service()
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceJob?.cancel()
            serviceJob = null
            service = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val record = result[Manifest.permission.RECORD_AUDIO] == true
        val bluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            result[Manifest.permission.BLUETOOTH_CONNECT] == true
        } else {
            true
        }
        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result[Manifest.permission.POST_NOTIFICATIONS] == true
        } else {
            true
        }
        viewModel.updatePermissions(record, bluetooth, notifications)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val devices by viewModel.devices.collectAsStateWithLifecycle()
            MainScreen(
                state = uiState,
                devices = devices,
                onSelectInput = { viewModel.selectInput(it) },
                onSelectSource = { viewModel.selectSource(it) },
                onSelectTarget = { viewModel.selectTarget(it) },
                onStart = { startPipeline() },
                onStop = { stopPipeline() },
                onOpenSettings = {
                    val ui = viewModel.uiState.value
                    val intent = Intent(this, SettingsActivity::class.java).apply {
                        putExtra(SettingsActivity.EXTRA_AEC, ui.enableAec)
                        putExtra(SettingsActivity.EXTRA_NS, ui.enableNs)
                        putExtra(SettingsActivity.EXTRA_AGC, ui.enableAgc)
                        putExtra(SettingsActivity.EXTRA_FRAME_DURATION, ui.frameDurationMillis)
                        putExtra(SettingsActivity.EXTRA_SEGMENTATION, ui.segmentationStrategy.name)
                    }
                    settingsLauncher.launch(intent)
                }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, RealtimeForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
        isBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        serviceJob?.cancel()
        serviceJob = null
        service = null
    }

    private fun observeServiceState() {
        val serviceInstance = service ?: return
        serviceJob?.cancel()
        serviceJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                serviceInstance.state.collect { state ->
                    viewModel.onServiceStateChanged(state)
                }
            }
        }
    }

    private fun requestRuntimePermissions() {
        val needsBluetooth = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val needsNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (needsBluetooth) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (needsNotifications) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val recordGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val bluetoothGranted = !needsBluetooth || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val notificationsGranted = !needsNotifications || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!recordGranted || !bluetoothGranted || !notificationsGranted) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            viewModel.updatePermissions(recordGranted, bluetoothGranted, notificationsGranted)
        }
    }

    private fun startPipeline() {
        val serviceInstance = service ?: return
        val config = viewModel.buildSessionConfig()
        serviceInstance.startSession(config)
    }

    private fun stopPipeline() {
        service?.stopSession()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainUiState,
    devices: List<DeviceUi>,
    onSelectInput: (InputSelection) -> Unit,
    onSelectSource: (LanguageOption) -> Unit,
    onSelectTarget: (LanguageOption) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.app_name)) },
                actions = {
                    OutlinedButton(onClick = onOpenSettings) {
                        Text("設定")
                    }
                }
            )
        }
    ) { padding ->
        Surface(modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
        ) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                InputSelector(state, onSelectInput)
                LanguageSelector(state, onSelectSource, onSelectTarget)
                DeviceStatusCard(state, devices)
                CloudStatusCard(state)
                TranscriptCard(state)
                ControlButtons(onStart, onStop)
                if (state.statusMessage.isNotBlank()) {
                    Text(text = state.statusMessage, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun InputSelector(state: MainUiState, onSelectInput: (InputSelection) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("輸入來源", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(
                    selected = state.inputSelection == InputSelection.BLUETOOTH_MIC,
                    onClick = { onSelectInput(InputSelection.BLUETOOTH_MIC) }
                )
                Text("藍牙耳機麥克風")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(
                    selected = state.inputSelection == InputSelection.BUILTIN_MIC,
                    onClick = { onSelectInput(InputSelection.BUILTIN_MIC) }
                )
                Text("手機內建麥克風 (輸出仍走耳機)")
            }
            if (state.statusMessage.contains("回退")) {
                Text(text = "裝置限制：已強制使用藍牙麥克風", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun LanguageSelector(
    state: MainUiState,
    onSelectSource: (LanguageOption) -> Unit,
    onSelectTarget: (LanguageOption) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("來源語言", style = MaterialTheme.typography.titleMedium)
            state.sourceLanguages.forEach { option ->
                Row(
                    modifier = Modifier
                        .selectable(selected = state.selectedSource == option, onClick = { onSelectSource(option) }),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = state.selectedSource == option,
                        onClick = { onSelectSource(option) }
                    )
                    Text(option.displayName)
                }
            }
            Divider()
            Text("目標語言", style = MaterialTheme.typography.titleMedium)
            state.targetLanguages.forEach { option ->
                Row(
                    modifier = Modifier
                        .selectable(selected = state.selectedTarget == option, onClick = { onSelectTarget(option) }),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = state.selectedTarget == option,
                        onClick = { onSelectTarget(option) }
                    )
                    Text(option.displayName)
                }
            }
        }
    }
}

@Composable
private fun DeviceStatusCard(state: MainUiState, devices: List<DeviceUi>) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("裝置狀態", style = MaterialTheme.typography.titleMedium)
            Text("目前路由：${state.routeDescription}")
            Text("取樣率：${state.sampleRate} Hz")
            Text("雲端狀態：${state.cloudStatus}")
            state.latencyMillis?.let {
                Text("延遲量測：${it} ms")
            }
            Divider()
            devices.forEach { device ->
                Text("• ${device.name}")
            }
        }
    }
}

@Composable
private fun CloudStatusCard(state: MainUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Google Cloud 連線", style = MaterialTheme.typography.titleMedium)
            Text("狀態：${state.cloudStatus}")
            if (state.autoDetect) {
                Text("來源語言：自動偵測")
            } else {
                Text("來源語言：${state.selectedSource.displayName}")
            }
            Text("目標語言：${state.selectedTarget.displayName}")
            Text("分句策略：${when (state.segmentationStrategy) {
                SegmentationStrategy.PUNCTUATION -> "標點"
                SegmentationStrategy.SILENCE -> "靜音門檻"
            }}")
        }
    }
}

@Composable
private fun TranscriptCard(state: MainUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("即時字幕", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Interim：${state.sttInterim}")
            Text("Final：${state.sttFinal}")
            Divider()
            Text("翻譯：${state.translation}")
        }
    }
}

@Composable
private fun ControlButtons(onStart: () -> Unit, onStop: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(onClick = onStart, modifier = Modifier.weight(1f)) {
            Text("啟動前景服務")
        }
        OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) {
            Text("停止")
        }
    }
}
