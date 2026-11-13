package cordis.kernel

internal actual class Lock actual constructor() {
    actual fun <T> withLock(action: () -> T): T = action()
}
