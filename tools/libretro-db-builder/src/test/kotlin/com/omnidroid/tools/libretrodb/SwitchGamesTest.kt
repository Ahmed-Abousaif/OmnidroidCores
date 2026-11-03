package com.omnidroid.tools.libretrodb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SwitchGamesTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun parsesAndDeduplicatesSwitchTitles() {
        val testJson = """
        {
            "0100000000010000": {
                "id": "0100000000010000",
                "name": "Super Mario Odyssey™",
                "size": 5683281920
            },
            "0100000000010800": {
                "id": "0100000000010800",
                "name": "Super Mario Odyssey Update v1.3.0",
                "size": 314572800
            },
            "0100152000022000": {
                "id": "0100152000022000",
                "name": "Zelda: Breath of the Wild, The®",
                "size": 14680064000
            },
            "INVALID_ID": {
                "id": "INVALID_ID",
                "name": "Corrupt Entry"
            }
        }
        """.trimIndent()

        val jsonFile = tempFolder.newFile("test_switch.json")
        jsonFile.writeText(testJson)

        val rows = SwitchGames.read(jsonFile, "switch")
        assertEquals(3, rows.size)

        val odyssey = rows.firstOrNull { it.serial == "0100000000010000" }
        assertNotNull(odyssey)
        assertEquals("Super Mario Odyssey", odyssey?.name)
        assertEquals("0100000000010000", odyssey?.serial)
        assertEquals("switch", odyssey?.system)
        assertEquals("Named_Boxarts/Super Mario Odyssey.png", odyssey?.thumbnailPath)
        assertEquals(5683281920L, odyssey?.size)

        val zelda = rows.firstOrNull { it.serial == "0100152000022000" }
        assertNotNull(zelda)
        assertEquals("The Zelda: Breath of the Wild", zelda?.name)
        assertEquals("Named_Boxarts/The Zelda: Breath of the Wild.png", zelda?.thumbnailPath)
    }

    @Test
    fun prioritizesBaseGameOverUpdateOnCollision() {
        val testJson = """
        {
            "0100000000010000_ALT": {
                "id": "0100000000010000",
                "name": "Super Mario Odyssey (Update)",
                "size": 100
            },
            "0100000000010000": {
                "id": "0100000000010000",
                "name": "Super Mario Odyssey",
                "size": 500
            }
        }
        """.trimIndent()

        val jsonFile = tempFolder.newFile("test_collision.json")
        jsonFile.writeText(testJson)

        val rows = SwitchGames.read(jsonFile, "switch")
        assertEquals(1, rows.size)
        assertEquals(500L, rows[0].size)
    }
}

