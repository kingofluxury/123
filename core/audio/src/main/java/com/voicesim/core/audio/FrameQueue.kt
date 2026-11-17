package com.voicesim.core.audio

import kotlin.math.min

/**
 * 固定帧长的有界队列，单生产者 / 单消费者。
 *
 * 满了丢最旧的一帧（而不是阻塞），这是实时音频的基本策略：
 * 积压等于延迟无限增长，宁可贵一点音质也不能让延迟失控。
 */
class FrameQueue(
    private val capacity: Int,
    val frameSize: Int,
) {
    init {
        require(capacity > 0)
        require(frameSize > 0)
    }

    private val frames = arrayOfNulls<FloatArray>(capacity)
    private var head = 0
    private var count = 0
    private val lock = Any()

    /** @return true 表示正常入队，false 表示挤掉了一帧旧的。 */
    fun push(frame: FloatArray): Boolean {
        require(frame.size == frameSize) { "frame size ${frame.size} != $frameSize" }
        synchronized(lock) {
            var dropped = false
            if (count == capacity) {
                head = (head + 1) % capacity
                count--
                dropped = true
            }
            frames[(head + count) % capacity] = frame
            count++
            return !dropped
        }
    }

    /** 取一帧拷贝到 [dst]，队列空返回 false。 */
    fun pop(dst: FloatArray): Boolean {
        require(dst.size >= frameSize)
        synchronized(lock) {
            if (count == 0) return false
            val frame = frames[head] ?: return false
            System.arraycopy(frame, 0, dst, 0, frameSize)
            frames[head] = null
            head = (head + 1) % capacity
            count--
            return true
        }
    }

    fun clear() {
        synchronized(lock) {
            frames.fill(null)
            head = 0
            count = 0
        }
    }

    fun size(): Int = synchronized(lock) { count }
}

/**
 * 把设备读到的任意长度数据拼成固定帧。
 *
 * 内部复用同一个 [FloatArray]，**返回值必须由调用方复制后入队**；
 * 一次 [push] 最多产出一帧，多余的样本请下次继续传入。
 */
class FrameAssembler(private val frameSize: Int) {
    private val frame = FloatArray(frameSize)
    private var filled = 0

    fun push(src: FloatArray, size: Int = src.size): FloatArray? {
        require(size <= src.size)
        var offset = 0
        while (offset < size) {
            val n = min(frameSize - filled, size - offset)
            System.arraycopy(src, offset, frame, filled, n)
            filled += n
            offset += n
            if (filled == frameSize) {
                filled = 0
                return frame
            }
        }
        return null
    }

    fun reset() {
        filled = 0
    }
}

/**
 * 样本级环形 FIFO，用于解耦"转换器按 hop 产出"和"播放线程按 20 ms 消费"。
 *
 * 写满时丢弃最旧样本，保证延迟有上界。
 */
class FloatFifo(private val capacity: Int) {
    init {
        require(capacity > 0)
    }

    private val buffer = FloatArray(capacity)
    private var head = 0
    private var count = 0
    private val lock = Any()

    fun write(src: FloatArray, offset: Int = 0, size: Int = src.size): Int {
        if (size <= 0) return 0
        synchronized(lock) {
            var srcOffset = offset
            var toWrite = size
            if (toWrite > capacity) {
                srcOffset += toWrite - capacity
                toWrite = capacity
            }
            val overflow = count + toWrite - capacity
            if (overflow > 0) {
                head = (head + overflow) % capacity
                count -= overflow
            }
            val tail = (head + count) % capacity
            val first = min(toWrite, capacity - tail)
            System.arraycopy(src, srcOffset, buffer, tail, first)
            if (first < toWrite) {
                System.arraycopy(src, srcOffset + first, buffer, 0, toWrite - first)
            }
            count += toWrite
            return toWrite
        }
    }

    fun read(dst: FloatArray, offset: Int = 0, size: Int = dst.size): Int {
        if (size <= 0) return 0
        synchronized(lock) {
            val n = min(size, count)
            if (n == 0) return 0
            val first = min(n, capacity - head)
            System.arraycopy(buffer, head, dst, offset, first)
            if (first < n) {
                System.arraycopy(buffer, 0, dst, offset + first, n - first)
            }
            head = (head + n) % capacity
            count -= n
            return n
        }
    }

    fun available(): Int = synchronized(lock) { count }

    fun clear() {
        synchronized(lock) {
            head = 0
            count = 0
        }
    }
}
