package com.voicesim

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.voicesim.core.audio.AndroidAudioRecordSource
import com.voicesim.core.audio.AndroidAudioTrackSink
import com.voicesim.core.audio.AudioSpec
import com.voicesim.core.vc.AssetModelInstaller
import com.voicesim.core.vc.ConversionConfig
import com.voicesim.core.vc.ConversionMode
import com.voicesim.core.vc.ConverterFactory
import com.voicesim.core.vc.ModelManifest
import com.voicesim.core.vc.PipelineState
import com.voicesim.core.vc.PipelineStats
import com.voicesim.core.vc.VoiceChangerPipeline
import com.voicesim.core.vc.VoiceProfile
import com.voicesim.core.vc.VoiceProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

data class VoiceChangerUiState(
    val profiles: List<VoiceProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val mode: ConversionMode = ConversionMode.PASSTHROUGH,
    val pitchSemitones: Float = 0f,
    val running: Boolean = false,
    val busy: Boolean = false,
    val stats: PipelineStats = PipelineStats(),
    val message: String? = null,
)

/**
 * 应用层控制器：拥有管线生命周期，向 UI 暴露唯一的状态源。
 *
 * - 所有重活（模型拷贝、模型加载、音频启动）都在 IO 线程；
 * - [start] / [stop] 用 busy 标志串行化，避免 UI 连点导致竞争；
 * - 不直接持有 Activity，可被前台服务复用。
 */
class VoiceChangerController(
    context: Context,
    private val store: VoiceProfileStore,
    private val factory: ConverterFactory,
    private val installer: AssetModelInstaller,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(VoiceChangerUiState())
    val state: StateFlow<VoiceChangerUiState> = _state.asStateFlow()

    private var pipeline: VoiceChangerPipeline? = null
    private var statsJob: Job? = null

    init {
        val builtin = builtinProfile()
        _state.value = VoiceChangerUiState(
            profiles = listOf(builtin),
            selectedProfileId = builtin.id,
        )
        scope.launch {
            runCatching { installer.ensureInstalled() }
            refreshProfiles()
        }
    }

    fun refreshProfiles() {
        val builtin = builtinProfile()
        val all = listOf(builtin) + store.list()
        _state.update { current ->
            val selected = current.selectedProfileId
                ?.takeIf { id -> all.any { it.id == id } }
                ?: builtin.id
            current.copy(profiles = all, selectedProfileId = selected)
        }
    }

    fun selectProfile(id: String) {
        if (_state.value.running || _state.value.busy) return
        _state.update { it.copy(selectedProfileId = id) }
    }

    fun setMode(mode: ConversionMode) {
        if (_state.value.running || _state.value.busy) return
        _state.update { current ->
            val selected = if (mode == ConversionMode.RVC && current.selectedProfileId == BUILTIN_ID) {
                current.profiles.firstOrNull {
                    it.id != BUILTIN_ID && it.validationError == null
                }?.id ?: current.selectedProfileId
            } else {
                current.selectedProfileId
            }
            current.copy(mode = mode, selectedProfileId = selected)
        }
    }

    fun setPitchSemitones(value: Float) {
        _state.update { it.copy(pitchSemitones = value) }
    }

    /**
     * 从 SAF 目录导入音色：复制整个目录到 filesDir/models/<id>。
     * 目录根部必须有 manifest.json。
     */
    fun importModel(treeUri: Uri) {
        if (_state.value.running || _state.value.busy) return

        _state.update { it.copy(busy = true, message = "正在导入模型…") }
        scope.launch {
            runCatching { importTree(treeUri) }
                .onSuccess { name ->
                    refreshProfiles()
                    _state.update { it.copy(busy = false, message = "已导入：$name") }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            busy = false,
                            message = "导入失败：${error.message ?: error.javaClass.simpleName}",
                        )
                    }
                }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    fun start() {
        val snapshot = _state.value
        if (snapshot.running || snapshot.busy) return

        val profile = snapshot.profiles.firstOrNull { it.id == snapshot.selectedProfileId }
        if (profile == null) {
            message("没有可用音色")
            return
        }
        if (snapshot.mode == ConversionMode.RVC) {
            if (profile.id == BUILTIN_ID) {
                message("RVC 模式需要先导入音色模型（见 README）")
                return
            }
            profile.validationError?.let {
                message("模型不完整：$it")
                return
            }
        }

        _state.update { it.copy(busy = true, message = null) }
        val config = configFor(snapshot.mode, snapshot.pitchSemitones)
        val converter = factory.create(snapshot.mode, config)
        val newPipeline = VoiceChangerPipeline(
            source = AndroidAudioRecordSource(),
            sink = AndroidAudioTrackSink(),
            converter = converter,
        )
        pipeline = newPipeline

        scope.launch {
            try {
                newPipeline.start(profile, config)
                statsJob?.cancel()
                statsJob = launch {
                    newPipeline.stats.collect { stats ->
                        _state.update {
                            it.copy(
                                stats = stats,
                                running = stats.state == PipelineState.RUNNING,
                                busy = false,
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                pipeline = null
                _state.update {
                    it.copy(busy = false, running = false, message = describe(t))
                }
            }
        }
    }

    fun stop() {
        val current = pipeline ?: return
        if (_state.value.busy) return

        pipeline = null
        statsJob?.cancel()
        statsJob = null
        _state.update { it.copy(busy = true) }

        scope.launch {
            runCatching { current.stop() }
            _state.update { it.copy(busy = false, running = false) }
        }
    }

    fun release() {
        statsJob?.cancel()
        runCatching { pipeline?.stop() }
        pipeline = null
        scope.cancel()
    }

    private fun configFor(mode: ConversionMode, pitchSemitones: Float): ConversionConfig =
        when (mode) {
            ConversionMode.PASSTHROUGH ->
                ConversionConfig.passthrough(AudioSpec.DEFAULT_SAMPLE_RATE)

            ConversionMode.RVC ->
                ConversionConfig.standard(AudioSpec.DEFAULT_SAMPLE_RATE)
                    .copy(pitchSemitones = pitchSemitones)
        }

    private fun builtinProfile(): VoiceProfile = VoiceProfile(
        id = BUILTIN_ID,
        displayName = "直通（验证链路）",
        description = "不做变声，用于检查采集、延迟和播放",
        modelDir = File(appContext.filesDir, "models/__builtin__"),
        license = "built-in",
    )

    private fun importTree(treeUri: Uri): String {
        val root = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: error("无法读取所选目录")

        val manifest = root.findFile(ModelManifest.FILE_NAME)
        val manifestId = manifest?.let { doc ->
            runCatching {
                appContext.contentResolver.openInputStream(doc.uri)
                    ?.bufferedReader()
                    ?.use { JSONObject(it.readText()).optString("id") }
            }.getOrNull()
        }?.takeIf { it.isNotBlank() }

        val rawName = manifestId ?: root.name ?: "imported_${System.currentTimeMillis()}"
        val sanitized = rawName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "imported" }
        val name = if (sanitized == "." || sanitized == "..") "imported" else sanitized

        val target = File(File(appContext.filesDir, "models"), name)
        target.deleteRecursively()
        target.mkdirs()
        copyDocument(root, target)

        require(File(target, ModelManifest.FILE_NAME).isFile) {
            "目录根节点缺少 ${ModelManifest.FILE_NAME}"
        }
        return name
    }

    private fun copyDocument(source: DocumentFile, target: File) {
        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles().forEach { child ->
                val childName = child.name ?: return@forEach
                copyDocument(child, File(target, childName))
            }
            return
        }

        val input = appContext.contentResolver.openInputStream(source.uri)
            ?: error("无法读取文件：${source.name}")
        input.use { stream ->
            target.outputStream().use { output -> stream.copyTo(output) }
        }
    }

    private fun message(text: String) {
        _state.update { it.copy(message = text) }
    }

    private fun describe(t: Throwable): String {
        val reason = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        return "启动失败：$reason"
    }

    companion object {
        const val BUILTIN_ID = "__builtin__"
    }
}
