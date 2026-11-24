package com.omnidroid.tools.libretrodb

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.DriverManager

class CatalogSeedsTest {
    @get:Rule val temp = TemporaryFolder()
    private val key = "a".repeat(64)

    @Test fun exportOnlyImportsAcceptedRecordsForTheRequestedPlatform() {
        val page = JsonParser.parseString("""{"records":[
            {"system":"snes","lookup_key":"$key","content":{"provider":"igdb","providerGameId":"1070","title":"Super Mario World","assets":[]}},
            {"system":"psx","lookup_key":"$key","content":{"provider":"igdb","providerGameId":"1"}},
            {"system":"snes","lookup_key":"invalid","content":{"provider":"igdb","providerGameId":"2"}}
        ]}""").asJsonObject
        val seeds = CatalogSeeds.parseRecords(page, "snes")
        assertEquals(1, seeds.size)
        val payload = JsonParser.parseString(seeds.single().payload).asJsonObject
        assertEquals("matched", payload["state"].asString)
        assertEquals("1070", payload.getAsJsonObject("match")["providerGameId"].asString)
    }

    @Test fun seedsTravelInsideTheChecksummedSqlitePayloadWithoutChangingGameRows() {
        Class.forName("org.sqlite.JDBC")
        val sqlite = temp.newFile("seeded.sqlite")
        val row = GameRow("Example", "snes", null, null, null, null, null, null, null, null, null)
        val seed = CatalogSeedRow("snes", key, "{\"state\":\"matched\"}")
        SliceWriter.writeGames(sqlite, listOf(row), listOf(seed))
        DriverManager.getConnection("jdbc:sqlite:${sqlite.absolutePath}").use { connection ->
            connection.createStatement().executeQuery("SELECT count(*) FROM games").use { assertTrue(it.next()); assertEquals(1, it.getInt(1)) }
            connection.createStatement().executeQuery("SELECT payload FROM catalog_seeds WHERE lookupKey = '$key'").use {
                assertTrue(it.next()); assertEquals(seed.payload, it.getString(1))
            }
        }
        assertEquals(64, SliceWriter.sha256(SliceWriter.gzip(sqlite)).length)
    }
}
