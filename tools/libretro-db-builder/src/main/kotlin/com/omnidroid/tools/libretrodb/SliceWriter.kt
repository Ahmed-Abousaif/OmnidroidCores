package com.omnidroid.tools.libretrodb

import java.io.File
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

internal data class SliceFile(
    val spec: SliceSpec,
    val gzip: ByteArray,
    val sha256: String,
    val rows: Int,
)

internal object SliceWriter {
    fun writeGames(
        file: File,
        rows: List<GameRow>,
    ) {
        if (file.exists()) file.delete()
        file.parentFile?.mkdirs()
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.autoCommit = false
            val statement = connection.createStatement()
            statement.execute(
                """
                CREATE TABLE games (
                    name TEXT,
                    system TEXT,
                    crc32 TEXT,
                    serial TEXT,
                    code TEXT,
                    size INTEGER,
                    romHash INTEGER
                )
                """.trimIndent(),
            )
            val insert =
                connection.prepareStatement(
                    "INSERT INTO games (name, system, crc32, serial, code, size, romHash) VALUES (?, ?, ?, ?, ?, ?, ?)",
                )
            rows.sortedWith(compareBy({ it.system }, { it.name }, { it.crc32 }, { it.serial })).forEach { row ->
                insert.setString(1, row.name)
                insert.setString(2, row.system)
                insert.setString(3, row.crc32)
                insert.setString(4, row.serial)
                insert.setString(5, row.code)
                if (row.size == null) insert.setNull(6, java.sql.Types.INTEGER) else insert.setLong(6, row.size)
                val hash = Keys.romHash(row.romBase)
                if (hash == null) insert.setNull(7, java.sql.Types.INTEGER) else insert.setLong(7, hash)
                insert.addBatch()
            }
            insert.executeBatch()
            connection.commit()
            connection.autoCommit = true
            connection.createStatement().execute("VACUUM")
        }
    }

    fun gzip(file: File): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        object : GZIPOutputStream(buffer) {
            init {
                def.setLevel(Deflater.BEST_COMPRESSION)
            }
        }.use { gzip ->
            file.inputStream().use { it.copyTo(gzip) }
        }
        return buffer.toByteArray()
    }

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun manifest(
        slices: List<SliceFile>,
    ): String {
        val body =
            buildString {
                append("{\"schemaVersion\":")
                append(SliceLayout.SCHEMA_VERSION)
                append(",\"slices\":[")
                slices.forEachIndexed { index, slice ->
                    if (index > 0) append(',')
                    append("{\"sliceId\":\"")
                    append(slice.spec.id)
                    append("\",\"systems\":[")
                    slice.spec.systems.forEachIndexed { systemIndex, system ->
                        if (systemIndex > 0) append(',')
                        append('"')
                        append(system)
                        append('"')
                    }
                    append("],\"file\":\"")
                    append(slice.spec.id)
                    append(".sqlite.gz\",\"sha256\":\"")
                    append(slice.sha256)
                    append("\",\"size\":")
                    append(slice.gzip.size)
                    append(",\"rows\":")
                    append(slice.rows)
                    append('}')
                }
                append("]}")
            }
        val sha = sha256(body.toByteArray(Charsets.UTF_8))
        return body.dropLast(1) + ",\"manifestSha\":\"$sha\"}"
    }

    fun place(
        coresRoot: File,
        coreName: String,
        slices: List<SliceFile>,
    ) {
        val dir = File(coresRoot, "${SliceLayout.coreDirectory(coreName)}/src/main/assets/libretro-db/$coreName")
        dir.mkdirs()
        slices.forEach { slice ->
            File(dir, "${slice.spec.id}.sqlite.gz").writeBytes(slice.gzip)
        }
        File(dir, "manifest.json").writeText(manifest(slices))
    }
}
