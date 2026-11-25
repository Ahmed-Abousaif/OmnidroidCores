package com.omnidroid.tools.libretrodb

import com.google.gson.JsonParser
import java.io.File
import java.net.URI
import java.sql.DriverManager

/** Adds the 32X catalog without rebuilding or dropping verified seeds from existing slices. */
object BuildSega32x {
    @JvmStatic
    fun main(args: Array<String>) {
        val cores = File(args[0])
        val app = File(args[1])
        val cache = File(args[2]).apply { mkdirs() }
        val rdb = File(cache, "Sega_-_32X.rdb")
        if (!rdb.isFile) {
            URI("https://raw.githubusercontent.com/libretro/libretro-database/master/rdb/Sega%20-%2032X.rdb")
                .toURL()
                .openStream()
                .use { input -> rdb.outputStream().use { input.copyTo(it) } }
        }
        val rows = RdbGames.read(rdb, "32x")
        check(rows.isNotEmpty()) { "The 32X catalog is empty" }
        val covered = ThumbnailMatcher.assign(rows, ThumbnailIndex.load(cache, listOf("32x")))
        Class.forName("org.sqlite.JDBC")
        val database = File(cache, "32x.sqlite")
        SliceWriter.writeGames(database, covered)
        val gzip = SliceWriter.gzip(database)
        val added =
            SliceFile(SliceLayout.slices.single { it.id == "32x" }, gzip, SliceWriter.sha256(gzip), covered.size)
        val genesis = File(cores, "omnidroid_core_genesis_plus_gx/src/main/assets/libretro-db/genesis_plus_gx")
        val shared = readSlices(genesis).filter { it.spec.id in setOf("md", "scd") }
        check(shared.size == 2)
        SliceWriter.place(cores, "picodrive", (shared + added).sortedBy { it.spec.id })
        SliceWriter.place(cores, "picodrive", (shared + added).sortedBy { it.spec.id }, bundled = true)

        val bundled = File(cores, "bundled-cores/src/main/assets/libretro-db/bundled")
        val combined = (readSlices(bundled).filterNot { it.spec.id == "32x" } + added).sortedBy { it.spec.id }
        File(bundled, added.fileName).writeBytes(gzip)
        File(bundled, "32x.sqlite.gz").delete()
        File(bundled, "manifest.json").writeText(SliceWriter.manifest(combined))

        val detection = File(app, "omnidroid-metadata-libretro-db/src/main/assets/libretro-detect.sqlite")
        DriverManager.getConnection("jdbc:sqlite:${detection.absolutePath}").use { connection ->
            connection.autoCommit = false
            connection.createStatement().execute("DELETE FROM bin_carts WHERE system = '32x'")
            connection.prepareStatement("INSERT INTO bin_carts (crc, system) VALUES (?, '32x')").use { insert ->
                rows.mapNotNull { it.crc32 }.distinct().forEach { crc ->
                    insert.setString(1, crc)
                    insert.addBatch()
                }
                insert.executeBatch()
            }
            connection.commit()
        }
        println("Installed ${added.rows} 32X metadata rows and preserved existing slices")
    }

    private fun readSlices(directory: File): List<SliceFile> {
        val manifest = JsonParser.parseString(File(directory, "manifest.json").readText()).asJsonObject
        return manifest.getAsJsonArray("slices").map { value ->
            val entry = value.asJsonObject
            val id = entry.get("sliceId").asString
            val bytes = File(directory, entry.get("file").asString).readBytes()
            check(SliceWriter.sha256(bytes) == entry.get("sha256").asString) { "Invalid existing slice $id" }
            SliceFile(
                SliceSpec(id, entry.getAsJsonArray("systems").map { it.asString }, emptyList()),
                bytes,
                SliceWriter.sha256(bytes),
                entry.get("rows").asInt,
                entry.get("file").asString,
            )
        }
    }
}
