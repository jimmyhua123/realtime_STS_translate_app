package com.example.realtimeststranslate.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialAec = intent.getBooleanExtra(EXTRA_AEC, true)
        val initialNs = intent.getBooleanExtra(EXTRA_NS, true)
        val initialAgc = intent.getBooleanExtra(EXTRA_AGC, true)
        val initialFrame = intent.getIntExtra(EXTRA_FRAME_DURATION, 100)
        val initialSegmentation = intent.getStringExtra(EXTRA_SEGMENTATION) ?: SegmentationStrategy.PUNCTUATION.name
        setContent {
            SettingsScreen(
                initialAec = initialAec,
                initialNs = initialNs,
                initialAgc = initialAgc,
                initialFrame = initialFrame,
                initialSegmentation = SegmentationStrategy.valueOf(initialSegmentation)
            ) { aec, ns, agc, frame, segmentation ->
                val data = Intent().apply {
                    putExtra(EXTRA_AEC, aec)
                    putExtra(EXTRA_NS, ns)
                    putExtra(EXTRA_AGC, agc)
                    putExtra(EXTRA_FRAME_DURATION, frame)
                    putExtra(EXTRA_SEGMENTATION, segmentation.name)
                }
                setResult(Activity.RESULT_OK, data)
                finish()
            }
        }
    }

    companion object {
        const val EXTRA_AEC = "extra_aec"
        const val EXTRA_NS = "extra_ns"
        const val EXTRA_AGC = "extra_agc"
        const val EXTRA_FRAME_DURATION = "extra_frame"
        const val EXTRA_SEGMENTATION = "extra_segmentation"
    }
}

@Composable
private fun SettingsScreen(
    initialAec: Boolean,
    initialNs: Boolean,
    initialAgc: Boolean,
    initialFrame: Int,
    initialSegmentation: SegmentationStrategy,
    onSave: (Boolean, Boolean, Boolean, Int, SegmentationStrategy) -> Unit
) {
    var aec by remember { mutableStateOf(initialAec) }
    var ns by remember { mutableStateOf(initialNs) }
    var agc by remember { mutableStateOf(initialAgc) }
    var frame by remember { mutableStateOf(initialFrame) }
    var segmentation by remember { mutableStateOf(initialSegmentation) }

    Scaffold(topBar = { TopAppBar(title = { Text("設定") }) }) { padding ->
        Surface(modifier = Modifier.padding(padding)) {
            Column(modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SwitchRow(title = "AEC", checked = aec, description = "聲學回音消除") { aec = it }
                SwitchRow(title = "NS", checked = ns, description = "雜訊抑制") { ns = it }
                SwitchRow(title = "AGC", checked = agc, description = "自動增益控制") { agc = it }
                FrameSelector(frame) { frame = it }
                SegmentationSelector(segmentation) { segmentation = it }
                Button(onClick = { onSave(aec, ns, agc, frame, segmentation) }) {
                    Text("儲存")
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, description: String, onCheckedChange: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun FrameSelector(frame: Int, onFrameChange: (Int) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("語音框長度：${frame} ms", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onFrameChange((frame - 20).coerceAtLeast(40)) }) {
                    Text("-20")
                }
                Button(onClick = { onFrameChange((frame + 20).coerceAtMost(200)) }) {
                    Text("+20")
                }
            }
            Text("調整後將影響 STT/TTS 串流切片大小。")
        }
    }
}

@Composable
private fun SegmentationSelector(segmentation: SegmentationStrategy, onChange: (SegmentationStrategy) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("翻譯分句策略", style = MaterialTheme.typography.titleMedium)
            SegmentationStrategy.values().forEach { option ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = segmentation == option, onClick = { onChange(option) })
                    Text(
                        text = when (option) {
                            SegmentationStrategy.PUNCTUATION -> "標點分句：依 STT 最終句"
                            SegmentationStrategy.SILENCE -> "靜音分句：使用 VAD 偵測靜音"
                        }
                    )
                }
            }
        }
    }
}
