package com.voicesim.core.vc

import java.io.File

/**
 * 一个可选音色。
 *
 * [modelDir] 是已经落到设备文件系统上的目录，内含 manifest.json 和三/五个模型文件。
 * 不把模型二进制放进内存对象，避免切换音色时重复持有。
 */
data class VoiceProfile(
    val id: String,
    val displayName: String,
    val description: String = "",
    val modelDir: File,
    val sampleRate: Int = 40_000,
    val speakerId: Int = 0,
    val pitchSemitones: Float = 0f,
    val license: String = "",
    val version: String = "1.0.0",
    /** 非空表示模型文件不完整，UI 置灰并展示原因。 */
    val validationError: String? = null,
)

/**
 * RVC 推理需要的文件集合。
 *
 * 两种布局：
 * - 拆分：hubert + rmvpe + text_encoder + flow + generator
 * - 合并：hubert + rmvpe + combined
 *
 * 路径为 null 表示该文件不存在；加载阶段会做完整校验。
 */
data class RvcModelBundle(
    val hubertPath: String,
    val rmvpePath: String?,
    val textEncoderPath: String?,
    val flowPath: String?,
    val generatorPath: String?,
    val combinedPath: String?,
    val modelSampleRate: Int,
    /** 单块帧数（100 Hz），64 帧 = 640 ms。 */
    val segmentFrames: Int,
    val f0: Boolean = true,
    val speakerId: Int = 0,
    val speakerEmbedding: FloatArray? = null,
) {
    val isCombined: Boolean get() = combinedPath != null
    val segmentSamples16k: Int get() = segmentFrames * 160
}
