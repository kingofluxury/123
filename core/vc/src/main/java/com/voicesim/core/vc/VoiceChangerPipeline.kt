package com.voicesim.core.vc

import android.os.Process
import android.os.SystemClock
import com.voicesim.core.audio.AudioSink
import com.voicesim.core.audio.AudioSource
import com.voicesim.core.audio.AudioSpec
import com.voicesim.core.audio.AudioThreads
import com.voicesim.core.audio.FloatFifo
import com.voicesim.core.audio.FrameAssembler
import com.voicesim.core.audio.FrameQueue
import com.voicesim.core.dsp.Fade
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

enum class PipelineState { IDLE, RUNNING, ERROR }

/** 给 UI / 诊断用的实时指标。 */
data class PipelineStats(
    val state: PipelineState = PipelineState.IDLE,
    val hopMs: Int = 0,
    val processedChunks: Long = 0,
    val captureDrops: Long = 0,
    val playbackUnderruns: Long = 0,
    val fallbackChunks: Long = 0,
    val lastInferenceMs: Float = 0f,
    val averageInferenceMs: Float = 0f,
    val inputLevelDb: Float = -120f,
    val outputLevelDb: Float = -120f,
    val lastError: String? = null,
)

data class PipelineConfig(
    val audioSpec: AudioSpec = AudioSpec(),
    /**
     * 采集队列深度（帧）。必须覆盖"一块推理耗时 + 一块 hop 的累积时间"，
     * 否则推理期间采集会溢出。64 × 20 ms = 1.28 s，覆盖 640 ms 块 + 640 ms 推理余量。
     */
    val captureQueueFrames: Int = 64,
    /** 播放 FIFO 长度，容纳一整块 RVC 输出（640 ms）并留出抖动余量。 */
    val playbackBufferMs: Int = 2_000,
    val statsIntervalMs: Long = 250,
)

/**
 * 实时变声管线：采集线程 → 转换线程 → 播放线程。
 *
 * 线程之间只通过有界队列通信；任一环节变慢都表现为"丢最旧帧/欠载补静音"，
 * 不会级联阻塞，也不会让延迟无限增长。
 *
 * 生命周期由 [start] / [stop] 管理，必须在非 UI 线程调用 [start]（模型加载很慢）。
 */
class VoiceChangerPipeline(
    private val source: AudioSource,
    private val sink: AudioSink,
    private val converter: VoiceConverter,
    private val config: PipelineConfig = PipelineConfig(),
) {
    private val spec: AudioSpec = config.audioSpec
    private val captureQueue = FrameQueue(config.captureQueueFrames, spec.frameSamples)
    private val playbackFifo = FloatFifo(spec.samplesFor(config.playbackBufferMs))

    private val running = AtomicBoolean(false)
    private val captureDrops = AtomicLong()
    private val underruns = AtomicLong()
    private val fallbackChunks = AtomicLong()
    private val processedChunks = AtomicLong()

    private var threads: List<Thread> = emptyList()

    private val _stats = MutableStateFlow(PipelineStats())
    val stats: StateFlow<PipelineStats> = _stats.asStateFlow()

    fun start(profile: VoiceProfile, conversion: ConversionConfig) {
        check(!running.get()) { "pipeline already running" }

        try {
            converter.prepare(profile, conversion)
            require(converter.sampleRate == spec.sampleRate) {
                "converter sampleRate ${converter.sampleRate} != pipeline ${spec.sampleRate}"
            }
            require(converter.hopSamples > 0) { "converter.hopSamples must be > 0" }
            require(converter.contextSamples >= 0) { "converter.contextSamples must be >= 0" }

            source.open()
            sink.open()

            captureQueue.clear()
            playbackFifo.clear()
            running.set(true)

            threads = listOf(
                AudioThreads.capture("voicesim-capture") { captureLoop() },
                AudioThreads.start("voicesim-convert", Process.THREAD_PRIORITY_AUDIO) { convertLoop() },
                AudioThreads.playback("voicesim-playback") { playbackLoop() },
            )

            _stats.value = PipelineStats(
                state = PipelineState.RUNNING,
                hopMs = converter.hopSamples * 1000 / spec.sampleRate,
            )
        } catch (t: Throwable) {
            running.set(false)
            runCatching { source.close() }
            runCatching { sink.close() }
            runCatching { converter.close() }
            _stats.value = PipelineStats(state = PipelineState.ERROR, lastError = t.message)
            throw t
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return

        // 关闭输入设备以唤醒阻塞中的 read()
        runCatching { source.close() }
        threads.forEach { it.join(500) }

        // 再关闭输出设备，唤醒阻塞中的 write()
        runCatching { sink.close() }
        threads.forEach { it.join(200) }

        captureQueue.clear()
        playbackFifo.clear()
        converter.reset()
        runCatching { converter.close() }
        threads = emptyList()

        _stats.update { it.copy(state = PipelineState.IDLE) }
    }

    // ---------------------------------------------------------------- capture

    private fun captureLoop() {
        val readBuffer = FloatArray(spec.frameSamples)
        val assembler = FrameAssembler(spec.frameSamples)

        while (running.get()) {
            val read = runCatching { source.read(readBuffer, 0, readBuffer.size) }
                .getOrDefault(-1)

            if (read < 0) {
                if (!running.get()) break
                Thread.sleep(5)
                continue
            }
            if (read == 0) {
                Thread.sleep(1)
                continue
            }

            val frame = assembler.push(readBuffer, read) ?: continue
            if (!captureQueue.push(frame.copyOf())) {
                captureDrops.incrementAndGet()
            }
        }
    }

    // ---------------------------------------------------------------- convert

    private fun convertLoop() {
        val hop = converter.hopSamples
        val context = converter.contextSamples
        require(hop > 0) { "converter.hopSamples must be > 0" }

        val window = FloatArray(context + hop)
        val hopBuffer = FloatArray(hop)
        val output = FloatArray(hop)
        val frame = FloatArray(spec.frameSamples)

        var filled = 0
        var lastStatsAt = 0L
        var totalInferenceMs = 0.0
        var lastInferenceMs = 0f
        var chunks = 0L

        while (running.get()) {
            if (!captureQueue.pop(frame)) {
                Thread.sleep(1)
                continue
            }

            var offset = 0
            while (offset < frame.size && running.get()) {
                val n = min(hop - filled, frame.size - offset)
                System.arraycopy(frame, offset, hopBuffer, filled, n)
                filled += n
                offset += n

                if (filled < hop) continue

                // 滑动窗口：左移 hop，把新样本放到尾部
                if (context > 0) {
                    System.arraycopy(window, hop, window, 0, context)
                }
                System.arraycopy(hopBuffer, 0, window, context, hop)

                val inputDb = dbOf(hopBuffer, hop)
                val startNs = SystemClock.elapsedRealtimeNanos()
                val written = try {
                    converter.convert(window, output)
                } catch (t: Throwable) {
                    fallbackChunks.incrementAndGet()
                    copyTail(window, output, hop)
                }
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000f

                lastInferenceMs = elapsedMs
                totalInferenceMs += elapsedMs
                chunks++
                processedChunks.incrementAndGet()

                if (written > 0) {
                    playbackFifo.write(output, 0, written)
                }

                val now = SystemClock.elapsedRealtime()
                if (now - lastStatsAt >= config.statsIntervalMs) {
                    lastStatsAt = now
                    val average = if (chunks > 0) (totalInferenceMs / chunks).toFloat() else 0f
                    val outputDb = dbOf(output, min(written, output.size))
                    _stats.update {
                        it.copy(
                            processedChunks = processedChunks.get(),
                            captureDrops = captureDrops.get(),
                            playbackUnderruns = underruns.get(),
                            fallbackChunks = fallbackChunks.get(),
                            lastInferenceMs = lastInferenceMs,
                            averageInferenceMs = average,
                            inputLevelDb = inputDb,
                            outputLevelDb = outputDb,
                            lastError = null,
                        )
                    }
                }

                filled = 0
            }
        }
    }

    // --------------------------------------------------------------- playback

    private fun playbackLoop() {
        val frame = FloatArray(spec.frameSamples)
        val silence = FloatArray(spec.frameSamples)

        while (running.get()) {
            val read = playbackFifo.read(frame, 0, frame.size)
            if (read == frame.size) {
                runCatching { sink.write(frame, 0, frame.size) }
            } else {
                if (read > 0) {
                    runCatching { sink.write(frame, 0, read) }
                }
                val padding = frame.size - read
                runCatching { sink.write(silence, 0, padding) }
                underruns.incrementAndGet()
            }
        }
    }

    // ------------------------------------------------------------------ utils

    private fun copyTail(window: FloatArray, output: FloatArray, hop: Int): Int {
        val n = min(hop, min(window.size, output.size))
        System.arraycopy(window, window.size - n, output, 0, n)
        return n
    }

    private fun dbOf(buffer: FloatArray, size: Int): Float {
        if (size <= 0) return -120f
        return 20f * log10(max(Fade.rms(buffer, size), 1e-7f))
    }
}
