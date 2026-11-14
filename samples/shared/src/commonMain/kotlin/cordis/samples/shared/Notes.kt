package cordis.samples.shared

import cordis.kernel.Context
import cordis.kernel.Inject
import cordis.kernel.Plugin
import cordis.kernel.ServiceKey

interface Notes {
    fun add(text: String)

    fun list(): List<String>
}

/**
 * Keeps every note in [database]: the count under `notes.count`, each note under `notes.<index>`.
 */
class InMemoryNotes(
    private val database: Database,
) : Notes {
    override fun add(text: String) {
        val index = count()
        database.put("notes.$index", text)
        database.put(COUNT_KEY, (index + 1).toString())
    }

    override fun list(): List<String> = (0 until count()).mapNotNull { database["notes.$it"] }

    private fun count(): Int = database[COUNT_KEY]?.toInt() ?: 0

    private companion object {
        const val COUNT_KEY = "notes.count"
    }
}

object NotesKey : ServiceKey<Notes>

object NotesPlugin : Plugin<Unit> {
    override val inject: Inject = Inject.of(required = listOf(DatabaseKey))

    override suspend fun apply(
        ctx: Context,
        config: Unit,
    ) {
        ctx.provide(NotesKey, InMemoryNotes(ctx.require(DatabaseKey)))
        ctx.contribute(NavEntries, NavEntry(id = SampleKeys.notes.value, title = "Notes"))
    }
}
