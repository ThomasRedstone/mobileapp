package coredevices.util

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

// BLE bonding on the desktop PoC happens in the headless core service via BlueZ/D-Bus, not
// something this UI-side client observes directly yet.
@Composable
actual fun rememberPlatformBondingListener(): Flow<BondingEvent> = emptyFlow()
