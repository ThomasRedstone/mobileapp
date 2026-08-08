package coredevices.pebble.weather

import dev.jordond.compass.autocomplete.Autocomplete
import dev.jordond.compass.autocomplete.mobile
import dev.jordond.compass.geocoder.Geocoder
import dev.jordond.compass.geocoder.MobileGeocoder
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/** dev.jordond.compass:geocoder-mobile/autocomplete-mobile publish no jvm() variant (see
 *  docs/ubuntu-touch-poc-plan.md); the plain (non-mobile) Geocoder/Autocomplete interfaces do. */
val mobileWeatherModule: Module = module {
    single { MobileGeocoder() } bind Geocoder::class
}

actual fun createWeatherAutocomplete(): Autocomplete? = Autocomplete.mobile()
