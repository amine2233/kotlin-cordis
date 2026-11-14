package cordis.samples.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cordis.runtime.PluginState
import cordis.samples.shared.SampleKeys
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleApp(
    factory: ViewModelProvider.Factory,
    plugins: StateFlow<List<PluginState>>,
) {
    val drawerViewModel: DrawerViewModel = viewModel(factory = factory)
    val state by drawerViewModel.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val select = { destination: Destination ->
        drawerViewModel.select(destination)
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.testTag(TestTags.DRAWER)) {
                state.entries.forEach { entry ->
                    val destination = Destination.Feature(entry.id)
                    NavigationDrawerItem(
                        label = { Text(entry.title) },
                        selected = state.selected == destination,
                        onClick = { select(destination) },
                        modifier = Modifier.testTag(TestTags.drawerItem(entry.id)),
                    )
                }
                NavigationDrawerItem(
                    label = { Text(DrawerState.SETTINGS_TITLE) },
                    selected = state.selected == Destination.Settings,
                    onClick = { select(Destination.Settings) },
                    modifier = Modifier.testTag(TestTags.DRAWER_ITEM_SETTINGS),
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(state.title) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(MenuIcon, contentDescription = "Open navigation drawer")
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (val selected = state.selected) {
                    Destination.Settings -> {
                        SettingsScreen(viewModel(factory = factory))
                    }

                    is Destination.Feature -> {
                        when (selected.id) {
                            SampleKeys.notes.value -> NotesScreen(viewModel(factory = factory))
                            SampleKeys.weather.value -> WeatherScreen(viewModel(factory = factory))
                            else -> InfoScreen(id = selected.id, title = state.title, plugins = plugins)
                        }
                    }
                }
            }
        }
    }
}
