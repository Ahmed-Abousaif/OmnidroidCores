package com.omnidroid.tools.libretrodb

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

internal object ThumbnailIndex {
    private val PATH = Regex(""""path"\s*:\s*"((?:\\.|[^"\\])*)"""")
    private val CLIENT = HttpClient.newHttpClient()

    val folders: Map<String, String> =
        mapOf(
            "nes" to "Nintendo - Nintendo Entertainment System",
            "snes" to "Nintendo - Super Nintendo Entertainment System",
            "md" to "Sega - Mega Drive - Genesis",
            "gb" to "Nintendo - Game Boy",
            "gbc" to "Nintendo - Game Boy Color",
            "gba" to "Nintendo - Game Boy Advance",
            "n64" to "Nintendo - Nintendo 64",
            "sms" to "Sega - Master System - Mark III",
            "psp" to "Sony - PlayStation Portable",
            "nds" to "Nintendo - Nintendo DS",
            "gg" to "Sega - Game Gear",
            "atari2600" to "Atari - 2600",
            "psx" to "Sony - PlayStation",
            "fbneo" to "MAME",
            "mame2003plus" to "MAME",
            "pce" to "NEC - PC Engine - TurboGrafx 16",
            "lynx" to "Atari - Lynx",
            "atari7800" to "Atari - 7800",
            "scd" to "Sega - Mega-CD - Sega CD",
            "ngp" to "SNK - Neo Geo Pocket",
            "ngc" to "SNK - Neo Geo Pocket Color",
            "ws" to "Bandai - WonderSwan",
            "wsc" to "Bandai - WonderSwan Color",
            "dos" to "DOS",
            "3ds" to "Nintendo - Nintendo 3DS",
            "ps2" to "Sony - PlayStation 2",
            "gamecube" to "Nintendo - GameCube",
            "wii" to "Nintendo - Wii",
            "dreamcast" to "Sega - Dreamcast",
            "wiiu" to "Nintendo - Wii U",
        )

    fun load(
        cache: File,
        systems: Collection<String>,
    ): Map<String, ThumbnailLibrary> {
        val byFolder = HashMap<String, ThumbnailLibrary>()
        val result = HashMap<String, ThumbnailLibrary>()
        systems.distinct().forEach { system ->
            val folder = folders[system] ?: return@forEach
            val library = byFolder.getOrPut(folder) { download(cache, folder) }
            result[system] = library
        }
        return result
    }

    private val CATEGORIES = listOf("Named_Boxarts", "Named_Titles", "Named_Snaps")

    private fun download(
        cache: File,
        folder: String,
    ): ThumbnailLibrary {
        val repo = folder.replace(" ", "_")
        val destination = File(cache, "thumbs/$repo.json")
        if (!destination.exists() || destination.length() == 0L) {
            destination.parentFile?.mkdirs()
            val body = fetchIndex(repo) ?: return ThumbnailLibrary(emptyList(), emptyList(), emptyList()).also {
                println("no thumbnail repo for $folder")
            }
            destination.writeText(body)
        } else {
            println("cached thumbnails $folder")
        }
        val body = destination.readText()
        check(!Regex(""""truncated"\s*:\s*true""").containsMatchIn(body)) {
            "Thumbnail index for $folder was truncated"
        }
        val boxarts = ArrayList<String>()
        val titles = ArrayList<String>()
        val snaps = ArrayList<String>()
        PATH.findAll(body).forEach { match ->
            val path = unescape(match.groupValues[1])
            val slash = path.indexOf('/')
            if (slash <= 0 || !path.endsWith(".png")) return@forEach
            val stem = path.substring(slash + 1, path.length - 4)
            when (path.substring(0, slash)) {
                "Named_Boxarts" -> boxarts += stem
                "Named_Titles" -> titles += stem
                "Named_Snaps" -> snaps += stem
            }
        }
        println("$folder boxarts ${boxarts.size}")
        return ThumbnailLibrary(boxarts, titles, snaps)
    }

    private fun fetchIndex(repo: String): String? {
        val root = "https://api.github.com/repos/libretro-thumbnails/$repo/git/trees"
        val whole = fetch("$root/master?recursive=1")
        if (whole != null && whole.status == 200 && !truncated(whole.body)) return whole.body
        if (whole?.status == 404) return null
        println("full tree for $repo failed (${whole?.status}); fetching each thumbnail folder")
        val master = fetch("$root/master")
        check(master != null && master.status == 200) { "Thumbnail index for $repo returned HTTP ${master?.status}" }
        val shas = treeShas(master.body)
        val combined = StringBuilder("{\"truncated\":false,\"tree\":[")
        var first = true
        CATEGORIES.forEach { category ->
            val sha = shas[category] ?: return@forEach
            val tree = fetch("$root/$sha?recursive=1")
            check(tree != null && tree.status == 200) { "Thumbnail index for $repo/$category returned HTTP ${tree?.status}" }
            check(!truncated(tree.body)) { "Thumbnail index for $repo/$category was truncated" }
            PATH.findAll(tree.body).forEach { match ->
                val name = unescape(match.groupValues[1])
                if (!name.endsWith(".png") || name.endsWith("/")) return@forEach
                if (!first) combined.append(',')
                first = false
                combined.append("{\"path\":\"").append(category).append('/').append(jsonEscape(name)).append("\"}")
            }
        }
        combined.append("]}")
        return combined.toString()
    }

    private fun treeShas(body: String): Map<String, String> {
        val shas = HashMap<String, String>()
        val entry = Regex(""""path"\s*:\s*"([^"]+)"[^}]*"sha"\s*:\s*"([0-9a-f]+)"""")
        entry.findAll(body).forEach { match ->
            if (match.groupValues[1] in CATEGORIES) shas[match.groupValues[1]] = match.groupValues[2]
        }
        return shas
    }

    private fun truncated(body: String): Boolean = Regex(""""truncated"\s*:\s*true""").containsMatchIn(body)

    private fun fetch(url: String): HttpBody? {
        var pause = 2_000L
        repeat(4) { attempt ->
            println("downloading $url")
            val request =
                HttpRequest.newBuilder(URI(url))
                    .header("User-Agent", "omnidroid-libretro-db")
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build()
            val response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200 || response.statusCode() == 404) {
                return HttpBody(response.statusCode(), response.body())
            }
            if (response.statusCode() !in setOf(429, 500, 502, 503) || attempt == 3) {
                return HttpBody(response.statusCode(), response.body())
            }
            println("HTTP ${response.statusCode()} for $url, retrying")
            Thread.sleep(pause)
            pause *= 2
        }
        return null
    }

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private class HttpBody(
        val status: Int,
        val body: String,
    )

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '\\' || index + 1 >= value.length) {
                out.append(char)
                index++
                continue
            }
            when (value[index + 1]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'n' -> out.append('\n')
                'u' -> {
                    if (index + 5 >= value.length) break
                    out.append(value.substring(index + 2, index + 6).toInt(16).toChar())
                    index += 6
                    continue
                }
                else -> out.append(value[index + 1])
            }
            index += 2
        }
        return out.toString()
    }
}
