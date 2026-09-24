package mihon.discover

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CatalogueFilterDialog(
    current: CatalogueFilters,
    presets: Map<String, CatalogueFilters>,
    options: AnilistCatalogApi.FilterOptions?,
    onApply: (CatalogueFilters) -> Unit,
    onSave: (String, CatalogueFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(current) { mutableStateOf(current) }
    var name by remember { mutableStateOf("") }
    var genreSearch by remember { mutableStateOf("") }
    var tagSearch by remember { mutableStateOf("") }
    var presetRevision by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filtres AniList") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (presets.isNotEmpty()) {
                    Text("Préréglages")
                    ChoiceRow(presets.keys.toList(), emptySet()) { selected ->
                        value = presets.getValue(selected)
                        presetRevision++
                    }
                }
                Text("Format")
                ChoiceRow(listOf("MANGA", "ONE_SHOT"), value.formats) { selected ->
                    value = value.copy(formats = value.formats.toggle(selected))
                }
                Text("Publication")
                ChoiceRow(listOf("RELEASING", "FINISHED", "HIATUS", "CANCELLED"), value.statuses) { selected ->
                    value = value.copy(statuses = value.statuses.toggle(selected))
                }
                Text("Origine")
                ChoiceRow(listOf("JP", "KR", "CN"), value.countries) { selected ->
                    value = value.copy(countries = value.countries.toggle(selected))
                }
                Text("Apparence et thèmes")
                BooleanRow("Long Strip (webtoon)", value.longStrip) { value = value.copy(longStrip = it) }
                BooleanRow("Full Color", value.fullColor) { value = value.copy(fullColor = it) }
                TextSetField("Genres inclus (virgules)", value.genres, presetRevision) {
                    value = value.copy(genres = it)
                }
                BooleanRow("Exiger tous les genres", value.requireAllGenres) {
                    value = value.copy(requireAllGenres = it)
                }
                if (options != null) {
                    OutlinedTextField(
                        genreSearch,
                        onValueChange = { genreSearch = it },
                        label = { Text("Chercher un genre AniList") },
                    )
                    ChoiceRow(
                        options.genres.filter { it.contains(genreSearch, ignoreCase = true) }.take(10),
                        value.genres,
                    ) { value = value.copy(genres = value.genres.toggle(it)) }
                }
                TextSetField("Genres exclus (virgules)", value.excludedGenres, presetRevision) {
                    value = value.copy(excludedGenres = it)
                }
                TextSetField("Tags inclus (virgules)", value.tags, presetRevision) { value = value.copy(tags = it) }
                BooleanRow("Exiger tous les tags", value.requireAllTags) { value = value.copy(requireAllTags = it) }
                if (options != null) {
                    OutlinedTextField(
                        tagSearch,
                        onValueChange = { tagSearch = it },
                        label = { Text("Chercher un tag AniList") },
                    )
                    ChoiceRow(
                        options.tags.filter { it.name.contains(tagSearch, ignoreCase = true) }.take(10).map { it.name },
                        value.tags,
                    ) { value = value.copy(tags = value.tags.toggle(it)) }
                }
                TextSetField("Tags exclus (virgules)", value.excludedTags, presetRevision) {
                    value = value.copy(excludedTags = it)
                }
                TextSetField("Catégories de tags (virgules)", value.tagCategories, presetRevision) {
                    value = value.copy(tagCategories = it)
                }
                if (options != null) {
                    ChoiceRow(options.tags.map { it.category }.distinct().take(12), value.tagCategories) {
                        value = value.copy(tagCategories = value.tagCategories.toggle(it))
                    }
                }
                IntegerField("Pertinence minimale des tags", value.minimumTagRank) {
                    value = value.copy(minimumTagRank = (it ?: 18).coerceIn(0, 100))
                }
                IntegerField("Note AniList minimale", value.minScore) { value = value.copy(minScore = it) }
                IntegerField("Nombre de votes minimal", value.minVotes) { value = value.copy(minVotes = it) }
                IntegerField("Qualité corrigée minimale", value.minQuality) { value = value.copy(minQuality = it) }
                IntegerField("Popularité minimale", value.minPopularity) { value = value.copy(minPopularity = it) }
                IntegerField("Favoris AniList minimum", value.minFavourites) { value = value.copy(minFavourites = it) }
                IntegerField("Année de début minimale", value.startYear) { value = value.copy(startYear = it) }
                IntegerField("Année de début maximale", value.endYear) { value = value.copy(endYear = it) }
                IntegerField("Chapitres minimum", value.minChapters) { value = value.copy(minChapters = it) }
                IntegerField("Chapitres maximum", value.maxChapters) { value = value.copy(maxChapters = it) }
                IntegerField("Volumes minimum", value.minVolumes) { value = value.copy(minVolumes = it) }
                Text("Origine de l’œuvre")
                ChoiceRow(listOf("ORIGINAL", "MANGA", "LIGHT_NOVEL", "WEB_NOVEL"), value.origins) {
                    value = value.copy(origins = value.origins.toggle(it))
                }
                Text("Licence officielle")
                ChoiceRow(
                    listOf("Oui", "Non", "Indifférent"),
                    setOf(
                        when (value.isLicensed) {
                            true -> "Oui"
                            false -> "Non"
                            null -> "Indifférent"
                        },
                    ),
                ) {
                    value = value.copy(
                        isLicensed = when (it) {
                            "Oui" -> true
                            "Non" -> false
                            else -> null
                        },
                    )
                }
                BooleanRow("Exclure la romance principale probable", value.excludeMainRomance) {
                    value = value.copy(excludeMainRomance = it)
                }
                Text("Mes lectures (œuvres identifiées seulement)")
                BooleanRow("Masquer celles en bibliothèque", value.hideKnownLibrary) {
                    value = value.copy(hideKnownLibrary = it, onlyKnownLibrary = false)
                }
                BooleanRow("Afficher seulement ma bibliothèque", value.onlyKnownLibrary) {
                    value = value.copy(onlyKnownLibrary = it, hideKnownLibrary = false)
                }
                BooleanRow("Masquer les œuvres déjà commencées", value.hideKnownStarted) {
                    value = value.copy(hideKnownStarted = it)
                }
                BooleanRow("Afficher seulement mes coups de cœur", value.onlyLiked) {
                    value = value.copy(onlyLiked = it)
                }
                BooleanRow("Masquer mes dislikes", value.excludeDisliked) {
                    value = value.copy(excludeDisliked = it)
                }
                BooleanRow("Inclure le contenu adulte", !value.excludeAdult) {
                    value = value.copy(excludeAdult = !it)
                }
                OutlinedTextField(name, onValueChange = { name = it }, label = { Text("Nom du préréglage") })
                TextButton(onClick = { onSave(name, value) }, enabled = name.isNotBlank()) {
                    Text("Enregistrer les filtres")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(value) }) { Text("Appliquer") } },
        dismissButton = {
            Row {
                TextButton(onClick = { value = CatalogueFilters() }) { Text("Réinitialiser") }
                TextButton(onClick = onDismiss) { Text("Fermer") }
            }
        },
    )
}

@Composable
private fun ChoiceRow(choices: List<String>, selected: Set<String>, onClick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEach { value ->
            FilterChip(selected = value in selected, onClick = { onClick(value) }, label = { Text(value) })
        }
    }
}

@Composable
private fun BooleanRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun TextSetField(label: String, value: Set<String>, presetRevision: Int, onChange: (Set<String>) -> Unit) {
    var text by remember(presetRevision) { mutableStateOf(value.joinToString(", ")) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            onChange(input.split(',').map(String::trim).filter(String::isNotEmpty).toSet())
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun IntegerField(label: String, value: Int?, onChange: (Int?) -> Unit) {
    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { onChange(it.toIntOrNull()) },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun Set<String>.toggle(value: String) = if (value in this) this - value else this + value
