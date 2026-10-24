package com.omnidroid.tools.libretrodb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ThumbnailMatcherTest {
    @Test
    fun exclamationMarkMatchesTheThumbnailFilename() {
        val name = "Yu-Gi-Oh! The Duelists of the Roses (USA)"
        val library =
            ThumbnailLibrary(
                boxarts = listOf(name, "Yu-Gi-Oh_ The Duelists of the Roses (USA)"),
                titles = emptyList(),
                snaps = emptyList(),
            )
        assertEquals("Named_Boxarts/$name.png", library.pathFor(name))
    }

    @Test
    fun colonMatchesTheHyphenThumbnail() {
        val library =
            ThumbnailLibrary(
                boxarts =
                    listOf(
                        "WarioWare - Smooth Moves (Europe)",
                        "WarioWare - Smooth Moves (USA)",
                    ),
                titles = emptyList(),
                snaps = emptyList(),
            )
        assertEquals(
            "Named_Boxarts/WarioWare - Smooth Moves (USA).png",
            library.pathFor("WarioWare: Smooth Moves (USA)"),
        )
    }

    @Test
    fun extraDistributionTagIsUsedWhenItIsTheOnlyFile() {
        val library =
            ThumbnailLibrary(
                boxarts =
                    listOf(
                        "Final Fantasy Crystal Chronicles - My Life as a King (USA) (Demo) (WiiWare)",
                        "Final Fantasy Crystal Chronicles - My Life as a King (USA) (WiiWare)",
                        "Final Fantasy Crystal Chronicles - My Life as a King (Europe) (WiiWare)",
                    ),
                titles = emptyList(),
                snaps = emptyList(),
            )
        assertEquals(
            "Named_Boxarts/Final Fantasy Crystal Chronicles - My Life as a King (USA) (WiiWare).png",
            library.pathFor("Final Fantasy Crystal Chronicles: My Life as a King (USA)"),
        )
    }

    @Test
    fun articleSortedTitleMatchesTheNaturalTitle() {
        assertEquals(
            ThumbnailMatcher.matchKey("Legend of Zelda, The"),
            ThumbnailMatcher.matchKey("The Legend of Zelda"),
        )
    }

    @Test
    fun titlesAreUsedWhenBoxartIsMissing() {
        val name = "Only A Title (USA)"
        val library =
            ThumbnailLibrary(
                boxarts = emptyList(),
                titles = listOf(name),
                snaps = listOf(name),
            )
        assertEquals("Named_Titles/$name.png", library.pathFor(name))
    }

    @Test
    fun missingThumbnailStaysUnset() {
        val library = ThumbnailLibrary(emptyList(), emptyList(), emptyList())
        assertNull(library.pathFor("No Such Game (USA)"))
    }
}

class DatGamesTest {
    @Test
    fun wiiUDatRowKeepsNameSerialAndRom() {
        val file = File.createTempFile("wiiu", ".dat")
        file.writeText(
            """
            clrmamepro (
            	name "Nintendo - Nintendo Wii U"
            )

            game (
            	name "Mario Kart 8 (USA)"
            	serial "AMKE"
            	rom (
            		name "Mario Kart 8 (USA).wux"
            		serial "AMKE"
            	)
            )
            """.trimIndent(),
        )
        try {
            val rows = DatGames.read(file, "wiiu")
            assertEquals(1, rows.size)
            assertEquals("Mario Kart 8 (USA)", rows[0].name)
            assertEquals("wiiu", rows[0].system)
            assertEquals("AMKE", rows[0].serial)
            assertEquals("mario kart 8 (usa)", rows[0].romBase)
        } finally {
            file.delete()
        }
    }
}
