package com.voicesim.core.audio

import android.os.Process

/**
 * 音频线程工具。
 *
 * 实时音频不用协程：协程的调度点不可控，会引入周期性抖动。
 * 这里统一创建带优先级的守护线程，异常向上抛给调用方统一记录。
 */
object AudioThreads {

    fun start(
        name: String,
        priority: Int = Process.THREAD_PRIORITY_AUDIO,
        body: () -> Unit,
    ): Thread {
        val thread = Thread({
            Process.setThreadPriority(priority)
            body()
        }, name)
        thread.isDaemon = true
        thread.start()
        return thread
    }

    fun capture(name: String, body: () -> Unit): Thread =
        start(name, Process.THREAD_PRIORITY_URGENT_AUDIO, body)

    fun playback(name: String, body: () -> Unit): Thread =
        start(name, Process.THREAD_PRIORITY_URGENT_AUDIO, body)
}
