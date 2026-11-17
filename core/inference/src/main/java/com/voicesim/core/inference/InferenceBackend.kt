package com.voicesim.core.inference

/** 一次推理会话。同一 session 不保证线程安全，调用方需串行使用。 */
interface ModelSession : AutoCloseable {
    val inputs: List<TensorInfo>
    val outputs: List<TensorInfo>

    /**
     * 同步推理。key 必须是模型输入名。
     */
    fun run(inputs: Map<String, Tensor>): Map<String, Tensor>
}

/** 后端会话参数。 */
data class SessionConfig(
    val threads: Int = 2,
    val useNnapi: Boolean = true,
    val useXnnpack: Boolean = false,
)

/**
 * 推理后端抽象。
 *
 * 对上层只暴露"打开模型 → 跑张量"，不暴露 ORT 类型。
 * 换 NCNN / QNN / TFLite 时新增实现即可，[com.voicesim.core.vc.RvcConverter] 不用改。
 */
interface InferenceBackend : AutoCloseable {
    val name: String

    fun open(modelPath: String, config: SessionConfig = SessionConfig()): ModelSession
}

/** 默认后端工厂。 */
object InferenceBackends {
    fun create(): InferenceBackend = OnnxRuntimeBackend()
}
