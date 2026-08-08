package coredevices.pebble.weather

import dev.jordond.compass.Place
import dev.jordond.compass.autocomplete.Autocomplete

// dev.jordond.compass:autocomplete-mobile publishes no jvm() variant (see
// docs/ubuntu-touch-poc-plan.md); null means "address autocomplete unavailable on this
// platform", handled by the caller falling back to manual entry.
expect fun createWeatherAutocomplete(): Autocomplete<Place>?
