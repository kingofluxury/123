package com.voicesim.core.vc

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * manifest.json 解析 + 音色仓库。
 *
 * 解析是纯函数（输入目录，输出对象），方便在 JVM 单测里覆盖。
 */
object ModelManifest {

    const val FILE_NAME = "manifest.json"

    fun parse(dir: File): VoiceProfile {
        val file = File(dir, FILE_NAME)
        require(file.isFile) { "manifest not found: ${file.absolutePath}" }

        val json = readManifest(file)
        val id = json.optString("id").ifBlank { dir.name }
        return VoiceProfile(
            id = id,
            displayName = json.optString("name").ifBlank { id },
            description = json.optString("description", ""),
            modelDir = dir,
            sampleRate = json.optInt("sample_rate", 40_000),
            speakerId = json.optInt("speaker_id", 0),
            pitchSemitones = json.optDouble("pitch_semitones", 0.0).toFloat(),
            license = json.optString("license", ""),
            version = json.optString("version", "1.0.0"),
        )
    }

    fun parseBundle(profile: VoiceProfile): RvcModelBundle {
        val dir = profile.modelDir
        val json = readManifest(File(dir, FILE_NAME))
        val files = json.optJSONObject("files") ?: JSONObject()

        fun path(key: String): String? {
            val name = files.optString(key)
            if (name.isBlank()) return null
            val file = File(dir, name)
            require(file.isFile) { "model file not found: ${file.absolutePath}" }
            return file.absolutePath
        }

        val hubert = requireNotNull(path("hubert")) {
            "manifest files.hubert missing: ${profile.id}"
        }

        val combinedName = json.optString("combined").ifBlank { files.optString("combined") }
        val combinedPath = if (combinedName.isBlank()) {
            null
        } else {
            val file = File(dir, combinedName)
            require(file.isFile) { "combined model not found: ${file.absolutePath}" }
            file.absolutePath
        }

        val mode = json.optString("mode", "")
        val useCombined = when (mode) {
            "combined" -> {
                requireNotNull(combinedPath) { "mode=combined but no combined file: ${profile.id}" }
                true
            }
            "split" -> false
            else -> combinedPath != null
        }

        val textEncoder: String?
        val flow: String?
        val generator: String?
        if (useCombined) {
            textEncoder = null
            flow = null
            generator = null
        } else {
            textEncoder = requireNotNull(path("text_encoder")) {
                "manifest files.text_encoder missing: ${profile.id}"
            }
            flow = requireNotNull(path("flow")) {
                "manifest files.flow missing: ${profile.id}"
            }
            generator = requireNotNull(path("generator")) {
                "manifest files.generator missing: ${profile.id}"
            }
        }

        val embeddingName = json.optString("embedding")
        val embedding = if (embeddingName.isBlank()) {
            null
        } else {
            File(dir, embeddingName).takeIf { it.isFile }?.let(::readFloatBin)
        }

        return RvcModelBundle(
            hubertPath = hubert,
            rmvpePath = path("rmvpe"),
            textEncoderPath = textEncoder,
            flowPath = flow,
            generatorPath = generator,
            combinedPath = if (useCombined) combinedPath else null,
            modelSampleRate = resolveSampleRate(dir, json, profile),
            segmentFrames = resolveSegmentFrames(json),
            f0 = json.optBoolean("f0", true),
            speakerId = json.optInt("speaker_id", profile.speakerId),
            speakerEmbedding = embedding,
        )
    }

    private fun resolveSampleRate(dir: File, json: JSONObject, profile: VoiceProfile): Int {
        val declared = json.optInt("sample_rate", 0)
        if (declared > 0) return declared

        val config = File(dir, "config.json")
        if (config.isFile) {
            val fromConfig = runCatching {
                JSONObject(config.readText()).optInt("target_sr", 0)
            }.getOrDefault(0)
            if (fromConfig > 0) return fromConfig
        }
        return profile.sampleRate.takeIf { it > 0 } ?: 40_000
    }

    private fun resolveSegmentFrames(json: JSONObject): Int {
        val frames = json.optInt("segment_frames", 64)
        require(frames in 16..256) { "segment_frames out of range: $frames" }
        require(frames % 16 == 0) { "segment_frames must be a multiple of 16: $frames" }
        return frames
    }

    private fun readManifest(file: File): JSONObject =
        JSONObject(file.readText().removePrefix("\uFEFF"))

    private fun readFloatBin(file: File): FloatArray {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }
}

/** 音色查询。实现可以是文件系统、数据库或网络，目前只有本地文件。 */
interface VoiceProfileStore {
    fun list(): List<VoiceProfile>
    fun find(id: String): VoiceProfile?
}

/** 以 `filesDir/models/<id>/` 为根的本地仓库。 */
class FileVoiceProfileStore(private val root: File) : VoiceProfileStore {

    override fun list(): List<VoiceProfile> {
        val dirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        return dirs.mapNotNull { dir ->
            runCatching {
                val profile = ModelManifest.parse(dir)
                val error = runCatching { ModelManifest.parseBundle(profile) }
                    .exceptionOrNull()
                    ?.message
                if (error == null) profile else profile.copy(validationError = error)
            }.getOrNull()
        }.sortedBy { it.displayName }
    }

    override fun find(id: String): VoiceProfile? = list().firstOrNull { it.id == id }
}

/**
 * 首次启动把 APK 内置音色复制到可写目录。
 *
 * 已存在且 manifest 完整的目录不覆盖，方便用户后续替换/升级。
 */
class AssetModelInstaller(
    private val context: Context,
    private val targetRoot: File,
) {
    fun ensureInstalled(assetRoot: String = "models"): Int {
        targetRoot.mkdirs()
        var installed = 0
        context.assets.list(assetRoot).orEmpty().forEach { name ->
            val assetProfile = "$assetRoot/$name"
            val children = context.assets.list(assetProfile).orEmpty()
            // 只处理真正包含 manifest.json 的目录，忽略 README 之类的散文件
            if (ModelManifest.FILE_NAME !in children) return@forEach

            val target = File(targetRoot, name)
            if (File(target, ModelManifest.FILE_NAME).isFile) return@forEach
            target.mkdirs()
            copyDir(assetProfile, target)
            installed++
        }
        return installed
    }

    private fun copyDir(assetPath: String, target: File) {
        context.assets.list(assetPath).orEmpty().forEach { name ->
            val childAsset = "$assetPath/$name"
            val childTarget = File(target, name)
            val grandChildren = context.assets.list(childAsset).orEmpty()
            if (grandChildren.isEmpty()) {
                context.assets.open(childAsset).use { input ->
                    childTarget.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                childTarget.mkdirs()
                copyDir(childAsset, childTarget)
            }
        }
    }
}
