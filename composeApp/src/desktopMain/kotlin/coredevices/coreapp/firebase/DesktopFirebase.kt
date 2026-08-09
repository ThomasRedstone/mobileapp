package coredevices.coreapp.firebase

import android.app.Application
import co.touchlab.kermit.Logger
import com.google.firebase.FirebasePlatform
import coredevices.util.AppDirs
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.FirebaseOptions
import dev.gitlive.firebase.initialize
import java.io.File

private val logger = Logger.withTag("DesktopFirebase")

/**
 * On Android a manifest-registered ContentProvider auto-initializes Firebase; on desktop nothing
 * does, so every `Firebase.auth` call would throw "Default FirebaseApp is not initialized".
 *
 * The JVM variant of the gitlive SDK is backed by `dev.gitlive:firebase-java-sdk`, which
 * reimplements the Android Firebase API on plain JVM. It needs two things Android provides for
 * free: a [FirebasePlatform] (its stand-in for SharedPreferences, so the signed-in session
 * survives a restart) and explicit [FirebaseOptions].
 *
 * Returns false if no real config could be found - the caller decides what to do about it.
 */
fun initializeFirebase(): Boolean {
    val source = findGoogleServicesJson()
    if (source == null) {
        logger.e {
            "No google-services.json found - Firebase is NOT initialized, so sign-in and any " +
                "screen touching Firebase.auth will fail. Looked at: ${searchedLocations()}"
        }
        return false
    }
    val config = try {
        parseGoogleServicesJson(source.read())
    } catch (e: GoogleServicesConfigException) {
        logger.e { "Firebase is NOT initialized: ${source.description} is unusable - ${e.message}" }
        return false
    }

    initializeFirebase(config, firebaseStateDir())
    logger.i { "Firebase initialized for project ${config.projectId} from ${source.description}" }
    return true
}

internal fun initializeFirebase(config: GoogleServicesConfig, stateDir: File) {
    FirebasePlatform.initializeFirebasePlatform(FileFirebasePlatform(stateDir))
    Firebase.initialize(
        context = Application(),
        options = FirebaseOptions(
            applicationId = config.applicationId,
            apiKey = config.apiKey,
            projectId = config.projectId,
            storageBucket = config.storageBucket,
            gcmSenderId = config.gcmSenderId,
        ),
    )
}

private class ConfigSource(val description: String, val read: () -> String)

private fun findGoogleServicesJson(): ConfigSource? {
    explicitConfigPath()?.let { path ->
        val file = File(path)
        if (!file.isFile) {
            logger.w { "$CONFIG_PATH_ENV points at $path, which does not exist" }
        } else {
            return ConfigSource(file.absolutePath) { file.readText() }
        }
    }
    val userConfig = File(configDir(), "google-services.json")
    if (userConfig.isFile) return ConfigSource(userConfig.absolutePath) { userConfig.readText() }

    // Embedded by composeApp's build when the developer's real google-services.json is present.
    val resource = ConfigSource::class.java.getResource("/google-services.json")
    if (resource != null) return ConfigSource("bundled google-services.json") { resource.readText() }
    return null
}

private const val CONFIG_PATH_ENV = "COREAPP_GOOGLE_SERVICES"

private fun explicitConfigPath() = System.getenv(CONFIG_PATH_ENV)?.takeIf { it.isNotBlank() }

private fun searchedLocations() = listOf(
    "\$$CONFIG_PATH_ENV",
    File(configDir(), "google-services.json").absolutePath,
    "the app's own resources",
).joinToString(", ")

private fun configDir(): File = AppDirs.configDir()

private fun firebaseStateDir(): File = AppDirs.dataDir("firebase")

/**
 * File-per-key rather than the app's `Settings`: the JVM SDK's default `Settings` is
 * `java.util.prefs`, whose 8KB per-value limit the persisted-user blob (ID + refresh tokens)
 * can exceed.
 */
internal class FileFirebasePlatform(private val dir: File) : FirebasePlatform() {
    private fun fileFor(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    override fun store(key: String, value: String) {
        dir.mkdirs()
        fileFor(key).writeText(value)
    }

    override fun retrieve(key: String): String? = fileFor(key).takeIf { it.isFile }?.readText()

    override fun clear(key: String) {
        fileFor(key).delete()
    }

    override fun log(msg: String) = logger.d { msg }
}
