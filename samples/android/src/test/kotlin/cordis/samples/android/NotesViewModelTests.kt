package cordis.samples.android

import cordis.samples.shared.InMemoryDatabase
import cordis.samples.shared.InMemoryNotes
import cordis.samples.shared.Notes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTests {
    @Test
    fun addAppendsTheNoteAndClearsTheInput() =
        runViewModelTest {
            val viewModel = NotesViewModel(MutableStateFlow<Notes?>(InMemoryNotes(InMemoryDatabase())))
            advanceUntilIdle()
            assertTrue(viewModel.state.value.available)
            assertFalse(viewModel.state.value.canAdd)

            viewModel.onInputChange("Buy milk")
            assertTrue(viewModel.state.value.canAdd)
            viewModel.add()

            assertEquals(listOf("Buy milk"), viewModel.state.value.notes)
            assertEquals("", viewModel.state.value.input)
        }

    @Test
    fun blankInputIsNotAdded() =
        runViewModelTest {
            val viewModel = NotesViewModel(MutableStateFlow<Notes?>(InMemoryNotes(InMemoryDatabase())))
            advanceUntilIdle()

            viewModel.onInputChange("   ")
            viewModel.add()

            assertEquals(emptyList(), viewModel.state.value.notes)
        }

    @Test
    fun followsTheService() =
        runViewModelTest {
            val database = InMemoryDatabase()
            InMemoryNotes(database).add("Existing")
            val service = MutableStateFlow<Notes?>(null)
            val viewModel = NotesViewModel(service)
            advanceUntilIdle()
            assertFalse(viewModel.state.value.available)

            service.value = InMemoryNotes(database)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.available)
            assertEquals(listOf("Existing"), viewModel.state.value.notes)
        }
}
