package com.omnidroid.tools.libretrodb

import java.io.File

internal object DatGames {
    private val QUOTED = Regex("""^(\w+)\s+"(.*)"\s*$""")

    fun read(
        file: File,
        system: String,
    ): List<GameRow> {
        var inGame = false
        var depth = 0
        var inRom = false
        var name: String? = null
        var serial: String? = null
        var romName: String? = null
        var romSerial: String? = null
        val rows = ArrayList<GameRow>()
        file.bufferedReader().useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                if (!inGame) {
                    if (line.startsWith("game (") || line == "game(") {
                        inGame = true
                        depth = 1
                        inRom = false
                        name = null
                        serial = null
                        romName = null
                        romSerial = null
                    }
                    return@forEach
                }
                if (line.startsWith("rom (") || line == "rom(") {
                    inRom = true
                    depth++
                    return@forEach
                }
                if (line.startsWith(")")) {
                    depth--
                    if (inRom && depth == 1) inRom = false
                    if (depth <= 0) {
                        val title = name?.trim()?.ifBlank { null }
                        if (title != null) {
                            val fileName = romName?.trim()?.ifBlank { null } ?: title
                            rows +=
                                GameRow(
                                    name = title,
                                    system = system,
                                    romName = fileName,
                                    romBase = Keys.romBase(fileName),
                                    publisher = null,
                                    genre = null,
                                    releaseYear = null,
                                    crc32 = null,
                                    serial = (romSerial ?: serial)?.trim()?.ifBlank { null },
                                    code = null,
                                    size = null,
                                )
                        }
                        inGame = false
                    }
                    return@forEach
                }
                val match = QUOTED.find(line) ?: return@forEach
                val key = match.groupValues[1]
                val value = match.groupValues[2]
                when {
                    key == "name" && inRom -> romName = value
                    key == "name" -> name = value
                    key == "serial" && inRom -> romSerial = value
                    key == "serial" -> serial = value
                }
            }
        }
        return rows
    }
}
