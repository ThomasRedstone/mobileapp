package coredevices

import androidx.room.Room
import androidx.room.RoomDatabase
import coredevices.ring.RingDelegate
import coredevices.ring.agent.integrations.obsidian.JvmObsidianVault
import coredevices.ring.agent.integrations.obsidian.ObsidianVault
import coredevices.ring.database.IntegrationTokenStorageImpl
import coredevices.ring.database.room.RingDatabase
import coredevices.ring.encryption.EncryptionKeyManager
import coredevices.ring.model.CactusModelProvider
import coredevices.ring.service.PlatformIndexNotificationManager
import coredevices.ring.ui.screens.settings.SettingsBeeperContactsDialogViewModel
import coredevices.ring.util.AudioPlayer
import coredevices.ring.util.AudioRecorder
import coredevices.util.integrations.IntegrationTokenStorage
import coredevices.util.transcription.CactusModelPathProvider
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module
import java.io.File

// Ring/watch BLE satellite pairing (KMPHaversineSatelliteManager, used on Android/iOS) has no
// desktop equivalent wired up here — Ring device connectivity on this platform is handled
// separately by libpebble3's jvmMain (busctl/D-Bus), not through this module.
actual val platformRingModule = module {
    single<CactusModelPathProvider> { CactusModelProvider() }
    singleOf(::RingDelegate)
    factoryOf(::AudioRecorder)
    factoryOf(::AudioPlayer)
    factory {
        val dbFile = File(File(System.getProperty("user.home"), ".local/share/coreapp"), "coreapp_room.db")
        dbFile.parentFile?.mkdirs()
        Room.databaseBuilder<RingDatabase>(name = dbFile.absolutePath)
    } bind RoomDatabase.Builder::class
    singleOf(::PlatformIndexNotificationManager)
    singleOf(::IntegrationTokenStorageImpl) bind IntegrationTokenStorage::class
    singleOf(::EncryptionKeyManager)
    viewModelOf(::SettingsBeeperContactsDialogViewModel)
    single<ObsidianVault> { JvmObsidianVault() }
}
