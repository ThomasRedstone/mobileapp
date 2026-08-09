package coredevices.coreapp.firebase

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DesktopFirebaseTest {
    private val tempDir: File = Files.createTempDirectory("firebase-state").toFile()

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    // The bug this exists for: WatchSettingsScreen touches Firebase.auth on compose, which threw
    // "Default FirebaseApp is not initialized in this process" on desktop.
    @Test
    fun `Firebase auth is reachable once initialized`() {
        initializeFirebase(
            config = GoogleServicesConfig(
                projectId = "core-app-test",
                applicationId = "1:112233445566:android:abcdef",
                apiKey = "AIzaSyTestKey",
                storageBucket = "core-app-test.appspot.com",
                gcmSenderId = "112233445566",
            ),
            stateDir = tempDir,
        )

        assertNotNull(Firebase.auth)
    }

    @Test
    fun `stored state survives a new platform instance, as it must across app restarts`() {
        val key = "com.google.firebase.auth.FIREBASE_USER[DEFAULT]"

        FileFirebasePlatform(tempDir).store(key, "session-blob")

        assertEquals("session-blob", FileFirebasePlatform(tempDir).retrieve(key))
    }

    @Test
    fun `cleared state is gone and unknown keys read as null`() {
        val platform = FileFirebasePlatform(tempDir)
        platform.store("some.key", "value")

        platform.clear("some.key")

        assertNull(platform.retrieve("some.key"))
        assertNull(platform.retrieve("never.written"))
    }
}
