package coredevices.ring.ui

import coredevices.indexai.data.entity.ItemDocument.ItemMetadata

actual fun openSystemClockApp(fireKind: ItemMetadata.Scheduled.FireKind): Boolean {
    // No system clock app on desktop/Ubuntu Touch; callers fall back to in-app navigation.
    return false
}
