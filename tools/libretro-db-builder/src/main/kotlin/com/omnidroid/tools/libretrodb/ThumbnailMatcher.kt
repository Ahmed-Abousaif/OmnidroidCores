package com.omnidroid.tools.libretrodb

internal class ThumbnailLibrary(
    boxarts: List<String>,
    titles: List<String>,
    snaps: List<String>,
) {
    private val categories =
        listOf(
            index("Named_Boxarts", boxarts),
            index("Named_Titles", titles),
            index("Named_Snaps", snaps),
        )

    fun pathFor(databaseName: String): String? {
        val wanted = ThumbnailMatcher.matchKey(databaseName)
        for (category in categories) {
            category.exact[databaseName]?.let { return "${category.folder}/$it.png" }
            val candidates = category.byKey[wanted] ?: continue
            val best = ThumbnailMatcher.choose(databaseName, candidates) ?: continue
            return "${category.folder}/$best.png"
        }
        return null
    }

    private fun index(
        folder: String,
        stems: List<String>,
    ): CategoryIndex {
        val exact = HashMap<String, String>(stems.size)
        val byKey = HashMap<String, MutableList<String>>()
        stems.forEach { stem ->
            exact.putIfAbsent(stem, stem)
            byKey.getOrPut(ThumbnailMatcher.matchKey(stem)) { ArrayList() }.add(stem)
        }
        return CategoryIndex(folder, exact, byKey)
    }

    private class CategoryIndex(
        val folder: String,
        val exact: Map<String, String>,
        val byKey: Map<String, List<String>>,
    )
}

internal object ThumbnailMatcher {
    private val PARENS = Regex("""\([^)]*\)|\[[^\]]*\]""")
    private val NON_ALNUM = Regex("""[^a-z0-9]+""")
    private val TAGS = Regex("""\(([^)]*)\)""")
    private val ARTICLES = setOf("the", "a", "an")
    private val SKIP_TAGS = setOf("demo", "beta", "proto", "dlc")

    fun assign(
        rows: List<GameRow>,
        libraries: Map<String, ThumbnailLibrary>,
    ): List<GameRow> {
        return rows.map { row ->
            val library = libraries[row.system] ?: return@map row
            val name = row.rawName ?: row.name ?: return@map row
            val path = library.pathFor(name) ?: return@map row
            row.copy(thumbnailPath = path)
        }
    }

    fun matchKey(title: String): String {
        val words =
            PARENS
                .replace(title, " ")
                .lowercase()
                .replace(NON_ALNUM, " ")
                .trim()
                .split(' ')
                .filter { it.isNotBlank() }
                .toMutableList()
        if (words.size > 1 && words.last() in ARTICLES) {
            words.add(0, words.removeAt(words.lastIndex))
        }
        if (words.firstOrNull() in ARTICLES) {
            words.removeAt(0)
        }
        return words.joinToString(" ")
    }

    fun choose(
        databaseName: String,
        stems: List<String>,
    ): String? {
        if (stems.isEmpty()) return null
        val wanted = tags(databaseName)
        var best: String? = null
        var bestScore: Score? = null
        for (stem in stems) {
            val have = tags(stem)
            if ((have intersect SKIP_TAGS).minus(wanted).isNotEmpty()) continue
            val score = Score(wanted.intersect(have).size, -(have.minus(wanted).size), -stem.length)
            if (bestScore == null || score > bestScore) {
                best = stem
                bestScore = score
            }
        }
        return best
    }

    private fun tags(title: String): Set<String> {
        val found = HashSet<String>()
        TAGS.findAll(title).forEach { match ->
            match.groupValues[1].split(',', '+', '/').forEach { part ->
                val token = part.trim().lowercase()
                if (token.isNotEmpty()) found.add(token)
            }
        }
        return found
    }

    private data class Score(
        val overlap: Int,
        val extraPenalty: Int,
        val lengthPenalty: Int,
    ) : Comparable<Score> {
        override fun compareTo(other: Score): Int {
            if (overlap != other.overlap) return overlap.compareTo(other.overlap)
            if (extraPenalty != other.extraPenalty) return extraPenalty.compareTo(other.extraPenalty)
            return lengthPenalty.compareTo(other.lengthPenalty)
        }
    }
}
