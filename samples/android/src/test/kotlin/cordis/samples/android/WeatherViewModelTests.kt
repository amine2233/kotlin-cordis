package cordis.samples.android

import cordis.samples.shared.InMemoryWeather
import cordis.samples.shared.Weather
import cordis.samples.shared.WeatherReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class WeatherViewModelTests {
    @Test
    fun followsTheLatestServiceAndItsReading() =
        runViewModelTest {
            val service = MutableStateFlow<Weather?>(null)
            val viewModel = WeatherViewModel(service)
            advanceUntilIdle()
            assertNull(viewModel.state.value.reading)

            val weather = InMemoryWeather()
            service.value = weather
            advanceUntilIdle()
            assertEquals(WeatherReading(update = 0, temperatureCelsius = 18), viewModel.state.value.reading)

            weather.refresh()
            advanceUntilIdle()
            assertEquals(WeatherReading(update = 1, temperatureCelsius = 19), viewModel.state.value.reading)

            service.value = null
            advanceUntilIdle()
            assertNull(viewModel.state.value.reading)
        }
}
