package mihon.discover.recommendation

import kotlin.math.max
import kotlin.math.min

/** Pure, deterministic scoring. AniList and Mihon models are mapped at the app boundary. */
class RecommendationEngine {
    data class Features(
        val id: Long,
        val genres: Set<String>,
        val tags: Map<String, Int>,
        val meanScore: Int?,
        val voteCount: Int?,
        val startYear: Int?,
        val romanceTags: Map<String, Int> = emptyMap(),
        val title: String? = null,
    )

    data class Seed(val features: Features, val weight: Double)

    enum class Mode { PERSONAL, SIMILAR, EXPLORE }

    enum class RomancePolicy { TOLERATE, PENALIZE, EXCLUDE_MAIN }

    data class Ranked(
        val id: Long,
        val score: Double,
        val affinity: Double,
        val quality: Double,
        val voteCount: Int?,
        val romanceEvidence: Double,
        val reasons: List<String>,
    )

    fun quality(mean: Int?, votes: Int?): Double? {
        if (mean == null || votes == null) return null
        val n = votes.coerceAtLeast(0).toDouble()
        return ((n * mean.coerceIn(0, 100) + PRIOR_VOTES * PRIOR_SCORE) / (n + PRIOR_VOTES)) / 100.0
    }

    fun similarity(a: Features, b: Features): Double {
        val tagA = a.tags.filterValues { it >= MIN_TAG_RANK }.mapValues { it.value / 100.0 }
        val tagB = b.tags.filterValues { it >= MIN_TAG_RANK }.mapValues { it.value / 100.0 }
        return TAG_WEIGHT * jaccard(tagA, tagB) + GENRE_WEIGHT * jaccard(
            a.genres.associateWith { 1.0 },
            b.genres.associateWith { 1.0 },
        )
    }

    fun rank(
        candidates: List<Features>,
        seeds: List<Seed>,
        mode: Mode,
        romancePolicy: RomancePolicy = RomancePolicy.PENALIZE,
        excluded: Set<Long> = emptySet(),
        currentYear: Int = java.time.Year.now().value,
    ): List<Ranked> {
        val known = seeds.mapTo(mutableSetOf()) { it.features.id } + excluded
        val positive = seeds.filter { it.weight > 0 }.sortedByDescending { it.weight }.take(MAX_SEEDS)
        val negative = seeds.filter { it.weight < 0 }.sortedBy { it.weight }.take(MAX_SEEDS)
        val scored = candidates.asSequence().filter { it.id !in known }.distinctBy { it.id }.mapNotNull { item ->
            val romance = romanceEvidence(item)
            if (romancePolicy == RomancePolicy.EXCLUDE_MAIN && romance >= MAIN_ROMANCE) return@mapNotNull null
            val neighbors = positive.map { similarity(item, it.features) * min(1.0, it.weight / 4.0) }
            val affinity = if (neighbors.isEmpty()) {
                0.0
            } else {
                neighbors.sortedDescending().take(3).average()
            }
            val dislike = negative.maxOfOrNull {
                similarity(item, it.features) * min(1.0, -it.weight / 4.0)
            } ?: 0.0
            val quality = quality(item.meanScore, item.voteCount) ?: NEUTRAL_QUALITY
            val freshness =
                item.startYear?.let { (1.0 - (currentYear - it).coerceAtLeast(0) / 20.0).coerceIn(0.0, 1.0) }
                    ?: 0.0
            val novelty = (1.0 - affinity).coerceIn(0.0, 1.0)
            val personalWeight = min(1.0, positive.size / 5.0)
            val base = when (mode) {
                Mode.PERSONAL -> personalWeight * (0.70 * affinity + 0.25 * quality + 0.05 * freshness) +
                    (1.0 - personalWeight) * (0.80 * quality + 0.20 * freshness)
                Mode.SIMILAR -> 0.85 * affinity + 0.15 * quality
                Mode.EXPLORE -> 0.40 * affinity + 0.35 * quality + 0.25 * novelty
            }
            val romancePenalty = when (romancePolicy) {
                RomancePolicy.TOLERATE -> 0.0
                RomancePolicy.PENALIZE -> when {
                    romance >= MAIN_ROMANCE -> 0.45
                    romance >= 0.5 -> 0.25
                    romance > 0.0 -> 0.05
                    else -> 0.0
                }
                RomancePolicy.EXCLUDE_MAIN -> if (romance >= 0.5) 0.25 else 0.0
            }
            val closest = positive.maxByOrNull { similarity(item, it.features) }
            val sharedTags = closest?.features?.tags?.keys?.intersect(item.tags.keys)?.take(2).orEmpty()
            val reasons = buildList {
                if (closest != null && affinity > 0.05) {
                    add("Thèmes proches de ${closest.features.title ?: "vos lectures"}")
                }
                if (sharedTags.isNotEmpty()) add(sharedTags.joinToString(", "))
                if ((item.voteCount ?: 0) >= 100) add("Note soutenue par ${item.voteCount} votes")
                if (mode == Mode.EXPLORE && novelty > 0.5) add("Thèmes moins présents dans vos lectures")
                if (romancePenalty > 0.0) add("Romance : pénalité ${ (romancePenalty * 100).toInt() } points")
            }
            Ranked(
                item.id,
                (100.0 * (base - 0.35 * dislike - romancePenalty)).coerceIn(0.0, 100.0),
                affinity,
                quality,
                item.voteCount,
                romance,
                reasons,
            )
        }.sortedWith(compareByDescending<Ranked> { it.score }.thenBy { it.id }).toList()
        return diversify(scored, candidates.associateBy { it.id }, mode)
    }

    private fun diversify(scored: List<Ranked>, byId: Map<Long, Features>, mode: Mode): List<Ranked> {
        val remaining = scored.toMutableList()
        val selected = mutableListOf<Ranked>()
        val noveltyPenalty = if (mode == Mode.EXPLORE) 25.0 else 12.0
        while (remaining.isNotEmpty() && selected.size < MAX_RESULTS) {
            val next = remaining.maxWithOrNull(
                compareBy<Ranked> { item ->
                    val maxSimilarity = selected.takeLast(20).maxOfOrNull {
                        similarity(byId.getValue(item.id), byId.getValue(it.id))
                    } ?: 0.0
                    item.score - noveltyPenalty * maxSimilarity
                }.thenByDescending { it.id },
            ) ?: break
            selected += next
            remaining.remove(next)
        }
        return selected
    }

    fun romanceEvidence(item: Features): Double {
        val ranks = (item.tags.filterKeys { it in ROMANCE_TAGS } + item.romanceTags)
            .values.sortedDescending()
        val genre = "Romance" in item.genres
        return when {
            genre && ranks.count { it >= 70 } >= 2 -> 0.9
            genre && ranks.firstOrNull()?.let { it >= 85 } == true -> 0.9
            genre && ranks.firstOrNull()?.let { it >= 70 } == true -> 0.65
            genre -> 0.25
            ranks.count { it >= 80 } >= 2 -> 0.6
            ranks.isNotEmpty() -> 0.2
            else -> 0.0
        }
    }

    private fun jaccard(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val keys = a.keys + b.keys
        val numerator = keys.sumOf { min(a[it] ?: 0.0, b[it] ?: 0.0) }
        val denominator = keys.sumOf { max(a[it] ?: 0.0, b[it] ?: 0.0) }
        return if (denominator == 0.0) 0.0 else numerator / denominator
    }

    private companion object {
        const val PRIOR_VOTES = 100.0
        const val PRIOR_SCORE = 70.0
        const val NEUTRAL_QUALITY = 0.7
        const val TAG_WEIGHT = 0.75
        const val GENRE_WEIGHT = 0.25
        const val MIN_TAG_RANK = 18
        const val MAIN_ROMANCE = 0.8
        const val MAX_SEEDS = 40
        const val MAX_RESULTS = 500
        val ROMANCE_TAGS = setOf("Love Triangle", "Unrequited Love", "Arranged Marriage", "Marriage", "Dating")
    }
}
