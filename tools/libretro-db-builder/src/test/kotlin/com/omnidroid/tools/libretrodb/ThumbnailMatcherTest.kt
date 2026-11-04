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
    fun articleSortedSubtitleMatchesNaturalDatTitle() {
        assertEquals(
            ThumbnailMatcher.matchKey(
                "Legend of Zelda, The - The Wind Waker HD (USA, Asia) (En,Fr,Es)",
            ),
            ThumbnailMatcher.matchKey("The Legend of Zelda: The Wind Waker HD (USA)"),
        )
    }

    @Test
    fun windWakerUsaBoxartIsChosenForUsaDatRow() {
        val library =
            ThumbnailLibrary(
                boxarts =
                    listOf(
                        "Legend of Zelda, The - The Wind Waker HD (Europe, Australia) (En,Fr,De,Es,It)",
                        "Legend of Zelda, The - The Wind Waker HD (USA, Asia) (En,Fr,Es)",
                    ),
                titles = emptyList(),
                snaps = emptyList(),
            )
        assertEquals(
            "Named_Boxarts/Legend of Zelda, The - The Wind Waker HD (USA, Asia) (En,Fr,Es).png",
            library.pathFor("The Legend of Zelda: The Wind Waker HD (USA)"),
        )
    }

    @Test
    fun japanDatDoesNotInheritUsaBoxartOnExactKeyPass() {
        // Pass 1 (choose) stays region-strict; pass 3 may still cross-region later.
        assertNull(
            ThumbnailMatcher.choose(
                "THE LEGEND OF ZELDA The Wind Waker HD (Trial) (Japan)",
                listOf("Legend of Zelda, The - The Wind Waker HD (USA, Asia) (En,Fr,Es)"),
            ),
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

    @Test
    fun ampersandMatchesAndInThumbnailFilename() {
        val library =
            ThumbnailLibrary(
                boxarts =
                    listOf(
                        "Regular Show - Mordecai and Rigby in 8-bit Land (Europe)",
                        "Regular Show - Mordecai and Rigby in 8-bit Land (USA)",
                    ),
                titles = emptyList(),
                snaps = emptyList(),
            )
        assertEquals(
            "Named_Boxarts/Regular Show - Mordecai and Rigby in 8-bit Land (USA).png",
            library.pathFor("Regular Show - Mordecai & Rigby in 8-Bit Land (USA)"),
        )
    }

    @Test
    fun relocatedSubtitleTokenStillMatchesSortedKey() {
        val library =
            ThumbnailLibrary(
                boxarts =
                    listOf(
                        "Metal Gear Solid 3D - Snake Eater (Europe) (En,Fr,De,Es,It)",
                        "Metal Gear Solid 3D - Snake Eater (USA) (En,Fr,Es)",
                    ),
                titles = emptyList(),
                snaps = emptyList(),
            )
        assertEquals(
            "Named_Boxarts/Metal Gear Solid 3D - Snake Eater (USA) (En,Fr,Es).png",
            library.pathFor("Metal Gear Solid - Snake Eater 3D (USA) (En,Fr,Es)"),
        )
    }

    @Test
    fun possessiveSIsIgnoredInMatchKey() {
        assertEquals(
            ThumbnailMatcher.matchKey("Yoshi New Island (Korea)"),
            ThumbnailMatcher.matchKey("Yoshi's New Island (Korea)"),
        )
    }

    @Test
    fun dlcPackFallsBackToBaseGameBoxart() {
        val library =
            ThumbnailLibrary(
                boxarts =
                    listOf(
                        "Dead or Alive - Dimensions (Europe) (En,Ja,Fr,De,Es,It)",
                        "Dead or Alive - Dimensions (USA) (En,Ja,Fr,De,Es,It)",
                    ),
                titles = emptyList(),
                snaps = emptyList(),
            )
        assertEquals(
            "Named_Boxarts/Dead or Alive - Dimensions (Europe) (En,Ja,Fr,De,Es,It).png",
            library.pathFor("Dead or Alive - Dimensions - Pack 1 (Europe) (DLC)"),
        )
    }

    @Test
    fun shovelKnightTreasureTroveUpdateUsesRetailBoxart() {
        val library =
            ThumbnailLibrary(
                boxarts =
                    listOf(
                        "Shovel Knight (Europe) (En,Fr,De,Es,It)",
                        "Shovel Knight (USA)",
                    ),
                titles = emptyList(),
                snaps = emptyList(),
            )
        assertEquals(
            "Named_Boxarts/Shovel Knight (USA).png",
            library.pathFor("Shovel Knight - Treasure Trove (USA) (v4.1, v5200) (Update)"),
        )
    }

    @Test
    fun fuzzyMatchesLocalizedSubtitleWithSharedFranchisePrefix() {
        val library =
            ThumbnailLibrary(
                boxarts =
                    listOf(
                        "LEGO Legends of Chima - Laval's Journey (Europe) (En,Fr,De,Es,It,Nl,Da)",
                        "LEGO Legends of Chima - Laval's Journey (Korea)",
                    ),
                titles = emptyList(),
                snaps = emptyList(),
            )
        assertEquals(
            "Named_Boxarts/LEGO Legends of Chima - Laval's Journey (Korea).png",
            library.pathFor("LEGO Legends of Chima - Lavalui Yeohaeng (Korea)"),
        )
    }

    @Test
    fun fuzzyMatchesNearTokenRomanization() {
        val library =
            ThumbnailLibrary(
                boxarts = listOf("Yogoe Watch Busters - Jeogmyodan (Korea)"),
                titles = emptyList(),
                snaps = emptyList(),
            )
        assertEquals(
            "Named_Boxarts/Yogoe Watch Busters - Jeogmyodan (Korea).png",
            library.pathFor("Yogoe Watch Busters - Jeongmyodan (Korea)"),
        )
    }

    @Test
    fun fuzzyPass3AllowsCrossRegionWhenNoLocalArt() {
        val library =
            ThumbnailLibrary(
                boxarts =
                    listOf(
                        "Yoshi's New Island (Europe) (En,Fr,De,Es,It,Nl,Pt,Ru)",
                        "Yoshi's New Island (Japan)",
                        "Yoshi's New Island (USA) (En,Fr,Es)",
                    ),
                titles = emptyList(),
                snaps = emptyList(),
            )
        assertEquals(
            "Named_Boxarts/Yoshi's New Island (Japan).png",
            library.pathFor("Yoshi New Island (Korea)"),
        )
    }

    @Test
    fun fuzzyPass3RejectsShortFranchiseFragmentAcrossRegions() {
        val library =
            ThumbnailLibrary(
                boxarts = listOf("The Legend of Zelda (USA)"),
                titles = emptyList(),
                snaps = emptyList(),
            )
        assertNull(
            library.pathFor("THE LEGEND OF ZELDA The Wind Waker HD (Trial) (Japan)"),
        )
    }

    @Test
    fun fuzzyFranchiseFallbackMatchesUniqueSameRegionTitle() {
        val library =
            ThumbnailLibrary(
                boxarts =
                    listOf(
                        "MapleStory - The Girl's Fate (Korea)",
                        "MapleStory - Unmei no Shoujo (Japan)",
                    ),
                titles = emptyList(),
                snaps = emptyList(),
            )
        assertEquals(
            "Named_Boxarts/MapleStory - The Girl's Fate (Korea).png",
            library.pathFor("MapleStory - Unmyeongui Sonyeo (Korea)"),
        )
    }

    @Test
    fun fuzzyPass3MatchesSushiStrikerWithoutJapanArt() {
        val library =
            ThumbnailLibrary(
                boxarts =
                    listOf(
                        "Sushi Striker - The Way of Sushido (Europe) (En,Fr,De,Es,It,Nl)",
                        "Sushi Striker - The Way of Sushido (USA) (En,Fr,Es)",
                    ),
                titles = emptyList(),
                snaps = emptyList(),
            )
        assertEquals(
            "Named_Boxarts/Sushi Striker - The Way of Sushido (USA) (En,Fr,Es).png",
            library.pathFor("Choukaiten Sushi Striker - The Way of Sushido (Japan)"),
        )
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
