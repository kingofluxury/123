plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.voicesim.core.vc"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":core:audio"))
    api(project(":core:dsp"))
    api(project(":core:inference"))

    // StateFlow 出现在 VoiceChangerController 的公开 API 上，必须 api 传递
    api(libs.kotlinx.coroutines.android)
}
