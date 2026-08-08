package coredevices

import CoreNav
import CoreRoute
import DocumentAttachment
import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import coredevices.pebble.ui.TopBarParams
import kotlinx.io.files.Path

/**
 * Seam between the shared app shell and `:experimental` (the Index 01/Ring
 * device feature module), so composeApp's commonMain doesn't need to
 * depend on `:experimental` directly. `:experimental` pulls in `:libindex`,
 * which needs the `haversine` BLE library — no jvm() target, so any
 * platform without it (e.g. this project's Ubuntu Touch desktop target)
 * can bind [NoOpExperimentalDevicesFacade] instead of the real
 * `ExperimentalDevices` implementation.
 */
interface ExperimentalDevicesFacade {
    fun addExperimentalRoutes(builder: NavGraphBuilder, coreNav: CoreNav)
    fun isRingRoute(route: CoreRoute): Boolean
    fun ringObjectRoute(objectId: String): CoreRoute
    fun ringRecordingRoute(recordingId: Long): CoreRoute

    @Composable
    fun IndexScreen(coreNav: CoreNav, topBarParams: TopBarParams)

    @Composable
    fun RingOnboardingScreen(coreNav: CoreNav)

    suspend fun init()
    fun onBackgroundSync()
    fun debugSummary(): String
    fun badCollectionsDir(): Path?
    suspend fun exportOutput(id: String): List<DocumentAttachment>
    suspend fun exportRecentRecordings(limit: Int = 10): List<DocumentAttachment>
    suspend fun exportTraceSessions(limit: Int = 20): List<DocumentAttachment>
}

object NoOpExperimentalDevicesFacade : ExperimentalDevicesFacade {
    override fun addExperimentalRoutes(builder: NavGraphBuilder, coreNav: CoreNav) {}
    override fun isRingRoute(route: CoreRoute): Boolean = false
    override fun ringObjectRoute(objectId: String): CoreRoute = NoOpRoute
    override fun ringRecordingRoute(recordingId: Long): CoreRoute = NoOpRoute

    private object NoOpRoute : CoreRoute

    @Composable
    override fun IndexScreen(coreNav: CoreNav, topBarParams: TopBarParams) {}

    @Composable
    override fun RingOnboardingScreen(coreNav: CoreNav) {
        coreNav.goBack()
    }

    override suspend fun init() {}
    override fun onBackgroundSync() {}
    override fun debugSummary(): String = ""
    override fun badCollectionsDir(): Path? = null
    override suspend fun exportOutput(id: String): List<DocumentAttachment> = emptyList()
    override suspend fun exportRecentRecordings(limit: Int): List<DocumentAttachment> = emptyList()
    override suspend fun exportTraceSessions(limit: Int): List<DocumentAttachment> = emptyList()
}
