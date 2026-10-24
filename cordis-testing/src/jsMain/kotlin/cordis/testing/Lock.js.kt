package cordis.testing

internal actual class Lock actual constructor() {
    actual fun <T> withLock(action: () -> T): T = action()
}
