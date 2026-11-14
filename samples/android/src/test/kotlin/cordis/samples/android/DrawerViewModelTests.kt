package cordis.samples.android

import cordis.samples.shared.NavEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DrawerViewModelTests {
    private val notes = NavEntry(id = "notes", title = "Notes")
    private val weather = NavEntry(id = "weather", title = "Weather")

    @Test
    fun startsOnSettings() =
        runViewModelTest {
            val viewModel = DrawerViewModel(MutableStateFlow(listOf(notes)))
            advanceUntilIdle()
            assertEquals(Destination.Settings, viewModel.state.value.selected)
            assertEquals("Settings", viewModel.state.value.title)
        }

    @Test
    fun entriesFollowTheFlow() =
        runViewModelTest {
            val entries = MutableStateFlow(listOf(notes))
            val viewModel = DrawerViewModel(entries)
            advanceUntilIdle()
            assertEquals(listOf(notes), viewModel.state.value.entries)

            entries.value = listOf(notes, weather)
            advanceUntilIdle()
            assertEquals(listOf(notes, weather), viewModel.state.value.entries)
        }

    @Test
    fun selectingAnEntrySelectsIt() =
        runViewModelTest {
            val viewModel = DrawerViewModel(MutableStateFlow(listOf(notes, weather)))
            advanceUntilIdle()

            viewModel.select(Destination.Feature("weather"))
            assertEquals(Destination.Feature("weather"), viewModel.state.value.selected)
            assertEquals("Weather", viewModel.state.value.title)

            viewModel.select(Destination.Settings)
            assertEquals(Destination.Settings, viewModel.state.value.selected)
        }

    @Test
    fun removingTheSelectedEntryFallsBackToSettings() =
        runViewModelTest {
            val entries = MutableStateFlow(listOf(notes, weather))
            val viewModel = DrawerViewModel(entries)
            advanceUntilIdle()
            viewModel.select(Destination.Feature("notes"))

            entries.value = listOf(weather)
            advanceUntilIdle()
            assertEquals(Destination.Settings, viewModel.state.value.selected)

            entries.value = listOf(notes, weather)
            advanceUntilIdle()
            assertEquals(Destination.Settings, viewModel.state.value.selected)
        }

    @Test
    fun removingAnotherEntryKeepsTheSelection() =
        runViewModelTest {
            val entries = MutableStateFlow(listOf(notes, weather))
            val viewModel = DrawerViewModel(entries)
            advanceUntilIdle()
            viewModel.select(Destination.Feature("weather"))

            entries.value = listOf(weather)
            advanceUntilIdle()
            assertEquals(Destination.Feature("weather"), viewModel.state.value.selected)
        }
}
