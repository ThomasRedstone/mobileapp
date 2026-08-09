package coredevices.coreapp.ui.navigation

import CoreRoute
import com.eygraber.uri.Uri
import coredevices.ExperimentalDevicesFacade
import coredevices.NoOpExperimentalDevicesFacade
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// handle() itself isn't exercised here because its fallback path constructs a
// NavUri (android.net.Uri on Android), which isn't available in JVM unit tests.
class CoreDeepLinkHandlerTest {
    private data class RecordingRoute(val recordingId: Long) : CoreRoute
    private data class ObjectRoute(val objectId: String) : CoreRoute

    // The real route types live in :experimental, which commonTest can't depend on (see
    // ExperimentalDevicesFacade) - so the URIs here are spelled out rather than built by RingRoutes.
    private val facade = object : ExperimentalDevicesFacade by NoOpExperimentalDevicesFacade {
        override fun ringObjectRoute(objectId: String): CoreRoute = ObjectRoute(objectId)
        override fun ringRecordingRoute(recordingId: Long): CoreRoute = RecordingRoute(recordingId)
    }
    private val handler = CoreDeepLinkHandler(facade)

    @Test
    fun recordingDeepLinkParsesToRecordingDetails() {
        val route = handler.recordingRouteFor(Uri.parse("pebblecore://deep-link/recording?id=123"))
        assertEquals(RecordingRoute(123L), route)
    }

    @Test
    fun recordingDeepLinkWithNonNumericIdDoesNotParse() {
        assertNull(handler.recordingRouteFor(Uri.parse("pebblecore://deep-link/recording?id=abc")))
    }

    @Test
    fun recordingDeepLinkWithoutIdDoesNotParse() {
        assertNull(handler.recordingRouteFor(Uri.parse("pebblecore://deep-link/recording")))
    }

    @Test
    fun recordingDeepLinkWithWrongSchemeOrPathDoesNotParse() {
        assertNull(handler.recordingRouteFor(Uri.parse("pebble://deep-link/recording?id=123")))
        assertNull(handler.recordingRouteFor(Uri.parse("pebblecore://deep-link/object?id=123")))
    }

    @Test
    fun objectDeepLinkParsesToObjectDetails() {
        val route = handler.objectRouteFor(Uri.parse("pebblecore://deep-link/object?id=firestore-doc-1"))
        assertEquals(ObjectRoute("firestore-doc-1"), route)
    }

    @Test
    fun objectDeepLinkWithBlankIdDoesNotParse() {
        assertNull(handler.objectRouteFor(Uri.parse("pebblecore://deep-link/object?id=")))
    }
}
