package cordis.samples.android

import cordis.kernel.Fork
import cordis.kernel.HealthVisitor
import cordis.kernel.RuntimeSnapshot
import cordis.kernel.TextTreeVisitor
import cordis.kernel.accept

data class DebugState(
    val tree: String,
    val healthy: Boolean,
    val failed: List<String>,
    val missingServices: List<String>,
    val pending: List<String>,
)

fun debugState(snapshot: RuntimeSnapshot): DebugState {
    val tree = TextTreeVisitor().also { snapshot.accept(it) }.render()
    val report = HealthVisitor().also { snapshot.accept(it) }.report()
    return DebugState(
        tree = tree,
        healthy = report.healthy,
        failed = report.failed.map { "${it.name}: ${(it.status as Fork.Status.Failed).reason}" },
        missingServices =
            report.missingServices.map { plugin ->
                "${plugin.name}: ${plugin.missing.joinToString(", ") { it.description }}"
            },
        pending = report.pending.map { it.name },
    )
}
