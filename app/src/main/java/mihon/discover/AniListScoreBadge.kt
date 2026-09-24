package mihon.discover

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import mihon.discover.recommendation.MangaRecommendationViewModel
import mihon.discover.recommendation.SimilarScreen
import tachiyomi.domain.manga.model.Manga

/**
 * The one composable integration point on Mihon's manga details.
 */
@Composable
fun AniListScoreBadge(
    manga: Manga,
    modifier: Modifier = Modifier,
) {
    val model = metroViewModel<MangaRecommendationViewModel>()
    val state by model.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.currentOrThrow
    LaunchedEffect(manga.id, manga.url) { model.load(manga) }
    val id = state.id ?: return
    Column(modifier) {
        state.score?.let { Text("AniList $it/100", style = MaterialTheme.typography.labelLarge) }
        Row {
            TextButton(onClick = { model.vote(if (state.vote > 0) 0 else 1) }) {
                Text(if (state.vote > 0) "♥ Aimé" else "♡ J’aime")
            }
            TextButton(onClick = { model.vote(if (state.vote < 0) 0 else -1) }) {
                Text(if (state.vote < 0) "Je n’aime pas ✓" else "Je n’aime pas")
            }
            TextButton(onClick = { navigator.push(SimilarScreen(id)) }) { Text("Similaires") }
        }
    }
}
