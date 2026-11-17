package com.voicesim.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicesim.VoiceChangerController
import com.voicesim.core.vc.ConversionMode
import com.voicesim.core.vc.PipelineState
import com.voicesim.core.vc.PipelineStats
import com.voicesim.core.vc.VoiceProfile
import com.voicesim.service.VoiceChangerService

@Composable
fun VoiceChangerScreen(controller: VoiceChangerController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val recordLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) VoiceChangerService.start(context)
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) controller.importModel(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("VoiceSim", style = MaterialTheme.typography.headlineMedium)
        Text(
            "端侧实时音色转换 · 640 ms 分块",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionCard(title = "音色") {
            ProfileSelector(
                profiles = state.profiles,
                selectedId = state.selectedProfileId,
                enabled = !state.running && !state.busy,
                onSelect = controller::selectProfile,
            )

            OutlinedButton(
                onClick = { importLauncher.launch(null) },
                enabled = !state.running && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("导入音色目录（含 manifest.json）")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.mode == ConversionMode.PASSTHROUGH,
                    onClick = { controller.setMode(ConversionMode.PASSTHROUGH) },
                    enabled = !state.running && !state.busy,
                    label = { Text("直通") },
                )
                FilterChip(
                    selected = state.mode == ConversionMode.RVC,
                    onClick = { controller.setMode(ConversionMode.RVC) },
                    enabled = !state.running && !state.busy,
                    label = { Text("RVC 音色") },
                )
            }

            Text(
                "音高 " + formatSemitones(state.pitchSemitones),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = state.pitchSemitones,
                onValueChange = controller::setPitchSemitones,
                valueRange = -12f..12f,
                steps = 23,
                enabled = state.mode == ConversionMode.RVC && !state.running,
            )
            Text(
                "RVC 模式按模型分块（默认 640 ms），音高参数实时生效；直通模式只验证链路。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        StatsCard(state.stats)

        Button(
            onClick = {
                if (state.running) {
                    controller.stop()
                    VoiceChangerService.stop(context)
                } else if (!hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
                    recordLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    ) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    VoiceChangerService.start(context)
                }
            },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.running) "停止变声" else "开始变声")
        }

        state.message?.let { text ->
            Text(
                text = text,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ProfileSelector(
    profiles: List<VoiceProfile>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = profiles.firstOrNull { it.id == selectedId }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled && profiles.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(selected?.displayName ?: "没有可用音色")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (profile.validationError == null) {
                                profile.displayName
                            } else {
                                "${profile.displayName}（文件缺失）"
                            },
                        )
                    },
                    onClick = {
                        onSelect(profile.id)
                        expanded = false
                    },
                    enabled = profile.validationError == null,
                )
            }
        }
        Text(
            selected?.validationError?.let { "⚠ $it" } ?: selected?.description.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected?.validationError != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun StatsCard(stats: PipelineStats) {
    SectionCard(title = "运行状态") {
        Text(statusLabel(stats.state), fontWeight = FontWeight.SemiBold)
        if (stats.hopMs > 0) {
            Text("分块 ${stats.hopMs} ms")
        }
        Text("推理耗时 %.1f ms（平均 %.1f ms）".format(stats.lastInferenceMs, stats.averageInferenceMs))
        Text(
            "已处理 ${stats.processedChunks} 块 · 丢帧 ${stats.captureDrops} · " +
                "欠载 ${stats.playbackUnderruns} · 回退 ${stats.fallbackChunks}",
        )
        Text("输入 %.1f dBFS · 输出 %.1f dBFS".format(stats.inputLevelDb, stats.outputLevelDb))
        stats.lastError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun statusLabel(state: PipelineState): String = when (state) {
    PipelineState.IDLE -> "空闲"
    PipelineState.RUNNING -> "运行中"
    PipelineState.ERROR -> "错误"
}

private fun formatSemitones(value: Float): String =
    if (value >= 0f) "+%.0f 半音".format(value) else "%.0f 半音".format(value)

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
