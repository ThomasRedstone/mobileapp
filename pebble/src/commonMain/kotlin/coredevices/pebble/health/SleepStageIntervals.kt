package coredevices.pebble.health

import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import io.rebble.libpebblecommon.health.OverlayType

// Pure sleep-stage carving used by RealPlatformHealthSync (mobileMain). Kept in commonMain
// because it needs nothing from health-kmp, and commonTest exercises it
// (PlatformHealthSyncTest).

internal data class SleepStageInterval(val startSec: Long, val endSec: Long, val isDeep: Boolean)

// Pebble's overlay model: Sleep/Nap are container overlays spanning the whole session with
// DeepSleep/DeepNap sub-overlays nested inside them. Carve the Deep periods out of each Light
// container so both stage types reach Health Connect.
internal fun computeSleepStageIntervals(overlays: List<OverlayDataEntity>): List<SleepStageInterval> {
    val (deepOverlays, lightOverlays) = overlays.partition {
        when (OverlayType.fromValue(it.type)) {
            OverlayType.DeepSleep, OverlayType.DeepNap -> true
            else -> false
        }
    }
    val deepRanges = deepOverlays
        .map { it.startTime to it.startTime + it.duration }
        .sortedBy { it.first }

    val intervals = mutableListOf<SleepStageInterval>()
    deepRanges.forEach { (s, e) ->
        intervals += SleepStageInterval(s, e, isDeep = true)
    }
    lightOverlays.forEach { container ->
        val containerEnd = container.startTime + container.duration
        var cursor = container.startTime
        for ((deepStart, deepEnd) in deepRanges) {
            if (deepEnd <= cursor) continue
            if (deepStart >= containerEnd) break
            if (cursor < deepStart) {
                intervals += SleepStageInterval(cursor, deepStart, isDeep = false)
            }
            cursor = maxOf(cursor, deepEnd)
        }
        if (cursor < containerEnd) {
            intervals += SleepStageInterval(cursor, containerEnd, isDeep = false)
        }
    }
    intervals.sortBy { it.startSec }
    return intervals
}
