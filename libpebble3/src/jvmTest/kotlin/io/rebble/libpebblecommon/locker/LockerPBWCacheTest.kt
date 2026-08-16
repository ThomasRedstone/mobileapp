package io.rebble.libpebblecommon.locker

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.database.dao.LockerEntryRealDao.DbAppBasicProperties
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private class NoopLockerPBWCache : LockerPBWCache(AppContext()) {
    override suspend fun handleCacheMiss(appId: Uuid, version: String, locker: Locker): Path? = null
}

class LockerPBWCacheTest {
    private val cacheDir = getLockerPBWCacheDirectory(AppContext())
    private val cache = NoopLockerPBWCache()
    private val writtenIds = mutableListOf<Uuid>()

    private fun pathFor(id: Uuid, version: String) = Path(cacheDir, "${id}_$version.pbw")

    private fun writeCachedFile(id: Uuid, version: String = "1.0") {
        writtenIds += id
        cache.addPBWFileForApp(id, version, Buffer().apply { write(byteArrayOf(1, 2, 3)) })
    }

    private fun basicProperties(id: Uuid, sideloaded: Boolean = false) = DbAppBasicProperties(
        id = id,
        title = "test",
        type = "watchapp",
        developerName = "core",
        orderIndex = 0,
        active = false,
        grantedPermissions = null,
        sideloaded = sideloaded,
        systemApp = false,
    )

    @AfterTest
    fun cleanup() {
        writtenIds.forEach { cache.deleteApp(it) }
    }

    @Test
    fun cleanupCacheDeletesOrphanedFiles() {
        val orphan = Uuid.random()
        writeCachedFile(orphan)

        cache.cleanupCache(allEntries = emptyList())

        assertFalse(SystemFileSystem.exists(pathFor(orphan, "1.0")))
    }

    @Test
    fun cleanupCacheKeepsAppsStillInLocker() {
        val owned = Uuid.random()
        writeCachedFile(owned)

        cache.cleanupCache(allEntries = listOf(basicProperties(owned)))

        assertTrue(SystemFileSystem.exists(pathFor(owned, "1.0")))
    }

    @Test
    fun cleanupCacheKeepsOwnedAppsEvenWhenNotSideloaded() {
        // Regression test: cleanupCache used to evict non-sideloaded (i.e. appstore/owned) apps
        // once the cache exceeded a fixed byte budget, silently defeating the offline-install
        // guarantee prefetching exists to provide. Only entries absent from the locker entirely
        // should ever be removed now.
        val owned = Uuid.random()
        writeCachedFile(owned)

        cache.cleanupCache(allEntries = listOf(basicProperties(owned, sideloaded = false)))

        assertTrue(SystemFileSystem.exists(pathFor(owned, "1.0")))
    }

    @Test
    fun cleanupCacheOnlyDeletesOrphansNotEverythingElse() {
        val owned = Uuid.random()
        val orphan = Uuid.random()
        writeCachedFile(owned)
        writeCachedFile(orphan)

        cache.cleanupCache(allEntries = listOf(basicProperties(owned)))

        assertTrue(SystemFileSystem.exists(pathFor(owned, "1.0")))
        assertFalse(SystemFileSystem.exists(pathFor(orphan, "1.0")))
    }
}
