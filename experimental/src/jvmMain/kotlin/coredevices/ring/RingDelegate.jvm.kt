package coredevices.ring

import com.russhwolf.settings.Settings
import coredevices.ring.database.firestore.FirestoreKnownRingsSync
import coredevices.ring.database.firestore.dao.FirestoreRecordingsDao
import coredevices.util.CoreConfigHolder
import coredevices.util.Permission

actual class RingDelegate(
    private val coreConfigHolder: CoreConfigHolder,
    private val recordingsDao: FirestoreRecordingsDao,
    private val settings: Settings,
    private val firestoreKnownRingsSync: FirestoreKnownRingsSync,
) {
    actual suspend fun init() {
        listenForUserPresent(recordingsDao, coreConfigHolder, settings)
        firestoreKnownRingsSync.init()
    }

    // Desktop JVM has no OS-level runtime permission model to nag about.
    actual fun requiredRuntimePermissions(): Set<Permission> = emptySet()

    actual fun onBackgroundSync() {
        // No-op: this hook exists for iOS background app refresh only.
    }

    actual fun restartPreemptiveTransfer() {
        // No-op: the pre-emptive transfer loop is iOS-only behaviour.
    }
}
