package com.voicesim.core.inference

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo as OrtTensorInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

/**
 * ONNX Runtime 后端。
 *
 * 默认优先尝试 NNAPI（Android GPU/NPU），失败自动留在 CPU（XNNPACK）。
 * NNAPI 在部分机型上对动态 shape 支持不好，RVC 的 generator 建议用固定窗口。
 */
class OnnxRuntimeBackend : InferenceBackend {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    override val name: String = "onnxruntime"

    override fun open(modelPath: String, config: SessionConfig): ModelSession {
        require(File(modelPath).isFile) { "model not found: $modelPath" }

        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(config.threads.coerceAtLeast(1))
        options.setInterOpNumThreads(1)
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

        if (config.useXnnpack) {
            runCatching { options.addXnnpack(emptyMap<String, String>()) }
        } else if (config.useNnapi) {
            runCatching { options.addNnapi() }
        }

        val session = env.createSession(modelPath, options)
        return OrtModelSession(env, session)
    }

    override fun close() {
        // OrtEnvironment 是进程级单例，不在这里关闭。
    }
}

private class OrtModelSession(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : ModelSession {

    override val inputs: List<TensorInfo> = session.inputInfo.map { (name, node) ->
        val info = node.info as OrtTensorInfo
        TensorInfo(name, info.shape, info.type.name)
    }

    override val outputs: List<TensorInfo> = session.outputInfo.map { (name, node) ->
        val info = node.info as OrtTensorInfo
        TensorInfo(name, info.shape, info.type.name)
    }

    override fun run(inputs: Map<String, Tensor>): Map<String, Tensor> {
        val ortInputs = HashMap<String, OnnxTensor>(inputs.size)
        try {
            inputs.forEach { (name, tensor) ->
                ortInputs[name] = createTensor(tensor)
            }

            val result = session.run(ortInputs)
            try {
                val output = HashMap<String, Tensor>()
                for ((name, value) in result) {
                    output[name] = readTensor(value as OnnxTensor)
                }
                return output
            } finally {
                runCatching { result.close() }
            }
        } finally {
            ortInputs.values.forEach { runCatching { it.close() } }
        }
    }

    private fun createTensor(tensor: Tensor): OnnxTensor = when (tensor) {
        is Tensor.FloatTensor ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(tensor.data), tensor.shape)

        is Tensor.LongTensor ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(tensor.data), tensor.shape)

        is Tensor.IntTensor ->
            OnnxTensor.createTensor(env, IntBuffer.wrap(tensor.data), tensor.shape)

        is Tensor.BoolTensor -> {
            val bytes = ByteArray(tensor.data.size) { if (tensor.data[it]) 1 else 0 }
            OnnxTensor.createTensor(
                env,
                ByteBuffer.wrap(bytes),
                tensor.shape,
                OnnxJavaType.BOOL,
            )
        }
    }

    private fun readTensor(tensor: OnnxTensor): Tensor {
        val shape = tensor.info.shape
        return when (tensor.info.type) {
            OnnxJavaType.FLOAT -> {
                val buffer = tensor.floatBuffer
                Tensor.FloatTensor(shape, FloatArray(buffer.remaining()).also { buffer.get(it) })
            }

            OnnxJavaType.INT64 -> {
                val buffer = tensor.longBuffer
                Tensor.LongTensor(shape, LongArray(buffer.remaining()).also { buffer.get(it) })
            }

            OnnxJavaType.INT32 -> {
                val buffer = tensor.intBuffer
                Tensor.IntTensor(shape, IntArray(buffer.remaining()).also { buffer.get(it) })
            }

            else -> throw IllegalStateException(
                "unsupported output tensor type ${tensor.info.type}",
            )
        }
    }

    override fun close() {
        session.close()
    }
}
