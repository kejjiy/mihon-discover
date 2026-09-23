package eu.kanade.tachiyomi.ui.discover

import java.text.Normalizer

internal object DiscoverTitleMatcher {
    const val MAX_VARIANTS = 8

    fun variants(media: AniListMedia): List<String> = (listOf(media.title) + media.alternativeTitles)
        .map(::normalize)
        .filter(String::isNotBlank)
        .distinct()
        .take(MAX_VARIANTS)

    fun confidence(title: String, variants: List<String>): SourceMatch.Confidence =
        if (normalize(title) in variants) SourceMatch.Confidence.EXACT else SourceMatch.Confidence.CANDIDATE

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{M}".toRegex(), "")
        .lowercase()
        .replace("[^\\p{L}\\p{N}]+".toRegex(), " ")
        .trim()
}
