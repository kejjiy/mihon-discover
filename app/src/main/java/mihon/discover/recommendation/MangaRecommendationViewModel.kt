package mihon.discover.recommendation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import mihon.discover.AnilistCatalogApi
import mihon.discover.DiscoverStore
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.repository.TrackRepository

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class MangaRecommendationViewModel(
    private val tracks: TrackRepository,
    private val trackerManager: TrackerManager,
    private val store: RecommendationStore,
    private val oldStore: DiscoverStore,
    private val api: AnilistCatalogApi,
) : ViewModel() {
    data class State(val id: Long? = null, val score: Int? = null, val vote: Int = 0)
    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    fun load(manga: Manga) {
        viewModelScope.launchIO {
            val tracked = tracks.getTracksByMangaId(manga.id)
                .firstOrNull { it.trackerId == trackerManager.aniList.id }
                ?.remoteId?.takeIf { it > 0 }
            val id = tracked ?: store.linkedId(manga.source, manga.url)
                ?: oldStore.linkedMediaId(manga.source, manga.url)
            if (id == null) {
                mutableState.value = State()
                return@launchIO
            }
            val cached = store.getMedia(id) ?: oldStore.getMedia(id)
            mutableState.value = State(id, cached?.averageScore, store.feedback(id).vote)
            if (cached == null || !store.isFresh(id)) {
                try {
                    api.media(id)?.let { media ->
                        store.putMedia(media)
                        mutableState.update { it.copy(score = media.averageScore) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The locally stored link and feedback remain useful offline.
                }
            }
        }
    }

    fun vote(value: Int) {
        val id = state.value.id ?: return
        viewModelScope.launchIO {
            store.setFeedback(id, vote = value)
            mutableState.update { it.copy(vote = value) }
        }
    }
}
