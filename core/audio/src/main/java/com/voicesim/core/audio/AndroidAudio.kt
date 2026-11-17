package com.voicesim.core.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import kotlin.math.max

/**
 * AudioRecord 封装。
 *
 * 默认使用 [MediaRecorder.AudioSource.VOICE_COMMUNICATION]，让系统开启 AEC / NS，
 * 对讲场景下能显著减少回声和底噪。纯录音场景可传入 [MediaRecorder.AudioSource.MIC]。
 */
class AndroidAudioRecordSource(
    override val spec: AudioSpec = AudioSpec(),
    private val audioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
) : AudioSource {

    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    override fun open() {
        check(record == null) { "source already opened" }

        val minBuffer = AudioRecord.getMinBufferSize(
            spec.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        require(minBuffer > 0) { "unsupported sample rate: ${spec.sampleRate}" }

        val bufferBytes = max(minBuffer, spec.bytesPerFrame * BUFFER_FRAMES)
        val created = AudioRecord.Builder()
            .setAudioSource(audioSource)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(spec.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()

        check(created.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
        created.startRecording()
        record = created
    }

    override fun read(dst: FloatArray, offset: Int, size: Int): Int {
        val r = record ?: return -1
        return r.read(dst, offset, size, AudioRecord.READ_BLOCKING)
    }

    override fun close() {
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }

    private companion object {
        const val BUFFER_FRAMES = 8
    }
}

/**
 * AudioTrack 封装，输出给扬声器或 App 内的通话上行链路。
 */
class AndroidAudioTrackSink(
    override val spec: AudioSpec = AudioSpec(),
    private val usage: Int = AudioAttributes.USAGE_VOICE_COMMUNICATION,
) : AudioSink {

    private var track: AudioTrack? = null

    override fun open() {
        check(track == null) { "sink already opened" }

        val attrs = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(spec.sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val created = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(spec.bytesPerFrame * OUTPUT_BUFFER_FRAMES)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        check(created.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack init failed" }
        created.play()
        track = created
    }

    override fun write(src: FloatArray, offset: Int, size: Int): Int {
        val t = track ?: return -1
        return t.write(src, offset, size, AudioTrack.WRITE_BLOCKING)
    }

    override fun close() {
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
    }

    private companion object {
        /**
         * 输出缓冲 4 帧（80 ms）。AudioTrack 缓冲直接计入端到端延迟，
         * 4 帧是"不频繁欠载"和"延迟可接受"的折中。
         */
        const val OUTPUT_BUFFER_FRAMES = 4
    }
}

/** 当前设备是否支持浮点采集（API 23+ 均支持，这里保留显式检查点）。 */
internal fun supportsFloatCapture(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
