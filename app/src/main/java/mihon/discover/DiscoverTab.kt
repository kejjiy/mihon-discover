package mihon.discover

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.TravelExplore

data object DiscoverTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(
            index = 4u,
            title = "Catalogue",
            icon = rememberVectorPainter(MaterialSymbols.Rounded.TravelExplore),
        )

    override suspend fun onReselect(navigator: Navigator) = Unit

    @Composable
    override fun Content() = DiscoverScreen()
}
