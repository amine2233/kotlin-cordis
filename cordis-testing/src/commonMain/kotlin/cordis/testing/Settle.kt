package cordis.testing

import kotlinx.coroutines.yield

/**
 * Yields up to 10 000 times until `condition` holds; returns whether it did.
 */
suspend fun settle(until: () -> Boolean): Boolean {
    for (i in 0 until 10_000) {
        if (until()) return true
        yield()
    }
    return until()
}
