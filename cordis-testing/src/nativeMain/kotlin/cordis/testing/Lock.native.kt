package cordis.testing

import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock

@OptIn(ExperimentalForeignApi::class)
internal actual class Lock actual constructor() {
    private val arena = Arena()
    private val mutex = arena.alloc<pthread_mutex_t>()

    init {
        pthread_mutex_init(mutex.ptr, null)
    }

    actual fun <T> withLock(action: () -> T): T {
        pthread_mutex_lock(mutex.ptr)
        try {
            return action()
        } finally {
            pthread_mutex_unlock(mutex.ptr)
        }
    }
}
