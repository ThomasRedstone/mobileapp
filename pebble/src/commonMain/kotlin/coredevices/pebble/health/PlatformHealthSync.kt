package coredevices.pebble.health

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** com.viktormykhailiv:health-kmp publishes no jvm() variant (see
 *  docs/ubuntu-touch-poc-plan.md), so the real implementation
 *  ([RealPlatformHealthSync]) lives in mobileMain (android/iOS only). */
interface PlatformHealthSync {
    val syncing: StateFlow<Boolean>

    /** Start observing health data updates and app foreground events, auto-syncing to the platform. */
    fun startAutoSync(scope: CoroutineScope)

    /** Check if the health platform is available on this device. */
    fun isAvailable(): Boolean

    /** Request write permissions. Returns true if granted. */
    suspend fun requestPermissions(): Boolean
    suspend fun hasPermission(): Boolean

    /** Run a sync: query new data from Room DB, map to HealthKMP records, write. */
    suspend fun sync()
}
