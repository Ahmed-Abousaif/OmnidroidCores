package com.omnidroid.tools.libretrodb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BundledDatabaseTest {
    @Test
    fun sharedSlicesAreByteIdenticalAndDetectionIndexIsSmall() {
        val cores = File("../..")
        val melondsds = File(cores, "omnidroid_core_melondsds/src/main/assets/libretro-db/melondsds/nds.sqlite.gz")
        assertTrue(melondsds.exists())
        val detect =
            File(
                "../..",
                "../omnidroid-metadata-libretro-db/src/main/assets/libretro-detect.sqlite",
            )
        assertTrue(detect.exists())
        assertTrue(detect.length() < 1024 * 1024)
        val manifest = File(cores, "omnidroid_core_gambatte/src/main/assets/libretro-db/gambatte/manifest.json").readText()
        val sha = Regex(""""sha256":"([0-9a-f]+)"""").find(manifest)!!.groupValues[1]
        val fileName = Regex(""""file":"([^"]+)"""").find(manifest)!!.groupValues[1]
        val bytes = File(cores, "omnidroid_core_gambatte/src/main/assets/libretro-db/gambatte/$fileName").readBytes()
        assertEquals(sha, SliceWriter.sha256(bytes))
    }
}
