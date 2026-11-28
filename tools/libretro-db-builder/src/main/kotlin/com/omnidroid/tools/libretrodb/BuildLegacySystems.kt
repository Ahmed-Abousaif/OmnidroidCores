package com.omnidroid.tools.libretrodb

import com.google.gson.JsonParser
import java.io.File
import java.net.URI
import java.sql.DriverManager

/** Incrementally installs the additional platform catalogs without replacing existing slices or seeds. */
object BuildLegacySystems {
    private val databases =
        linkedMapOf(
            "atari5200" to "Atari - 5200",
            "atari800" to "Atari - 8-bit Family",
            "saturn" to "Sega - Saturn",
            "3do" to "The 3DO Company - 3DO",
            "colecovision" to "Coleco - ColecoVision",
            "c64" to "Commodore - 64",
            "msx" to "Microsoft - MSX",
            "msx2" to "Microsoft - MSX2",
            "apple2" to "Apple - II",
            "jaguar" to "Atari - Jaguar",
        )

    @JvmStatic
    fun main(args: Array<String>) {
        val cores = File(args[0])
        val app = File(args[1])
        val cache = File(args[2]).apply { mkdirs() }
        Class.forName("org.sqlite.JDBC")
        val slices =
            databases.map { (system, databaseName) ->
                val file = File(cache, "$system.rdb")
                if (!file.isFile) {
                    val encoded =
                        java.net.URLEncoder
                            .encode("$databaseName.rdb", "UTF-8")
                            .replace("+", "%20")
                    URI(
                        "https://raw.githubusercontent.com/libretro/libretro-database/master/rdb/$encoded",
                    ).toURL().openStream().use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                }
                val rows = RdbGames.read(file, system)
                check(rows.isNotEmpty()) { "$databaseName catalog is empty" }
                val thumbnails =
                    runCatching { ThumbnailIndex.load(cache, listOf(system)) }.getOrElse {
                        println(
                            "$system thumbnail index unavailable: ${it.message}; retaining catalog without precomputed art paths",
                        )
                        emptyMap()
                    }
                val covered = ThumbnailMatcher.assign(rows, thumbnails)
                val sqlite = File(cache, "$system.sqlite")
                SliceWriter.writeGames(sqlite, covered)
                val gzip = SliceWriter.gzip(sqlite)
                val slice =
                    SliceFile(
                        SliceLayout.slices.single {
                            it.id == system
                        },
                        gzip,
                        SliceWriter.sha256(gzip),
                        covered.size,
                        "$system.sqlite.gzip",
                    )
                println("$system: ${covered.size} games")
                slice to rows
            }
        val newSlices = slices.map { it.first }
        newSlices.flatMap { it.spec.cores }.distinct().forEach { core ->
            val owned = newSlices.filter { core in it.spec.cores }
            SliceWriter.place(cores, core, owned)
            SliceWriter.place(cores, core, owned, bundled = true)
        }
        // A base asset source also supports on-demand flavors before the next core repository release.
        val legacy = File(app, "omnidroid-metadata-libretro-db/src/main/assets/libretro-db/legacy").apply { mkdirs() }
        newSlices.forEach { File(legacy, it.fileName).writeBytes(it.gzip) }
        File(legacy, "manifest.json").writeText(SliceWriter.manifest(newSlices))

        val bundled = File(cores, "bundled-cores/src/main/assets/libretro-db/bundled")
        val existing = readSlices(bundled).filterNot { it.spec.id in databases }
        newSlices.forEach { File(bundled, it.fileName).writeBytes(it.gzip) }
        File(bundled, "manifest.json").writeText(SliceWriter.manifest((existing + newSlices).sortedBy { it.spec.id }))
        val detection = File(app, "omnidroid-metadata-libretro-db/src/main/assets/libretro-detect.sqlite")
        DriverManager.getConnection("jdbc:sqlite:${detection.absolutePath}").use { connection ->
            connection.autoCommit = false
            databases.keys.forEach { system ->
                connection.prepareStatement("DELETE FROM bin_carts WHERE system = ?").use {
                    it.setString(1, system)
                    it.executeUpdate()
                }
            }
            connection.prepareStatement("INSERT INTO bin_carts (crc, system) VALUES (?, ?)").use { insert ->
                slices
                    .flatMap { it.second }
                    .mapNotNull { row ->
                        row.crc32?.let { it to row.system }
                    }.distinct()
                    .forEach { (crc, system) ->
                        insert.setString(1, crc)
                        insert.setString(2, system)
                        insert.addBatch()
                    }
                insert.executeBatch()
            }
            connection.commit()
        }
    }

    private fun readSlices(directory: File): List<SliceFile> {
        val manifest = JsonParser.parseString(File(directory, "manifest.json").readText()).asJsonObject
        return manifest.getAsJsonArray("slices").map { value ->
            val entry = value.asJsonObject
            val id = entry["sliceId"].asString
            val fileName = entry["file"].asString
            val bytes = File(directory, fileName).readBytes()
            check(SliceWriter.sha256(bytes) == entry["sha256"].asString)
            SliceFile(
                SliceSpec(
                    id,
                    entry.getAsJsonArray("systems").map {
                        it.asString
                    },
                    emptyList(),
                ),
                bytes,
                SliceWriter.sha256(bytes),
                entry["rows"].asInt,
                fileName,
            )
        }
    }
}
