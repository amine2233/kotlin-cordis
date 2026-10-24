package cordis.testing

internal expect class Lock() {
    fun <T> withLock(action: () -> T): T
}
