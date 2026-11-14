package cordis.samples.shared

import cordis.kernel.Context
import cordis.kernel.Plugin
import cordis.kernel.ServiceKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

interface Database {
    operator fun get(key: String): String?

    fun put(
        key: String,
        value: String,
    )

    fun remove(key: String)
}

class InMemoryDatabase : Database {
    private val values = MutableStateFlow(emptyMap<String, String>())

    override fun get(key: String): String? = values.value[key]

    override fun put(
        key: String,
        value: String,
    ) {
        values.update { it + (key to value) }
    }

    override fun remove(key: String) {
        values.update { it - key }
    }
}

object DatabaseKey : ServiceKey<Database>

object DatabasePlugin : Plugin<Unit> {
    override suspend fun apply(
        ctx: Context,
        config: Unit,
    ) {
        ctx.provide(DatabaseKey, InMemoryDatabase())
        ctx.contribute(NavEntries, NavEntry(id = SampleKeys.database.value, title = "Database"))
    }
}
