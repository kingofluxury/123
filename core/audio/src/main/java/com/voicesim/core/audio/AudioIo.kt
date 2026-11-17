package com.voicesim.core.audio

/**
 * 音频输入设备。
 *
 * 实现必须是**阻塞式**读取：一次 [read] 要么填满请求的样本数，要么返回 0/负数。
 * 调用方是专用采集线程。
 */
interface AudioSource : AutoCloseable {
    val spec: AudioSpec

    fun open()

    /**
     * @return 实际读到的样本数；< 0 表示设备错误，0 表示暂无数据。
     */
    fun read(dst: FloatArray, offset: Int, size: Int): Int
}

/**
 * 音频输出设备。
 *
 * 实现必须是**阻塞式**写入，保证播放线程按实时速率推进。
 */
interface AudioSink : AutoCloseable {
    val spec: AudioSpec

    fun open()

    /**
     * @return 实际写出的样本数；< 0 表示设备错误。
     */
    fun write(src: FloatArray, offset: Int, size: Int): Int
}
