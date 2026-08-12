package coredevices.analytics

import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import coredevices.util.AppDirs
import java.util.Properties

// Settings()'s JVM no-arg factory is PreferencesSettings(Preferences.userRoot()), whose backing
// store resolves under $HOME/.java/.userPrefs/ - outside the Click's writable dirs under real
// confinement, same bug class as JSLocalStorageInterface.jvm.kt. A Properties file under
// AppDirs avoids it.
actual fun createAnalyticsCache(): Settings {
    val file = AppDirs.dataDir("analytics.properties")
    val properties = Properties()
    if (file.exists()) {
        file.inputStream().use { properties.load(it) }
    }
    return PropertiesSettings(properties) { toSave ->
        file.parentFile.mkdirs()
        file.outputStream().use { toSave.store(it, null) }
    }
}
