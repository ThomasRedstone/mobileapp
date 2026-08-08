package coredevices.pebble.util

// dev.gitlive:firebase-crashlytics publishes no jvm artifact (see
// docs/ubuntu-touch-poc-plan.md), so :pebble's own Crashlytics touch points go through this
// seam instead of calling Firebase.crashlytics directly from commonMain.
expect fun setCrashlyticsCustomKey(key: String, value: Any)
expect fun setCrashlyticsCollectionEnabled(enabled: Boolean)
