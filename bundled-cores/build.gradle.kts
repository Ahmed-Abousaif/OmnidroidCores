plugins {
    id("com.android.library")
    id("kotlin-android")
}

android {
    kotlinOptions {
        jvmTarget = "17"
    }
    namespace = "com.omnidroid.cores"

    sourceSets {
        getByName("main") {
            val coresParentDir = projectDir.parentFile
            val coreJniDirs = coresParentDir.listFiles { f ->
                f.isDirectory && f.name.startsWith("omnidroid_core_")
            }?.map { File(it, "src/main/jniLibs") }?.filter { it.isDirectory } ?: emptyList()

            if (coreJniDirs.isNotEmpty()) {
                jniLibs.setSrcDirs(coreJniDirs)
            }
        }
    }
}

dependencies {
    implementation(kotlin(deps.libs.kotlin.stdlib))
}
