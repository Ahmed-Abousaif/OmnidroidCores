package com.omnidroid.tools.libretrodb

import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import java.util.zip.GZIPInputStream

class PicoDriveCatalogTest {
    @Test
    fun picoDriveCatalogContains32xAndIdenticalSharedSegaSlices() {
        val cores = File("../..")
        val pico = File(cores, "omnidroid_core_picodrive/src/main/assets/libretro-db/picodrive")
        val gx = File(cores, "omnidroid_core_genesis_plus_gx/src/main/assets/libretro-db/genesis_plus_gx")
        val manifest = JsonParser.parseString(File(pico, "manifest.json").readText()).asJsonObject
        val entries = manifest.getAsJsonArray("slices").map { it.asJsonObject }
        assertEquals(setOf("md", "scd", "32x"), entries.map { it.get("sliceId").asString }.toSet())
        entries.forEach { entry ->
            val file = File(pico, entry.get("file").asString)
            assertEquals(entry.get("sha256").asString, SliceWriter.sha256(file.readBytes()))
        }
        listOf("md", "scd").forEach { id ->
            assertArrayEquals(File(gx, "$id.sqlite.gz").readBytes(), File(pico, "$id.sqlite.gz").readBytes())
        }
        val unpacked = File.createTempFile("sega32x-catalog", ".sqlite")
        try {
            GZIPInputStream(File(pico, "32x.sqlite.gz").inputStream()).use { input ->
                unpacked.outputStream().use { input.copyTo(it) }
            }
            DriverManager.getConnection("jdbc:sqlite:${unpacked.absolutePath}").use { database ->
                val result =
                    database.createStatement().executeQuery(
                        "SELECT count(*),count(DISTINCT crc32),sum(thumbnailPath IS NOT NULL) FROM games WHERE system = '32x'",
                    )
                assertTrue(result.next())
                assertTrue(result.getInt(1) > 100)
                assertTrue(result.getInt(2) > 100)
                assertTrue(result.getInt(3) > 0)
            }
        } finally {
            unpacked.delete()
        }
    }
}
