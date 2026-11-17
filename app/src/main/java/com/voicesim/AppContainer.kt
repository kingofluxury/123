package com.voicesim

import android.content.Context
import com.voicesim.core.inference.InferenceBackend
import com.voicesim.core.inference.InferenceBackends
import com.voicesim.core.vc.AssetModelInstaller
import com.voicesim.core.vc.ConverterFactory
import com.voicesim.core.vc.FileVoiceProfileStore
import com.voicesim.core.vc.VoiceProfileStore
import java.io.File

/**
 * 手动依赖注入容器。
 *
 * 只做对象装配，不含业务逻辑；以后需要 Hilt/Koin 时可整体替换，不影响模块内部。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val modelRoot: File = File(appContext.filesDir, "models")

    private val profiles: VoiceProfileStore = FileVoiceProfileStore(modelRoot)

    /** 首次真正创建转换器时才加载 ORT，避免冷启动就吃内存。 */
    private val backend: InferenceBackend by lazy { InferenceBackends.create() }

    val controller: VoiceChangerController

    init {
        modelRoot.mkdirs()
        val installer = AssetModelInstaller(appContext, modelRoot)
        controller = VoiceChangerController(
            context = appContext,
            store = profiles,
            factory = ConverterFactory { backend },
            installer = installer,
        )
    }
}
