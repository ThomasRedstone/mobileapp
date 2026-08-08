package coredevices.pebble.util

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.crashlytics.crashlytics

actual fun setCrashlyticsCustomKey(key: String, value: Any) {
    when (value) {
        is String -> Firebase.crashlytics.setCustomKey(key, value)
        is Int -> Firebase.crashlytics.setCustomKey(key, value)
        is Long -> Firebase.crashlytics.setCustomKey(key, value)
        is Double -> Firebase.crashlytics.setCustomKey(key, value)
        is Float -> Firebase.crashlytics.setCustomKey(key, value)
        is Boolean -> Firebase.crashlytics.setCustomKey(key, value)
        else -> Firebase.crashlytics.setCustomKey(key, value.toString())
    }
}

actual fun setCrashlyticsCollectionEnabled(enabled: Boolean) {
    Firebase.crashlytics.setCrashlyticsCollectionEnabled(enabled)
}
