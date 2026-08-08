package coredevices.database

import PlatformContext
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

internal actual fun getCoreDatabaseBuilder(ctx: PlatformContext): RoomDatabase.Builder<CoreDatabase> {
    val dataDir = File(System.getProperty("user.home"), ".local/share/coreapp").apply { mkdirs() }
    val dbFile = File(dataDir, CORE_DATABASE_FILENAME)
    return Room.databaseBuilder<CoreDatabase>(
        name = dbFile.absolutePath,
    )
}
