package com.omnidroid.tools.libretrodb

import java.io.File
import java.io.FileInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

internal data class ArcadeMember(
    val system: String,
    val romName: String,
    val crc: String,
)

internal object ArcadeDats {
    fun members(
        file: File,
        system: String,
        overlapSets: Set<String>,
    ): List<ArcadeMember> {
        if (overlapSets.isEmpty()) return emptyList()
        val factory = XMLInputFactory.newFactory()
        factory.setProperty(XMLInputFactory.IS_COALESCING, true)
        FileInputStream(file).use { stream ->
            val reader = factory.createXMLStreamReader(stream)
            val rows = ArrayList<ArcadeMember>()
            var setName: String? = null
            var inGame = false
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        when (reader.localName) {
                            "game", "machine" -> {
                                setName = attribute(reader, "name")?.lowercase()
                                inGame = setName != null && setName in overlapSets
                            }
                            "rom" -> {
                                if (inGame) {
                                    val crc = attribute(reader, "crc")?.uppercase()?.padCrc()
                                    val name = setName
                                    if (crc != null && name != null) {
                                        rows += ArcadeMember(system, name, crc)
                                    }
                                }
                            }
                        }
                    }
                    XMLStreamConstants.END_ELEMENT -> {
                        if (reader.localName == "game" || reader.localName == "machine") {
                            inGame = false
                            setName = null
                        }
                    }
                }
            }
            reader.close()
            return rows
        }
    }

    private fun attribute(
        reader: XMLStreamReader,
        name: String,
    ): String? {
        return reader.getAttributeValue(null, name)?.trim()?.ifBlank { null }
    }

    private fun String.padCrc(): String? {
        val hex = filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }
        if (hex.isEmpty() || hex.length > 8) return null
        return hex.padStart(8, '0')
    }
}
