package coredevices.pebble.health

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// No Health Connect/HealthKit-equivalent platform health store on desktop Linux, and
// com.viktormykhailiv:health-kmp publishes no jvm() variant regardless (see
// docs/ubuntu-touch-poc-plan.md).
object NoOpPlatformHealthSync : PlatformHealthSync {
    override val syncing: StateFlow<Boolean> = MutableStateFlow(false)
    override fun startAutoSync(scope: CoroutineScope) {}
    override fun isAvailable(): Boolean = false
    override suspend fun requestPermissions(): Boolean = false
    override suspend fun hasPermission(): Boolean = false
    override suspend fun sync() {}
}
