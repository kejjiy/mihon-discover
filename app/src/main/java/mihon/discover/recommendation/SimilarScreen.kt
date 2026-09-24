package mihon.discover.recommendation

import androidx.compose.runtime.Composable
import eu.kanade.presentation.util.Screen
import mihon.discover.DiscoverScreen

class SimilarScreen(private val mediaId: Long) : Screen() {
    @Composable
    override fun Content() = DiscoverScreen(initialSimilarId = mediaId)
}
