package cordis

import cordis.kernel.Context
import cordis.kernel.Plugin
import cordis.kernel.ServiceKey

/**
 * Sugar for the most common case: a plugin whose only job is to provide one service.
 */
interface ServicePlugin<K : ServiceKey<V>, V : Any, C> : Plugin<C> {
    val key: K

    suspend fun make(
        ctx: Context,
        config: C,
    ): V

    override suspend fun apply(
        ctx: Context,
        config: C,
    ) {
        val service = make(ctx, config)
        ctx.provide(key, service)
    }
}

/**
 * Sugar for a [ServicePlugin] without configuration ([Unit]).
 */
interface UnitServicePlugin<K : ServiceKey<V>, V : Any> : ServicePlugin<K, V, Unit> {
    suspend fun make(ctx: Context): V

    override suspend fun make(
        ctx: Context,
        config: Unit,
    ): V = make(ctx)
}
