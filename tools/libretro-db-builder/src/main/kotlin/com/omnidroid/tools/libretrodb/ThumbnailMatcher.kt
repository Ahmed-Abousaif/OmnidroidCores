package com.omnidroid.tools.libretrodb

import kotlin.math.max
import kotlin.math.min

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
        pathForKeys(databaseName)?.let { return it }
        // DLC packs / updates often only have retail boxart under the base title.
        val base = ThumbnailMatcher.baseTitleForThumbnail(databaseName)
        if (base != null && base != databaseName) {
            pathForKeys(base)?.let { return it }
        }
        return pathForFuzzy(databaseName)
    }

    private fun pathForKeys(databaseName: String): String? {
        val ordered = ThumbnailMatcher.matchKey(databaseName)
        val sorted = ThumbnailMatcher.sortedMatchKey(databaseName)
        for (category in categories) {
            category.exact[databaseName]?.let { return "${category.folder}/$it.png" }
            val candidates =
                category.byKey[ordered]
                    ?: category.bySortedKey[sorted]
                    ?: continue
            val best = ThumbnailMatcher.choose(databaseName, candidates) ?: continue
            return "${category.folder}/$best.png"
        }
        return null
    }

    private fun pathForFuzzy(databaseName: String): String? {
        val queryVariants = ThumbnailMatcher.fuzzyQueryVariants(databaseName)
        if (queryVariants.isEmpty()) return null
        // Pass 2: same-region loose fuzzy over every stem.
        for (category in categories) {
            val best =
                ThumbnailMatcher.chooseFuzzy(
                    databaseName = databaseName,
                    queryVariants = queryVariants,
                    stems = category.stems,
                    requireSameRegion = true,
                ) ?: continue
            return "${category.folder}/$best.png"
        }
        // Pass 3: same fuzzy, region optional (prefer same-region when scoring).
        for (category in categories) {
            val best =
                ThumbnailMatcher.chooseFuzzy(
                    databaseName = databaseName,
                    queryVariants = queryVariants,
                    stems = category.stems,
                    requireSameRegion = false,
                ) ?: continue
            return "${category.folder}/$best.png"
        }
        // Last resort: unique same-region franchise first-token (e.g. MapleStory KR).
        for (category in categories) {
            val best =
                ThumbnailMatcher.chooseFuzzyFranchise(
                    databaseName = databaseName,
                    stems = category.stems,
                ) ?: continue
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
        val bySortedKey = HashMap<String, MutableList<String>>()
        stems.forEach { stem ->
            exact.putIfAbsent(stem, stem)
            byKey.getOrPut(ThumbnailMatcher.matchKey(stem)) { ArrayList() }.add(stem)
            bySortedKey.getOrPut(ThumbnailMatcher.sortedMatchKey(stem)) { ArrayList() }.add(stem)
        }
        return CategoryIndex(folder, exact, byKey, bySortedKey, stems)
    }

    private class CategoryIndex(
        val folder: String,
        val exact: Map<String, String>,
        val byKey: Map<String, List<String>>,
        val bySortedKey: Map<String, List<String>>,
        val stems: List<String>,
    )
}

internal object ThumbnailMatcher {
    private val PARENS = Regex("""\([^)]*\)|\[[^\]]*\]""")
    private val NON_ALNUM = Regex("""[^a-z0-9]+""")
    private val TAGS = Regex("""\(([^)]*)\)""")
    private val ARTICLES = setOf("the", "a", "an")
    private val SKIP_TAGS = setOf("demo", "beta", "proto", "dlc")
    private val PACK_OR_UPDATE_TAGS = setOf("dlc", "update", "embedded update")
    private val PACK_TITLE_SUFFIX = Regex("""\s+-\s+Pack\s+\d+\s*$""", RegexOption.IGNORE_CASE)
    private val TREASURE_TROVE_SUFFIX = Regex("""\s+-\s+Treasure Trove\s*$""", RegexOption.IGNORE_CASE)
    private val REGION_TAGS =
        setOf(
            "usa",
            "europe",
            "japan",
            "korea",
            "world",
            "australia",
            "asia",
            "france",
            "germany",
            "spain",
            "italy",
            "netherlands",
            "taiwan",
            "china",
        )

    // Pass 2 (same-region) thresholds.
    private const val MIN_SOFT_MATCHED = 2
    private const val MIN_QUERY_COVERAGE = 0.55
    private const val MIN_STEM_COVERAGE = 0.45
    private const val MIN_ORDERED_PREFIX = 3

    // Pass 3 (cross-region allowed) is stricter so earlier hits stay stable.
    private const val CROSS_MIN_SOFT_MATCHED = 3
    private const val CROSS_MIN_QUERY_COVERAGE = 0.65
    private const val CROSS_MIN_STEM_COVERAGE = 0.5
    // Must be unique in-region; "zelda" would otherwise steal Majora's Mask art.
    private const val FRANCHISE_MAX_CANDIDATES = 1

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

    fun matchKey(title: String): String = tokens(title).joinToString(" ")

    fun sortedMatchKey(title: String): String = tokens(title).sorted().joinToString(" ")

    internal fun tokens(title: String): List<String> {
        // Humanize article-sorted stems first so
        // "Legend of Zelda, The - The Wind Waker HD (...)" and
        // "The Legend of Zelda: The Wind Waker HD (USA)" share a key.
        // Without this, the middle "the" from ", The -" stays and boxarts miss.
        val humanized = Keys.humanize(title) ?: title
        // "&" must become "and" before non-alnum wipe, else "A & B" → "a b" misses "a and b".
        val normalized = humanized.replace("&", " and ")
        return PARENS
            .replace(normalized, " ")
            .lowercase()
            .replace(NON_ALNUM, " ")
            .trim()
            .split(' ')
            .filter { it.isNotBlank() && it !in ARTICLES && it != "s" }
    }

    fun baseTitleForThumbnail(databaseName: String): String? {
        val tags = tags(databaseName)
        val bare = PARENS.replace(databaseName, " ").replace(Regex("""\s+"""), " ").trim()
        val strippedPack = PACK_TITLE_SUFFIX.replace(bare, "").trim()
        if (strippedPack != bare && strippedPack.isNotEmpty()) {
            return restoreRegion(strippedPack, databaseName)
        }
        if (tags.any { it in PACK_OR_UPDATE_TAGS }) {
            val withoutTrove = TREASURE_TROVE_SUFFIX.replace(bare, "").trim()
            if (withoutTrove != bare && withoutTrove.isNotEmpty()) {
                return restoreRegion(withoutTrove, databaseName)
            }
            // "Title - Pack 1" already handled; also "Title - Something (DLC)" → Title
            val dash = bare.lastIndexOf(" - ")
            if (dash > 0) {
                val head = bare.substring(0, dash).trim()
                if (head.isNotEmpty()) return restoreRegion(head, databaseName)
            }
        }
        return null
    }

    fun fuzzyQueryVariants(databaseName: String): List<List<String>> {
        val full = tokens(databaseName)
        if (full.isEmpty()) return emptyList()
        val variants = LinkedHashSet<List<String>>()
        variants += full
        if (full.size >= 4) variants += full.drop(1)
        // Subtitle after the first " - " often carries the English title on localized rows.
        val bare = PARENS.replace(databaseName, " ").replace(Regex("""\s+"""), " ").trim()
        val dash = bare.indexOf(" - ")
        if (dash > 0) {
            val tail = tokens(bare.substring(dash + 3))
            if (tail.size >= 2) variants += tail
            val head = tokens(bare.substring(0, dash))
            if (head.size >= 2) variants += head
        }
        return variants.toList()
    }

    fun chooseFuzzy(
        databaseName: String,
        queryVariants: List<List<String>>,
        stems: List<String>,
        requireSameRegion: Boolean = true,
    ): String? {
        if (stems.isEmpty() || queryVariants.isEmpty()) return null
        val wanted = tags(databaseName)
        val wantedRegions = wanted.intersect(REGION_TAGS)
        var best: String? = null
        var bestScore: FuzzyScore? = null
        for (stem in stems) {
            val have = tags(stem)
            if ((have intersect SKIP_TAGS).minus(wanted).isNotEmpty()) continue
            val haveRegions = have.intersect(REGION_TAGS)
            val sameRegion = wantedRegions.isEmpty() || wantedRegions.intersect(haveRegions).isNotEmpty()
            if (requireSameRegion && wantedRegions.isNotEmpty() && !sameRegion) continue
            val stemTokens = tokens(stem)
            if (stemTokens.isEmpty()) continue
            for (query in queryVariants) {
                val soft = softOverlap(query, stemTokens)
                val queryCoverage = if (query.isEmpty()) 0.0 else soft.toDouble() / query.size
                val stemCoverage = soft.toDouble() / stemTokens.size
                val orderedPrefix = orderedPrefixLength(query, stemTokens)
                val strongPrefix = orderedPrefix >= MIN_ORDERED_PREFIX
                if (sameRegion || requireSameRegion) {
                    if (query.size < MIN_SOFT_MATCHED) continue
                    if (soft < MIN_SOFT_MATCHED) continue
                    if (queryCoverage < MIN_QUERY_COVERAGE && !strongPrefix) continue
                    if (stemCoverage < MIN_STEM_COVERAGE && !strongPrefix) continue
                } else {
                    // Cross-region: near-title only — block short franchise fragments
                    // like Wind Waker (Japan) → "The Legend of Zelda (USA)".
                    if (query.size < 3 || stemTokens.size < 3) continue
                    if (soft < MIN_SOFT_MATCHED) continue
                    if (soft < CROSS_MIN_SOFT_MATCHED &&
                        (queryCoverage < CROSS_MIN_QUERY_COVERAGE || stemCoverage < CROSS_MIN_STEM_COVERAGE)
                    ) {
                        continue
                    }
                    if (queryCoverage < CROSS_MIN_QUERY_COVERAGE && !strongPrefix) continue
                    if (stemCoverage < CROSS_MIN_STEM_COVERAGE && !strongPrefix) continue
                    if (stemTokens.size < query.size - 1) continue
                }
                // Tiny queries like "Super Mario" must not absorb longer retail titles.
                if (query.size <= 2 && stemTokens.size > query.size + 1) continue
                val score =
                    FuzzyScore(
                        sameRegion = if (sameRegion) 1 else 0,
                        softMatched = soft,
                        orderedPrefix = orderedPrefix,
                        queryCoverageMicros = (queryCoverage * 1_000_000).toInt(),
                        stemCoverageMicros = (stemCoverage * 1_000_000).toInt(),
                        extraPenalty = -(stemTokens.size - soft),
                        lengthPenalty = -stem.length,
                    )
                if (bestScore == null || score > bestScore) {
                    best = stem
                    bestScore = score
                }
            }
        }
        return best
    }

    /**
     * When only the franchise token matches (localized subtitle), accept a same-region
     * stem if few titles share that first token — e.g. MapleStory KR → Girl's Fate KR.
     */
    fun chooseFuzzyFranchise(
        databaseName: String,
        stems: List<String>,
    ): String? {
        val query = tokens(databaseName)
        if (query.isEmpty()) return null
        val franchise = query.first()
        if (franchise.length < 5) return null
        val wanted = tags(databaseName)
        val wantedRegions = wanted.intersect(REGION_TAGS)
        if (wantedRegions.isEmpty()) return null
        val candidates =
            stems.mapNotNull { stem ->
                val have = tags(stem)
                if ((have intersect SKIP_TAGS).minus(wanted).isNotEmpty()) return@mapNotNull null
                val haveRegions = have.intersect(REGION_TAGS)
                if (wantedRegions.intersect(haveRegions).isEmpty()) return@mapNotNull null
                val stemTokens = tokens(stem)
                if (stemTokens.isEmpty() || stemTokens.first() != franchise) return@mapNotNull null
                stem to stemTokens
            }
        if (candidates.isEmpty() || candidates.size > FRANCHISE_MAX_CANDIDATES) return null
        return candidates.minWith(
            compareBy<Pair<String, List<String>>> { it.second.size }
                .thenBy { it.first.length },
        ).first
    }

    fun softTokenMatch(
        a: String,
        b: String,
    ): Boolean {
        if (a == b) return true
        val short = min(a.length, b.length)
        val long = max(a.length, b.length)
        if (short >= 4 && long <= short + 3 && (a.startsWith(b) || b.startsWith(a))) return true
        if (a.length >= 5 && b.length >= 5 && levenshtein(a, b) <= 1) return true
        return false
    }

    private fun softOverlap(
        query: List<String>,
        stem: List<String>,
    ): Int {
        val used = BooleanArray(stem.size)
        var matched = 0
        for (q in query) {
            val index = stem.indices.firstOrNull { !used[it] && softTokenMatch(q, stem[it]) } ?: continue
            used[index] = true
            matched++
        }
        return matched
    }

    private fun orderedPrefixLength(
        query: List<String>,
        stem: List<String>,
    ): Int {
        val n = min(query.size, stem.size)
        var i = 0
        while (i < n && softTokenMatch(query[i], stem[i])) i++
        return i
    }

    private fun levenshtein(
        a: String,
        b: String,
    ): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > 1) return 2
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] =
                    min(
                        prev[j] + 1,
                        min(cur[j - 1] + 1, prev[j - 1] + cost),
                    )
                rowMin = min(rowMin, cur[j])
            }
            if (rowMin > 1) return 2
            for (j in prev.indices) prev[j] = cur[j]
        }
        return prev[b.length]
    }

    private fun restoreRegion(
        bareTitle: String,
        original: String,
    ): String {
        val region =
            TAGS.findAll(original)
                .map { it.value }
                .firstOrNull { tag ->
                    val inner = tag.removePrefix("(").removeSuffix(")").lowercase()
                    inner.split(',', '+', '/').any { part -> part.trim() in REGION_TAGS }
                }
        return if (region != null) "$bareTitle $region" else bareTitle
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
            val overlap = wanted.intersect(have).size
            // Don't attach a USA boxart to a Japan/Europe DAT row just because
            // the core title key matched after stripping region tags.
            if (wanted.isNotEmpty() && overlap == 0) continue
            val score = Score(overlap, -(have.minus(wanted).size), -stem.length)
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

    private data class FuzzyScore(
        val sameRegion: Int,
        val softMatched: Int,
        val orderedPrefix: Int,
        val queryCoverageMicros: Int,
        val stemCoverageMicros: Int,
        val extraPenalty: Int,
        val lengthPenalty: Int,
    ) : Comparable<FuzzyScore> {
        override fun compareTo(other: FuzzyScore): Int {
            if (sameRegion != other.sameRegion) return sameRegion.compareTo(other.sameRegion)
            if (orderedPrefix != other.orderedPrefix) return orderedPrefix.compareTo(other.orderedPrefix)
            if (softMatched != other.softMatched) return softMatched.compareTo(other.softMatched)
            if (queryCoverageMicros != other.queryCoverageMicros) {
                return queryCoverageMicros.compareTo(other.queryCoverageMicros)
            }
            if (stemCoverageMicros != other.stemCoverageMicros) {
                return stemCoverageMicros.compareTo(other.stemCoverageMicros)
            }
            if (extraPenalty != other.extraPenalty) return extraPenalty.compareTo(other.extraPenalty)
            return lengthPenalty.compareTo(other.lengthPenalty)
        }
    }
}
