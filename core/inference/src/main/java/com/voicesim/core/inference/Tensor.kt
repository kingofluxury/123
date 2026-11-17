package com.voicesim.core.inference

/**
 * 模型输入/输出的纯数据张量。
 *
 * 变声链路需要的类型不止 float：text_encoder 的 lengths 是 int64，
 * 合并模型还需要 sid / phone_lengths；HuBERT 的 padding_mask 是 bool。
 * 用 sealed class 表达，避免在调用点做字符串/类型猜测。
 */
sealed class Tensor {

    abstract val shape: LongArray

    val elementCount: Long
        get() = shape.fold(1L) { acc, dim -> acc * dim }

    class FloatTensor(
        override val shape: LongArray,
        val data: FloatArray,
    ) : Tensor() {
        init {
            require(elementCount == data.size.toLong()) {
                "shape ${shape.contentToString()} expects $elementCount floats, got ${data.size}"
            }
        }
    }

    class LongTensor(
        override val shape: LongArray,
        val data: LongArray,
    ) : Tensor() {
        init {
            require(elementCount == data.size.toLong()) {
                "shape ${shape.contentToString()} expects $elementCount longs, got ${data.size}"
            }
        }
    }

    class IntTensor(
        override val shape: LongArray,
        val data: IntArray,
    ) : Tensor() {
        init {
            require(elementCount == data.size.toLong()) {
                "shape ${shape.contentToString()} expects $elementCount ints, got ${data.size}"
            }
        }
    }

    class BoolTensor(
        override val shape: LongArray,
        val data: BooleanArray,
    ) : Tensor() {
        init {
            require(elementCount == data.size.toLong()) {
                "shape ${shape.contentToString()} expects $elementCount bools, got ${data.size}"
            }
        }
    }
}

/** 模型端口描述，用于加载时校验输入形状。 */
data class TensorInfo(
    val name: String,
    val shape: LongArray,
    val type: String,
)
