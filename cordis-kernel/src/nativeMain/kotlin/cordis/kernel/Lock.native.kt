package cordis.kernel

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal actual class Lock actual constructor() {
    private val mutex = nativeHeap.alloc<pthread_mutex_t>()

    init {
        pthread_mutex_init(mutex.ptr, null)
    }

    // A Scope owns a Lock, so unlike cordis-testing's single ManualClock lock these are created per plugin start.
    @Suppress("unused")
    private val cleaner =
        createCleaner(mutex) {
            pthread_mutex_destroy(it.ptr)
            nativeHeap.free(it)
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
