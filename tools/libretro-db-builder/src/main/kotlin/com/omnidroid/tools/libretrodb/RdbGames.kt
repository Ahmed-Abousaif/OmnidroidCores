package com.omnidroid.tools.libretrodb

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

internal data class GameRow(
    val name: String?,
    val system: String,
    val romName: String?,
    val romBase: String?,
    val publisher: String?,
    val genre: String?,
    val releaseYear: Int?,
    val crc32: String?,
    val serial: String?,
    val code: String?,
    val size: Long?,
    val rawName: String? = name,
)

internal object RdbGames {
    private val MAGIC = "RARCHDB".toByteArray(Charsets.US_ASCII)

    private val AUDIO_TRACK = Regex("""\(Track\s+(?!0?1\b)\d+\)""", RegexOption.IGNORE_CASE)

    fun read(
        file: File,
        system: String,
    ): List<GameRow> {
        BufferedInputStream(FileInputStream(file), 1024 * 1024).use { input ->
            val header = ByteArray(16)
            readFully(input, header)
            require(header.copyOf(7).contentEquals(MAGIC)) { "Not an rdb file: ${file.name}" }
            val reader = RmsgpackReader(input)
            val rows = ArrayList<GameRow>()
            while (true) {
                val value = reader.readValue() ?: break
                val map = value as? Map<*, *> ?: continue
                val row = toRow(map, system)
                if (shouldKeep(row)) {
                    rows += row
                }
            }
            return rows
        }
    }

    private fun shouldKeep(row: GameRow): Boolean {
        val name = row.name ?: row.romName ?: return false
        if (name.isBlank()) return false
        if (row.system in setOf("psx", "scd", "pce", "dreamcast")) {
            if (AUDIO_TRACK.containsMatchIn(name) || (row.romName != null && AUDIO_TRACK.containsMatchIn(row.romName))) {
                return false
            }
        }
        if (name.contains("Magazine Demo", ignoreCase = true) ||
            name.contains("Interactive Sampler", ignoreCase = true) ||
            name.contains("PlayStation Underground", ignoreCase = true)
        ) {
            return false
        }
        return true
    }

    fun firstKeys(file: File): Set<String> {
        BufferedInputStream(FileInputStream(file)).use { input ->
            val header = ByteArray(16)
            readFully(input, header)
            val reader = RmsgpackReader(input)
            val value = reader.readValue() as? Map<*, *> ?: return emptySet()
            return value.keys.map { it.toString() }.toSet()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun toRow(
        raw: Map<*, *>,
        system: String,
    ): GameRow {
        val map = raw as Map<String, Any?>
        val rom = map["rom"] as? Map<String, Any?>
        val description = string(map, "description")
        val name = string(map, "name")
        // Boxart files are named after the short title. description is often a paragraph.
        val romName = string(rom, "name") ?: string(map, "rom_name") ?: name
        val serialRaw = string(rom, "serial") ?: string(map, "serial")
        val serial = normaliseSerial(system, serialRaw)
        val code =
            when (system) {
                "gamecube", "wii" -> Keys.nintendoDiscCode(serialRaw)
                else -> null
            }
        val crcBytes = (rom?.get("crc") ?: map["crc"]) as? ByteArray
        val size = long(rom, "size") ?: long(map, "size")
        val year = (long(map, "releaseyear") ?: long(map, "release_year"))?.toInt()
        return GameRow(
            name = name ?: description,
            system = system,
            romName = romName,
            romBase = Keys.romBase(romName),
            publisher = string(map, "publisher"),
            genre = string(map, "genre"),
            releaseYear = year?.takeIf { it in 1970..2100 },
            crc32 = crcBytes?.let { Keys.crcHex(it) },
            serial = serial,
            code = code,
            size = size?.takeIf { it > 0 },
        )
    }

    private fun normaliseSerial(
        system: String,
        raw: String?,
    ): String? {
        val trimmed = raw?.trim()?.ifBlank { null } ?: return null
        return when (system) {
            "psx", "ps2", "psp" -> Keys.playstationSerial(trimmed) ?: trimmed
            "scd" -> Keys.segaCdSerial(trimmed) ?: trimmed
            "gamecube", "wii" -> trimmed
            else -> trimmed
        }
    }

    private fun string(
        map: Map<String, Any?>?,
        key: String,
    ): String? {
        val value =
            when (val raw = map?.get(key)) {
                is String -> raw
                is ByteArray -> raw.toString(Charsets.US_ASCII)
                else -> return null
            }
        return value.trim { it <= ' ' || it == '\u0000' }.ifBlank { null }
    }

    private fun long(
        map: Map<String, Any?>?,
        key: String,
    ): Long? {
        return when (val value = map?.get(key)) {
            is Long -> value
            is Int -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun readFully(
        input: BufferedInputStream,
        bytes: ByteArray,
    ) {
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read < 0) error("Short rdb header")
            offset += read
        }
    }
}
