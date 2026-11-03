plugins {
    id("com.android.dynamic-feature")
    id("kotlin-android")
    id("kotlin-kapt")
}

android {
    namespace = "com.omnidroid.core.dosbox_pure"

    defaultConfig {
        missingDimensionStrategy("opensource", "play")
        missingDimensionStrategy("cores", "dynamic")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("*/*/*_libretro_android.so")
        }
    }
}

dependencies {
    implementation(project(":omnidroid-app"))
    implementation(kotlin(deps.libs.kotlin.stdlib))
}
