plugins {
    id("com.android.dynamic-feature")
    id("kotlin-android")
    id("kotlin-kapt")
}

android {
    namespace = "com.omnidroid.core.switch_emu"

    defaultConfig {
        missingDimensionStrategy("opensource", "play")
        missingDimensionStrategy("cores", "dynamic")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("*/*/libyuzu-android.so")
        }
    }
}

dependencies {
    implementation(project(":omnidroid-app"))
    implementation(kotlin(deps.libs.kotlin.stdlib))
}
