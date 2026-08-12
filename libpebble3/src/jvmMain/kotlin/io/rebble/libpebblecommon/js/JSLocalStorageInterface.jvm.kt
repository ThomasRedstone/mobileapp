package io.rebble.libpebblecommon.js

import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import io.rebble.libpebblecommon.connection.AppContext
import java.io.File
import java.util.Properties

// java.util.prefs.Preferences.userRoot() (the previous backing store) always resolves under
// $HOME/.java/.userPrefs/, outside the Click's writable directories - a background sync thread
// throws SecurityException on every flush attempt under confinement, and pkjs local-storage
// writes are silently lost. $TMPDIR matches Database.jvm.kt's own writable-dir resolution for
// the same reason.
private fun jsSettingsFile(id: String): File {
    val tmpDir = System.getenv("TMPDIR")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("java.io.tmpdir")
    return File(tmpDir, "pkjs-settings/$id.properties")
}

internal actual fun createJSSettings(
    appContext: AppContext,
    id: String
): Settings {
    val file = jsSettingsFile(id)
    val properties = Properties()
    if (file.exists()) {
        file.inputStream().use { properties.load(it) }
    }
    return PropertiesSettings(properties) { toSave ->
        file.parentFile.mkdirs()
        file.outputStream().use { toSave.store(it, null) }
    }
}
