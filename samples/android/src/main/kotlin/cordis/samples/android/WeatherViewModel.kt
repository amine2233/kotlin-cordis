package cordis.samples.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cordis.samples.shared.Weather
import cordis.samples.shared.WeatherReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class WeatherState(
    val reading: WeatherReading? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class WeatherViewModel(
    weather: Flow<Weather?>,
) : ViewModel() {
    val state: StateFlow<WeatherState> =
        weather
            .flatMapLatest { service -> service?.reading ?: flowOf(null) }
            .map { WeatherState(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, WeatherState())
}
