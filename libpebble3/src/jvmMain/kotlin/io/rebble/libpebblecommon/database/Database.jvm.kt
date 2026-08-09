package io.rebble.libpebblecommon.database

import androidx.room.Room
import androidx.room.RoomDatabase
import io.rebble.libpebblecommon.connection.AppContext
import java.io.File

internal actual fun getDatabaseBuilder(ctx: AppContext): RoomDatabase.Builder<Database> {
    //TODO: This is a temporary solution, we should use a proper path
    // $TMPDIR (an env var, not the java.io.tmpdir system property) if set - under Click
    // confinement plain /tmp isn't writable, and jpackage's native launcher doesn't reliably
    // pick up -Djava.io.tmpdir overrides (its JVM args are baked into a static .cfg file, not
    // read from JDK_JAVA_OPTIONS at runtime), but a real env var always propagates.
    val tmpDir = System.getenv("TMPDIR")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("java.io.tmpdir")
    val dbFile = File(tmpDir, DATABASE_FILENAME)
    return Room.databaseBuilder<Database>(
        name = dbFile.absolutePath,
    )
}