package com.omnidroid.tools.libretrodb

import java.util.Locale

/**
 * Key normalisation shared with the scanner. The same rules live in
 * com.omnidroid.lib.library.scan.ScanKeys and must stay in step with this file.
 */
internal object Keys {
    private val PLAYSTATION_SERIAL = Regex("([A-Z]{3,5})[-_]?([0-9]{3,5})(?:\\.([0-9]{2}))?")
    private val NINTENDO_DISC_CODE = Regex("(?:DOL|RVL)-([A-Z0-9]{4})")
    private val SEGA_CD_SERIAL = Regex("([A-Z]+)?-?([0-9]+) ?-?([0-9]*)")

    fun playstationSerial(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val match = PLAYSTATION_SERIAL.find(raw.uppercase(Locale.US)) ?: return null
        val prefix = match.groupValues[1]
        val number = match.groupValues[2]
        val fraction = match.groupValues[3]
        return if (fraction.isNotEmpty()) "$prefix-$number$fraction" else "$prefix-$number"
    }

    fun nintendoDiscCode(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return NINTENDO_DISC_CODE.find(raw.uppercase(Locale.US))?.groupValues?.get(1)
    }

    fun segaCdSerial(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val groups = SEGA_CD_SERIAL.find(raw.uppercase(Locale.US)) ?: return null
        val prefix = groups.groupValues[1].ifBlank { null }
        val number = groups.groupValues[2].ifBlank { null } ?: return null
        var postfix = groups.groupValues[3].ifBlank { null }
        if (postfix == "00") postfix = null
        return listOfNotNull(prefix, number, postfix)
            .filter { it.isNotBlank() }
            .joinToString("-")
            .ifBlank { null }
    }

    fun crcHex(bytes: ByteArray): String? {
        if (bytes.size < 4) return null
        return bytes.take(4).joinToString("") { "%02X".format(it) }
    }

    fun romHash(romBase: String?): Long? {
        if (romBase.isNullOrBlank()) return null
        var hash = 0xcbf29ce484222325uL
        val prime = 0x100000001b3uL
        for (byte in romBase.encodeToByteArray()) {
            hash = hash xor byte.toUByte().toULong()
            hash *= prime
        }
        return hash.toLong()
    }

    fun romBase(romName: String?): String? {
        if (romName.isNullOrBlank()) return null
        val trimmed = romName.trim()
        val withoutExtension = trimmed.substringBeforeLast('.', trimmed)
        return withoutExtension.lowercase(Locale.US).ifBlank { null }
    }

    fun arcadeSetName(romName: String?): String? {
        if (romName.isNullOrBlank()) return null
        return romName.substringBeforeLast('.').lowercase(Locale.US).ifBlank { null }
    }

    private val ARTICLE_SORTED =
        Regex("""^(.*),\s*(The|A|An)(\b.*)$""", RegexOption.IGNORE_CASE)

    fun humanize(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val match = ARTICLE_SORTED.matchEntire(name.trim()) ?: return name
        val body = match.groupValues[1].trim()
        val article = match.groupValues[2]
        val rest = match.groupValues[3]
        if (body.isEmpty()) return name
        return "$article $body$rest".replace(Regex("""\s{2,}"""), " ").trim()
    }

    fun normalizeTitle(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val cleaned =
            value
                .lowercase(Locale.US)
                .replace('_', ' ')
                .replace(",", "")
                .replace(Regex("""\s+"""), " ")
                .trim()
        return cleaned.takeIf { it.isNotBlank() }
    }
}
