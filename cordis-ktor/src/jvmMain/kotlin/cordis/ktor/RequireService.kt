package cordis.ktor

import cordis.kernel.ServiceKey
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext

/**
 * Handles calls on this route with [handler] while the [key] service slot is filled, passing it the service.
 * The slot is read once per call, so providing or withdrawing the service changes the next response;
 * while it is empty the call gets [whenMissing] with an empty body and [handler] does not run.
 *
 * Example:
 * ```kotlin
 * route("/users", HttpMethod.Get) {
 *     requireService(DbKey) { db -> call.respondText(db.users().joinToString()) }
 * }
 * ```
 */
public fun <T : Any> Route.requireService(
    key: ServiceKey<T>,
    whenMissing: HttpStatusCode = HttpStatusCode.ServiceUnavailable,
    handler: suspend RoutingContext.(service: T) -> Unit,
) {
    handle {
        val service = call.cordis[key]
        if (service == null) call.respond(whenMissing) else handler(service)
    }
}
