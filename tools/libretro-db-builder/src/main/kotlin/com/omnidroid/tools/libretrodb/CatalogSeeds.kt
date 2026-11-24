package com.omnidroid.tools.libretrodb

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal data class CatalogSeedRow(val system: String, val lookupKey: String, val payload: String)

/** Imports already accepted catalog records only. This endpoint never queries IGDB. */
internal object CatalogSeeds {
    fun load(origin: String?, systems: Collection<String>): List<CatalogSeedRow> {
        if (origin.isNullOrBlank()) return emptyList()
        val base = URI(origin.trimEnd('/'))
        require(base.scheme == "https" && base.host != null && base.userInfo == null) { "Catalog export requires an HTTPS origin" }
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
        val result = ArrayList<CatalogSeedRow>()
        for (system in systems.distinct()) {
            require(Regex("[a-z0-9_+-]{1,40}").matches(system))
            var offset = 0
            do {
                val request = HttpRequest.newBuilder(URI("$base/api/metadata/catalog?system=$system&offset=$offset"))
                    .timeout(Duration.ofSeconds(30)).header("Accept", "application/json").GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                check(response.statusCode() == 200) { "Catalog export failed for $system: HTTP ${response.statusCode()}" }
                val page = JsonParser.parseString(response.body()).asJsonObject
                check(page["schemaVersion"].asInt == 1) { "Unsupported catalog export schema" }
                result += parseRecords(page, system)
                val next = page["nextOffset"]
                val hasNext = next != null && !next.isJsonNull
                if (hasNext) {
                    val value = next.asInt
                    check(value > offset && value <= 1_000_000) { "Invalid catalog pagination" }
                    offset = value
                }
            } while (hasNext)
        }
        return result.distinctBy { it.system to it.lookupKey }
    }

    internal fun parseRecords(page: JsonObject, system: String): List<CatalogSeedRow> =
        page.getAsJsonArray("records").mapNotNull { element ->
            val row = element.asJsonObject
            if (row["system"]?.asString != system) return@mapNotNull null
            val key = row["lookup_key"]?.asString ?: return@mapNotNull null
            if (!Regex("[a-f0-9]{64}").matches(key)) return@mapNotNull null
            val content = row.getAsJsonObject("content") ?: return@mapNotNull null
            if (content["provider"]?.asString.isNullOrBlank() || content["providerGameId"]?.asString.isNullOrBlank()) return@mapNotNull null
            val payload = JsonObject().apply {
                addProperty("key", key)
                addProperty("state", "matched")
                add("match", content.deepCopy())
            }
            CatalogSeedRow(system, key, payload.toString())
        }
}
