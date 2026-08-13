package coredevices.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import coredevices.util.Platform
import coredevices.util.isDesktop
import org.koin.compose.koinInject
import kotlin.math.abs

/**
 * Vertical scroll's built-in drag-gesture detector never fires for touch input under Ubuntu
 * Touch's Xwayland/AWT setup, though raw pointer press/move/release deltas do reach Compose
 * (confirmed via diagnostic logging, see WatchOnboardingScreen.kt's original fix) - horizontal
 * scrolling (`LazyRow`) is unaffected, and this hits both `Modifier.verticalScroll` and
 * `LazyColumn`'s own scrolling (they share the same underlying gesture machinery), hence a fix
 * keyed on [ScrollableState] - the common interface both `ScrollState` and `LazyListState`
 * implement - rather than tied to one or the other.
 *
 * No-ops on Android/iOS. On desktop, drives [state] directly from the raw pointer stream once
 * past a touch-slop threshold, so button/card taps elsewhere in the scrollable content still work.
 */
fun Modifier.verticalDragFix(state: ScrollableState): Modifier = composed {
    val platform: Platform = koinInject()
    if (!platform.isDesktop) return@composed this

    val touchSlop = LocalViewConfiguration.current.touchSlop
    pointerInput(state, touchSlop) {
        awaitPointerEventScope {
            var dragStartY: Float? = null
            var dragging = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                if (!change.pressed) {
                    dragStartY = null
                    dragging = false
                    continue
                }
                val startY = dragStartY
                if (startY == null) {
                    dragStartY = change.position.y
                    continue
                }
                if (!dragging) {
                    if (abs(change.position.y - startY) < touchSlop) continue
                    dragging = true
                }
                val deltaY = change.position.y - change.previousPosition.y
                state.dispatchRawDelta(-deltaY)
                change.consume()
            }
        }
    }
}

/** Drop-in replacement for `Modifier.verticalScroll(state)` - see [verticalDragFix]. */
fun Modifier.verticalScrollFixed(state: ScrollState): Modifier =
    verticalScroll(state).verticalDragFix(state)
