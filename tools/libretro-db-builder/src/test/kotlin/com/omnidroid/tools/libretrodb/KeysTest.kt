package com.omnidroid.tools.libretrodb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeysTest {
    @Test
    fun playstationSerialDropsDiscSuffixAndNormalisesSeparators() {
        assertEquals("SLUS-00594", Keys.playstationSerial("SLUS_005.94"))
        assertEquals("SLUS-00594", Keys.playstationSerial("SLUS-00594"))
        assertEquals("SLPM-65002", Keys.playstationSerial("SLPM-65002-0"))
        assertEquals("NPJH-90348", Keys.playstationSerial("cdrom0:\\NPJH_90348;1"))
    }

    @Test
    fun nintendoDiscCodeKeepsTheFourCharacterId() {
        assertEquals("GW7P", Keys.nintendoDiscCode("DL-DOL-GW7P-EUR"))
        assertEquals("SP3E", Keys.nintendoDiscCode("RVL-SP3E-USA-B0"))
        assertNull(Keys.nintendoDiscCode("not a disc"))
    }

    @Test
    fun segaCdSerialDropsZeroPostfix() {
        assertEquals("T-12345", Keys.segaCdSerial("T-12345-00"))
        assertEquals("MK-4437-50", Keys.segaCdSerial("MK-4437-50"))
    }

    @Test
    fun romHashMatchesTheScanner() {
        assertEquals(2321860806577408022L, Keys.romHash("tony hawk's pro skater 4 (europe)"))
    }

    @Test
    fun romBaseStripsExtensionAndCase() {
        assertEquals(
            "tony hawk's pro skater 4 (europe)",
            Keys.romBase("Tony Hawk's Pro Skater 4 (Europe).cue"),
        )
    }

    @Test
    fun crcIsEightUpperHexDigits() {
        assertEquals("DDB5205D", Keys.crcHex(byteArrayOf(0xDD.toByte(), 0xB5.toByte(), 0x20, 0x5D)))
    }

    @Test
    fun humanizeConvertsArticleSortedTitles() {
        assertEquals("The Lion King (World)", Keys.humanize("Lion King, The (World)"))
        assertEquals("The Legend of Zelda (USA)", Keys.humanize("Legend of Zelda, The (USA)"))
        assertEquals("A Link to the Past", Keys.humanize("Link to the Past, A"))
        assertEquals("Super Mario World", Keys.humanize("Super Mario World"))
    }

    @Test
    fun normalizeTitleCleansPunctuationAndWhitespace() {
        assertEquals("the lion king (world)", Keys.normalizeTitle("The_Lion, King (World)"))
        assertEquals("super mario world", Keys.normalizeTitle("Super Mario World"))
    }

    @Test
    fun dreamcastRdbParsesAllGames() {
        val file = java.io.File("cache/Sega_-_Dreamcast.rdb")
        if (file.exists()) {
            val rows = RdbGames.read(file, "dreamcast")
            org.junit.Assert.assertTrue("Expected over 1000 Dreamcast games, got ${rows.size}", rows.size > 1000)
        }
    }
}
