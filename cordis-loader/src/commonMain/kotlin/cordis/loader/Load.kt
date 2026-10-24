package cordis.loader

import cordis.kernel.Context
import cordis.kernel.Fork

/**
 * The result of loading one plan entry.
 */
data class LoadOutcome(
    val key: String,
    val name: String?,
    val status: Fork.Status
)

/**
 * Loads the plan in order and returns one outcome per entry.
 * An invalid plan loads nothing: every entry is reported as failed.
 */
suspend fun Context.load(plan: LoadPlan, registry: PluginRegistry): List<LoadOutcome> {
    val errors = plan.validate(against = registry)
    if (errors.isNotEmpty()) {
        return plan.entries.mapIndexed { index, entry ->
            val reasons = errors.filter { it.entry == index }.map { it.message }
            val reason = if (reasons.isEmpty()) {
                "plan is invalid; nothing was loaded"
            } else {
                reasons.joinToString("; ")
            }
            LoadOutcome(key = entry.key, name = entry.name, status = Fork.Status.Failed(reason))
        }
    }

    val outcomes = mutableListOf<LoadOutcome>()
    for (entry in plan.entries) {
        val status: Fork.Status = try {
            val loader = registry.loader(entry.key, entry.config)
                ?: throw LoaderError.UnknownKey(entry.key, entry = outcomes.size)
            loader(this)
        } catch (t: Throwable) {
            Fork.Status.Failed(t.message ?: t.toString())
        }
        outcomes.add(LoadOutcome(key = entry.key, name = entry.name, status = status))
    }
    return outcomes
}
