package coredevices.libindex.device

// Pure paired-ring decisions used by IndexDeviceManager (mobileMain). Kept in commonMain
// because they need nothing from coredevices.haversine, and commonTest exercises them
// (PairedRingReconcileTest, PairedRingResolutionTest).

internal data class StoredPairing(val id: String?, val name: String?)

/** Pure state transition; the caller keeps the side effects (collection wipe, logging). */
internal fun StoredPairing.applyDecision(decision: PairedRingDecision): StoredPairing =
    when (decision) {
        PairedRingDecision.Keep -> this
        PairedRingDecision.Clear -> StoredPairing(null, null)
        is PairedRingDecision.UpdateName -> copy(name = decision.name)
        is PairedRingDecision.Adopt -> StoredPairing(decision.identifier, decision.name)
    }

internal sealed interface PairedRingDecision {
    data object Keep : PairedRingDecision
    data object Clear : PairedRingDecision
    data class UpdateName(val name: String) : PairedRingDecision
    data class Adopt(val identifier: String, val name: String) : PairedRingDecision
}

/**
 * Pure decision for what to do with the stored paired ring, re-evaluated whenever the
 * association list changes.
 *
 * [associations] is null when the platform has no bt association support (iOS) or the
 * list has not been loaded yet (BLUETOOTH_CONNECT not granted / BT off). Must never
 * resolve to [PairedRingDecision.Clear] in that case.
 */
internal fun resolvePairedRing(
    storedPairedId: String?,
    storedPairedName: String?,
    associations: List<IndexAssociation>?,
): PairedRingDecision {
    if (associations == null) return PairedRingDecision.Keep
    if (storedPairedId != null) {
        val match = associations.firstOrNull { it.identifier == IndexIdentifier(storedPairedId) }
        return when {
            match == null -> PairedRingDecision.Clear
            match.deviceName != storedPairedName -> PairedRingDecision.UpdateName(match.deviceName)
            else -> PairedRingDecision.Keep
        }
    }
    val candidate = associations.firstOrNull { it.deviceName.contains("Pebble Index", ignoreCase = true) }
    return candidate?.let { PairedRingDecision.Adopt(it.identifier.asString, it.deviceName) }
        ?: PairedRingDecision.Keep
}