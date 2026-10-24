package cordis.testing

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal actual class Lock actual constructor() {
    private val lock = ReentrantLock()

    actual fun <T> withLock(action: () -> T): T {
        return lock.withLock(action)
    }
}
