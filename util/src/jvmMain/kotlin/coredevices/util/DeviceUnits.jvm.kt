package coredevices.util

import java.util.Locale

actual fun deviceDefaultWeatherUnit(): WeatherUnit {
    val locale = Locale.getDefault()
    return when (locale.country) {
        "US", "LR", "MM" -> WeatherUnit.Imperial
        "GB" -> WeatherUnit.UkHybrid
        else -> WeatherUnit.Metric
    }
}
