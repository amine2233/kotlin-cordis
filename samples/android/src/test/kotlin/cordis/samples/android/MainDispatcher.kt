package cordis.samples.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** Runs [body] with `Dispatchers.Main`, and so `viewModelScope`, on the test scheduler. */
@OptIn(ExperimentalCoroutinesApi::class)
fun runViewModelTest(body: suspend TestScope.() -> Unit) =
    runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            body()
        } finally {
            Dispatchers.resetMain()
        }
    }
