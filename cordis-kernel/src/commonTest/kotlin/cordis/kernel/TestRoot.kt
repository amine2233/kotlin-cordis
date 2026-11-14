package cordis.kernel

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

suspend fun <T> withRoot(
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    body: suspend (Context) -> T,
): T {
    val root = Context.root(dispatcher)
    try {
        return body(root)
    } finally {
        root.dispose()
    }
}
