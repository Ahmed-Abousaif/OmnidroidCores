package com.omnidroid.tools.libretrodb

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale

internal object SwitchGames {
    private val HEX_16 = Regex("""^[0-9A-Fa-f]{16}$""")

    private class SwitchEntry(
        val id: String,
        val cleanedName: String,
        val rawName: String,
        val size: Long?,
        val isBase: Boolean,
    )

    fun read(
        file: File,
        system: String,
    ): List<GameRow> {
        val byId = HashMap<String, SwitchEntry>()

        BufferedInputStream(FileInputStream(file), 1024 * 1024).use { bis ->
            JsonReader(InputStreamReader(bis, StandardCharsets.UTF_8)).use { reader ->
                reader.setStrictness(Strictness.LENIENT)
                if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                    return emptyList()
                }
                reader.beginObject()
                while (reader.hasNext()) {
                    reader.nextName() // top-level key (usually the title ID)
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                        parseEntry(reader)?.let { entry ->
                            // Prioritize base games (...000) over updates/DLCs, then larger size
                            val existing = byId[entry.id]
                            if (existing == null ||
                                (entry.isBase && !existing.isBase) ||
                                (entry.isBase == existing.isBase && (entry.size ?: 0L) > (existing.size ?: 0L))
                            ) {
                                byId[entry.id] = entry
                            }
                        }
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            }
        }

        return byId.values
            .sortedWith(compareBy({ it.cleanedName.lowercase(Locale.US) }, { it.id }))
            .map { entry ->
                val humanized = Keys.humanize(entry.cleanedName) ?: entry.cleanedName
                GameRow(
                    name = humanized,
                    system = system,
                    romName = "$humanized.nsp",
                    romBase = Keys.romBase(humanized),
                    publisher = null,
                    genre = null,
                    releaseYear = null,
                    crc32 = null,
                    serial = entry.id,
                    code = null,
                    size = entry.size?.takeIf { it > 0 },
                    rawName = if (entry.rawName != humanized) entry.rawName else null,
                    thumbnailPath = "Named_Boxarts/$humanized.png",
                )
            }
    }

    private fun parseEntry(reader: JsonReader): SwitchEntry? {
        var id: String? = null
        var name: String? = null
        var size: Long? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> {
                    if (reader.peek() == JsonToken.STRING) {
                        id = reader.nextString().trim().uppercase(Locale.US)
                    } else {
                        reader.skipValue()
                    }
                }
                "name" -> {
                    if (reader.peek() == JsonToken.STRING) {
                        name = reader.nextString()
                    } else {
                        reader.skipValue()
                    }
                }
                "size" -> {
                    if (reader.peek() == JsonToken.NUMBER) {
                        size = reader.nextLong()
                    } else {
                        reader.skipValue()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (id == null || !HEX_16.matches(id)) return null
        if (name.isNullOrBlank()) return null

        val cleaned = cleanName(name)
        if (cleaned.isBlank()) return null

        val isBase = id.endsWith("000")
        return SwitchEntry(
            id = id,
            cleanedName = cleaned,
            rawName = name.trim(),
            size = size,
            isBase = isBase,
        )
    }

    private fun cleanName(raw: String): String {
        var c = raw.replace("\ufffd", "")
        c = c.replace("\u2122", "") // ™
        c = c.replace("\u00ae", "") // ®
        c = c.replace("\u00a9", "") // ©
        c = c.replace(Regex("""\s{2,}"""), " ").trim()
        return c
    }
}
