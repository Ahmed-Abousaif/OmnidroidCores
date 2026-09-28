package com.omnidroid.tools.libretrodb

import java.io.File
import java.net.URI
import java.sql.DriverManager

private const val RDB_BASE = "https://raw.githubusercontent.com/libretro/libretro-database/master/rdb/"
private const val FBNEO_DAT =
    "https://raw.githubusercontent.com/libretro/FBNeo/master/dats/" +
        "FinalBurn%20Neo%20(ClrMame%20Pro%20XML,%20Arcade%20only).dat"
private const val MAME_DAT =
    "https://raw.githubusercontent.com/libretro/mame2003-plus-libretro/master/metadata/mame2003-plus.xml"
private val SYSTEMS =
    listOf(
        "Nintendo - Nintendo Entertainment System.rdb" to "nes",
        "Nintendo - Super Nintendo Entertainment System.rdb" to "snes",
        "Sega - Mega Drive - Genesis.rdb" to "md",
        "Nintendo - Game Boy.rdb" to "gb",
        "Nintendo - Game Boy Color.rdb" to "gbc",
        "Nintendo - Game Boy Advance.rdb" to "gba",
        "Nintendo - Nintendo 64.rdb" to "n64",
        "Sega - Master System - Mark III.rdb" to "sms",
        "Sony - PlayStation Portable.rdb" to "psp",
        "Nintendo - Nintendo DS.rdb" to "nds",
        "Sega - Game Gear.rdb" to "gg",
        "Atari - 2600.rdb" to "atari2600",
        "Sony - PlayStation.rdb" to "psx",
        "FBNeo - Arcade Games.rdb" to "fbneo",
        "MAME 2003-Plus.rdb" to "mame2003plus",
        "NEC - PC Engine - TurboGrafx 16.rdb" to "pce",
        "Atari - Lynx.rdb" to "lynx",
        "Atari - 7800.rdb" to "atari7800",
        "Sega - Mega-CD - Sega CD.rdb" to "scd",
        "SNK - Neo Geo Pocket.rdb" to "ngp",
        "SNK - Neo Geo Pocket Color.rdb" to "ngc",
        "Bandai - WonderSwan.rdb" to "ws",
        "Bandai - WonderSwan Color.rdb" to "wsc",
        "DOS.rdb" to "dos",
        "Nintendo - Nintendo 3DS.rdb" to "3ds",
        "Sony - PlayStation 2.rdb" to "ps2",
        "Nintendo - GameCube.rdb" to "gamecube",
        "Nintendo - Wii.rdb" to "wii",
        "Sega - Dreamcast.rdb" to "dreamcast",
    )

fun main(args: Array<String>) {
    val coresRoot = File(argument(args, "--cores-root"))
    val appRoot = File(argument(args, "--app-root"))
    val cache = File(argument(args, "--cache"))
    cache.mkdirs()

    val rows = ArrayList<GameRow>()
    SYSTEMS.forEach { (fileName, system) ->
        val file = download(cache, fileName, RDB_BASE + encodePath(fileName))
        if (rows.isEmpty()) {
            println("First rdb keys: ${RdbGames.firstKeys(file)}")
        }
        val parsed = RdbGames.read(file, system)
        println("$system ${parsed.size}")
        rows += parsed
    }

    val fbneoSets = setNames(rows, "fbneo")
    val mameSets = setNames(rows, "mame2003plus")
    val overlap = fbneoSets.intersect(mameSets)
    println("Arcade set overlap: ${overlap.size}")

    val fbneoDat = download(cache, "fbneo-arcade.dat", FBNEO_DAT)
    val mameDat = download(cache, "mame2003-plus.xml", MAME_DAT)
    val members =
        ArcadeDats.members(fbneoDat, "fbneo", overlap) +
            ArcadeDats.members(mameDat, "mame2003plus", overlap)
    println("Arcade member rows: ${members.size}")

    Class.forName("org.sqlite.JDBC")
    val built = buildSlices(cache, rows)
    placeSlices(coresRoot, built)
    writeDetection(appRoot, rows, members)
    println("Wrote ${built.size} slices")
}

private fun setNames(
    rows: List<GameRow>,
    system: String,
): Set<String> {
    return rows
        .asSequence()
        .filter { it.system == system }
        .mapNotNull { Keys.arcadeSetName(it.romName) }
        .toSet()
}

private fun buildSlices(
    cache: File,
    rows: List<GameRow>,
): List<SliceFile> {
    val scratch = File(cache, "slices")
    scratch.mkdirs()
    return SliceLayout.slices.map { spec ->
        val selected = rows.filter { it.system in spec.systems }
        val sqlite = File(scratch, "${spec.id}.sqlite")
        SliceWriter.writeGames(sqlite, selected)
        val gzip = SliceWriter.gzip(sqlite)
        sqlite.delete()
        println("${spec.id} ${selected.size} rows, ${gzip.size} bytes")
        SliceFile(spec, gzip, SliceWriter.sha256(gzip), selected.size)
    }
}

private fun placeSlices(
    coresRoot: File,
    slices: List<SliceFile>,
) {
    val byCore = mutableMapOf<String, MutableList<SliceFile>>()
    slices.forEach { slice ->
        slice.spec.cores.forEach { core ->
            byCore.getOrPut(core) { mutableListOf() }.add(slice)
        }
    }
    byCore.forEach { (core, coreSlices) ->
        SliceWriter.place(coresRoot, core, coreSlices.sortedBy { it.spec.id })
    }
    val bundled = File(coresRoot, "bundled-cores/src/main/assets/libretro-db/bundled")
    bundled.mkdirs()
    val distinct = slices.sortedBy { it.spec.id }
    distinct.forEach { File(bundled, "${it.spec.id}.sqlite.gz").writeBytes(it.gzip) }
    File(bundled, "manifest.json").writeText(SliceWriter.manifest(distinct))
}

private fun writeDetection(
    appRoot: File,
    rows: List<GameRow>,
    members: List<ArcadeMember>,
) {
    val output =
        File(
            appRoot,
            "omnidroid-metadata-libretro-db/src/main/assets/libretro-detect.sqlite",
        )
    if (output.exists()) output.delete()
    output.parentFile?.mkdirs()
    DriverManager.getConnection("jdbc:sqlite:${output.absolutePath}").use { connection ->
        connection.autoCommit = false
        val statement = connection.createStatement()
        statement.execute("PRAGMA user_version = 1")
        statement.execute(
            """
            CREATE TABLE arcade_sets (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                setHash INTEGER NOT NULL,
                systems TEXT NOT NULL
            )
            """.trimIndent(),
        )
        statement.execute(
            """
            CREATE TABLE arcade_markers (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                setHash INTEGER NOT NULL,
                system TEXT NOT NULL,
                crc TEXT NOT NULL
            )
            """.trimIndent(),
        )
        statement.execute(
            """
            CREATE TABLE bin_carts (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                crc TEXT NOT NULL,
                system TEXT NOT NULL
            )
            """.trimIndent(),
        )
        statement.execute("CREATE UNIQUE INDEX index_arcade_sets_setHash ON arcade_sets (setHash)")
        statement.execute("CREATE INDEX index_arcade_markers_setHash ON arcade_markers (setHash)")
        statement.execute("CREATE INDEX index_bin_carts_crc ON bin_carts (crc)")

        val fbneo = setNames(rows, "fbneo")
        val mame = setNames(rows, "mame2003plus")
        val setInsert = connection.prepareStatement("INSERT INTO arcade_sets (setHash, systems) VALUES (?, ?)")
        (fbneo + mame).distinct().forEach { name ->
            val hash = Keys.romHash(name) ?: return@forEach
            val systems =
                listOfNotNull(
                    "fbneo".takeIf { name in fbneo },
                    "mame2003plus".takeIf { name in mame },
                ).joinToString(",")
            setInsert.setLong(1, hash)
            setInsert.setString(2, systems)
            setInsert.addBatch()
        }
        setInsert.executeBatch()

        val markerInsert =
            connection.prepareStatement("INSERT INTO arcade_markers (setHash, system, crc) VALUES (?, ?, ?)")
        val overlap = fbneo.intersect(mame)
        val grouped = members.groupBy { it.romName }
        overlap.forEach { name ->
            val hash = Keys.romHash(name) ?: return@forEach
            val fbneoCrcs = grouped[name].orEmpty().filter { it.system == "fbneo" }.map { it.crc }.toSet()
            val mameCrcs = grouped[name].orEmpty().filter { it.system == "mame2003plus" }.map { it.crc }.toSet()
            (fbneoCrcs - mameCrcs).take(3).forEach { crc ->
                markerInsert.setLong(1, hash)
                markerInsert.setString(2, "fbneo")
                markerInsert.setString(3, crc)
                markerInsert.addBatch()
            }
            (mameCrcs - fbneoCrcs).take(3).forEach { crc ->
                markerInsert.setLong(1, hash)
                markerInsert.setString(2, "mame2003plus")
                markerInsert.setString(3, crc)
                markerInsert.addBatch()
            }
        }
        markerInsert.executeBatch()

        val cartInsert = connection.prepareStatement("INSERT INTO bin_carts (crc, system) VALUES (?, ?)")
        rows.filter { it.system == "atari7800" || it.system == "pce" }.forEach { row ->
            val crc = row.crc32 ?: return@forEach
            cartInsert.setString(1, crc)
            cartInsert.setString(2, row.system)
            cartInsert.addBatch()
        }
        cartInsert.executeBatch()
        connection.commit()
        connection.autoCommit = true
        connection.createStatement().execute("VACUUM")
    }
    println("Detection index ${output.length()} bytes")
}

private fun download(
    cache: File,
    name: String,
    url: String,
): File {
    val destination = File(cache, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    if (destination.exists() && destination.length() > 0L) {
        println("cached $name")
        return destination
    }
    println("downloading $url")
    URI(url).toURL().openStream().use { input ->
        destination.outputStream().use { output -> input.copyTo(output) }
    }
    return destination
}

private fun encodePath(name: String): String {
    return name.split("/").joinToString("/") { part ->
        java.net.URLEncoder.encode(part, "UTF-8").replace("+", "%20")
    }
}

private fun argument(
    args: Array<String>,
    name: String,
): String {
    val index = args.indexOf(name)
    require(index >= 0 && index + 1 < args.size) { "Missing $name" }
    return args[index + 1]
}
