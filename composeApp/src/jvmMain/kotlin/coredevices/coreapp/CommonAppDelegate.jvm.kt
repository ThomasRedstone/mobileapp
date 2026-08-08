package coredevices.coreapp

import coredevices.util.CoreConfig
import io.rebble.libpebblecommon.connection.AppContext

// No WorkManager equivalent on desktop yet - background weather sync isn't scheduled there.
actual fun rescheduleBgRefreshTask(appContext: AppContext, coreConfig: CoreConfig) {}
