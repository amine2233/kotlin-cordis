package cordis.kernel

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Probe {
    val log = mutableListOf<String>()
}

class ScopeTests {
    @Test
    fun disposeRunsTeardownsInLIFOOrder() =
        runTest {
            val scope = Scope()
            val probe = Probe()
            scope.collect { probe.log.add("1") }
            scope.collect { probe.log.add("2") }
            scope.collect { probe.log.add("3") }
            scope.dispose()
            assertEquals(listOf("3", "2", "1"), probe.log)
            assertTrue(scope.isDisposed)
        }

    @Test
    fun disposableIsIdempotent() =
        runTest {
            val scope = Scope()
            val probe = Probe()
            val handle = scope.collect { probe.log.add("x") }
            handle()
            handle()
            scope.dispose()
            assertEquals(listOf("x"), probe.log)
            assertTrue(handle.isDisposed)
        }

    @Test
    fun disposeIsIdempotent() =
        runTest {
            val scope = Scope()
            val probe = Probe()
            scope.collect { probe.log.add("x") }
            scope.dispose()
            scope.dispose()
            assertEquals(listOf("x"), probe.log)
        }
}
