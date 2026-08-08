package io.rebble.libpebblecommon.js

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import io.rebble.libpebblecommon.connection.AppContext
import java.util.prefs.Preferences

internal actual fun createJSSettings(
    appContext: AppContext,
    id: String
): Settings {
    val node = Preferences.userRoot().node("coredevices/coreapp/pkjs/$id")
    return PreferencesSettings(node)
}
