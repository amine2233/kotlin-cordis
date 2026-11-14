package cordis.runtime

import cordis.kernel.Fork
import cordis.kernel.PluginKey
import cordis.testing.withTestApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PluginRuntimeTests {
    @Test
    fun initialStateIsCatalogDefaultsWithNullStatus() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val runtime = PluginRuntimeDefault(ctx, testCatalog, InMemoryEnabledPluginsStore())
                val state = runtime.state.value
                assertEquals(listOf(Core, Feature, Extra), state.map { it.key })
                assertEquals(listOf(true, true, false), state.map { it.enabled })
                assertTrue(state.all { it.status == null && it.missing.isEmpty() })
            }
        }

    @Test
    fun startActivatesDefaultsAndLeavesDisabledEntriesUnloaded() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val runtime = PluginRuntimeDefault(ctx, testCatalog, InMemoryEnabledPluginsStore())
                runtime.start()
                advanceUntilIdle()
                val state = runtime.state.value
                assertEquals(Fork.Status.Active, state.of(Core).status)
                assertEquals(Fork.Status.Active, state.of(Feature).status)
                assertEquals(false, state.of(Extra).enabled)
                assertNull(state.of(Extra).status)
                assertEquals(0, ctx.liveForks("Extra"))
            }
        }

    @Test
    fun startTwiceIsANoOp() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val runtime = PluginRuntimeDefault(ctx, testCatalog, InMemoryEnabledPluginsStore())
                runtime.start()
                runtime.start()
                advanceUntilIdle()
                assertEquals(1, ctx.liveForks("Feature"))
            }
        }

    @Test
    fun setEnabledSavesOnlyNonDefaultOverrides() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val store = InMemoryEnabledPluginsStore()
                val runtime = PluginRuntimeDefault(ctx, testCatalog, store)
                runtime.start()
                runtime.setEnabled(Extra, true)
                runtime.setEnabled(Feature, false)
                advanceUntilIdle()
                assertEquals(mapOf(Extra to true, Feature to false), store.load())
                val state = runtime.state.value
                assertEquals(Fork.Status.Active, state.of(Extra).status)
                assertEquals(false, state.of(Feature).enabled)
                assertNull(state.of(Feature).status)
                assertEquals(0, ctx.liveForks("Feature"))
            }
        }

    @Test
    fun settingBackToDefaultRemovesTheOverride() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val store = InMemoryEnabledPluginsStore()
                val runtime = PluginRuntimeDefault(ctx, testCatalog, store)
                runtime.start()
                runtime.setEnabled(Feature, false)
                assertEquals(mapOf(Feature to false), store.load())
                runtime.setEnabled(Feature, true)
                advanceUntilIdle()
                assertEquals(emptyMap(), store.load())
                assertEquals(
                    Fork.Status.Active,
                    runtime.state.value
                        .of(Feature)
                        .status,
                )
            }
        }

    @Test
    fun noOpToggleNeitherSavesNorTouchesTheKernel() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val store = EnabledPluginsStoreMock()
                val runtime = PluginRuntimeDefault(ctx, testCatalog, store)
                runtime.start()
                advanceUntilIdle()
                val before = ctx.snapshot()
                runtime.setEnabled(Feature, true)
                runtime.setEnabled(Extra, false)
                runtime.setEnabled(Core, true)
                advanceUntilIdle()
                assertEquals(0, store.saveCount)
                assertEquals(before, ctx.snapshot())
            }
        }

    @Test
    fun newRuntimeOnTheSameStoreRestoresOverrides() =
        runTest {
            val store = InMemoryEnabledPluginsStore()
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val runtime = PluginRuntimeDefault(ctx, testCatalog, store)
                runtime.start()
                runtime.setEnabled(Feature, false)
                runtime.setEnabled(Extra, true)
                runtime.stop()
            }
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val runtime = PluginRuntimeDefault(ctx, testCatalog, store)
                runtime.start()
                advanceUntilIdle()
                val state = runtime.state.value
                assertEquals(false, state.of(Feature).enabled)
                assertNull(state.of(Feature).status)
                assertEquals(true, state.of(Extra).enabled)
                assertEquals(Fork.Status.Active, state.of(Extra).status)
            }
        }

    @Test
    fun unknownKeysAndLockedDisablesInTheStoreAreIgnored() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val store = InMemoryEnabledPluginsStore(mapOf(PluginKey("gone") to true, Core to false, Feature to true))
                val runtime = PluginRuntimeDefault(ctx, testCatalog, store)
                runtime.start()
                advanceUntilIdle()
                val state = runtime.state.value
                assertEquals(true, state.of(Core).enabled)
                assertEquals(Fork.Status.Active, state.of(Core).status)
                assertEquals(true, state.of(Feature).enabled)
                runtime.setEnabled(Extra, true)
                assertEquals(mapOf(Extra to true), store.load())
            }
        }

    @Test
    fun restartAfterStopReadsTheStoreAgain() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val store = InMemoryEnabledPluginsStore()
                val runtime = PluginRuntimeDefault(ctx, testCatalog, store)
                runtime.start()
                runtime.stop()
                store.save(mapOf(Extra to true))
                runtime.start()
                advanceUntilIdle()
                assertEquals(
                    Fork.Status.Active,
                    runtime.state.value
                        .of(Extra)
                        .status,
                )
                assertEquals(1, ctx.liveForks("Feature"))
            }
        }

    @Test
    fun disablingALockedEntryThrowsAndChangesNothing() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val store = EnabledPluginsStoreMock(mapOf(Feature to false))
                val runtime = PluginRuntimeDefault(ctx, testCatalog, store)
                runtime.start()
                advanceUntilIdle()
                val before = runtime.state.value
                val error = assertFailsWith<PluginRuntimeException.LockedPlugin> { runtime.setEnabled(Core, false) }
                advanceUntilIdle()
                assertEquals(Core, error.key)
                assertEquals(before, runtime.state.value)
                assertEquals(mapOf(Feature to false), store.load())
                assertEquals(0, store.saveCount)
            }
        }

    @Test
    fun unknownKeyThrows() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val store = EnabledPluginsStoreMock()
                val runtime = PluginRuntimeDefault(ctx, testCatalog, store)
                runtime.start()
                advanceUntilIdle()
                val before = runtime.state.value
                val error = assertFailsWith<PluginRuntimeException.UnknownPlugin> { runtime.setEnabled(PluginKey("nope"), true) }
                assertEquals(PluginKey("nope"), error.key)
                assertTrue(error.message.contains("nope"))
                assertEquals(before, runtime.state.value)
                assertEquals(0, store.saveCount)
            }
        }

    @Test
    fun setEnabledBeforeStartOrAfterStopThrowsNotStarted() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val store = EnabledPluginsStoreMock()
                val runtime = PluginRuntimeDefault(ctx, testCatalog, store)
                val initial = runtime.state.value
                assertFailsWith<PluginRuntimeException.NotStarted> { runtime.setEnabled(Extra, true) }
                assertEquals(initial, runtime.state.value)
                runtime.start()
                runtime.stop()
                val stopped = runtime.state.value
                assertFailsWith<PluginRuntimeException.NotStarted> { runtime.setEnabled(Extra, true) }
                assertEquals(stopped, runtime.state.value)
                assertEquals(0, store.saveCount)
                assertEquals(0, ctx.liveForks("Extra"))
            }
        }

    @Test
    fun failedSaveLeavesKernelAndStateUnchanged() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val runtime = PluginRuntimeDefault(ctx, testCatalog, EnabledPluginsStoreMock(failSaves = true))
                runtime.start()
                advanceUntilIdle()
                val before = runtime.state.value
                assertFailsWith<StoreFailure> { runtime.setEnabled(Feature, false) }
                advanceUntilIdle()
                assertEquals(before, runtime.state.value)
                assertEquals(1, ctx.liveForks("Feature"))
            }
        }

    @Test
    fun disablingTheProviderMakesTheDependentPendingWithItsMissingService() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val runtime = PluginRuntimeDefault(ctx, dependencyCatalog, InMemoryEnabledPluginsStore())
                runtime.start()
                advanceUntilIdle()
                assertEquals(
                    Fork.Status.Active,
                    runtime.state.value
                        .of(Dependent)
                        .status,
                )

                runtime.setEnabled(Provider, false)
                advanceUntilIdle()
                val pending = runtime.state.value.of(Dependent)
                assertEquals(Fork.Status.Pending, pending.status)
                assertEquals(true, pending.enabled)
                assertTrue(ProvidedKey.serviceId in pending.missing)

                runtime.setEnabled(Provider, true)
                advanceUntilIdle()
                val active = runtime.state.value.of(Dependent)
                assertEquals(Fork.Status.Active, active.status)
                assertEquals(emptyList(), active.missing)
            }
        }

    @Test
    fun stateEmitsStartThenToggleAndNothingForANoOp() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val runtime = PluginRuntimeDefault(ctx, testCatalog, InMemoryEnabledPluginsStore())
                val emissions = mutableListOf<List<PluginState>>()
                val collector =
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        runtime.state.collect { emissions.add(it) }
                    }
                runtime.start()
                advanceUntilIdle()
                val started = runtime.state.value
                runtime.setEnabled(Extra, true)
                advanceUntilIdle()
                val toggled = runtime.state.value
                runtime.setEnabled(Extra, true)
                advanceUntilIdle()
                collector.cancel()

                assertEquals(Fork.Status.Active, toggled.of(Extra).status)
                assertEquals(listOf(started, toggled), emissions.drop(1))
            }
        }

    @Test
    fun hundredSequentialTogglesEndWithTheLastValue() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val store = InMemoryEnabledPluginsStore()
                val runtime = PluginRuntimeDefault(ctx, testCatalog, store)
                runtime.start()
                repeat(100) { runtime.setEnabled(Extra, it % 2 == 0) }
                advanceUntilIdle()
                val extra = runtime.state.value.of(Extra)
                assertEquals(false, extra.enabled)
                assertNull(extra.status)
                assertEquals(emptyMap(), store.load())
                assertEquals(0, ctx.liveForks("Extra"))

                runtime.setEnabled(Extra, true)
                advanceUntilIdle()
                assertEquals(1, ctx.liveForks("Extra"))
            }
        }

    @Test
    fun hundredConcurrentTogglesLeaveStateStoreAndKernelInAgreement() =
        runTest {
            withTestApp { ctx, _ ->
                val store = InMemoryEnabledPluginsStore()
                val runtime = PluginRuntimeDefault(ctx, testCatalog, store)
                runtime.start()
                withContext(Dispatchers.Default) {
                    (0 until 100)
                        .map { index ->
                            launch {
                                val key = if (index % 2 == 0) Feature else Extra
                                runtime.setEnabled(key, index % 4 < 2)
                            }
                        }.joinAll()
                }
                val state = runtime.state.value
                val saved = store.load()
                for (entry in state) {
                    val expectedOverride = entry.enabled.takeIf { it != entry.metadata.enabledByDefault }
                    assertEquals(expectedOverride, saved[entry.key])
                    assertEquals(if (entry.enabled) 1 else 0, ctx.liveForks(entry.metadata.title))
                    assertEquals(if (entry.enabled) Fork.Status.Active else null, entry.status)
                }
            }
        }

    @Test
    fun stopDisposesLoadedForksWithoutSaving() =
        runTest {
            withTestApp(StandardTestDispatcher(testScheduler)) { ctx, _ ->
                val store = EnabledPluginsStoreMock(mapOf(Extra to true))
                val runtime = PluginRuntimeDefault(ctx, testCatalog, store)
                runtime.start()
                advanceUntilIdle()
                runtime.stop()
                runtime.stop()
                advanceUntilIdle()
                assertEquals(0, store.saveCount)
                assertEquals(listOf(0, 0, 0), listOf("Core", "Feature", "Extra").map { ctx.liveForks(it) })
                val state = runtime.state.value
                assertEquals(listOf(true, true, true), state.map { it.enabled })
                assertTrue(state.all { it.status == null })
            }
        }
}
