package coredevices.coreapp.firebase

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The subset of `google-services.json` the Firebase JVM SDK needs to build its `FirebaseOptions`.
 *
 * Android gets these injected as resources by the google-services Gradle plugin, and iOS reads
 * `GoogleService-Info.plist`. Neither applies on desktop, so the same file the Android build
 * already uses is parsed at runtime instead.
 */
data class GoogleServicesConfig(
    val projectId: String,
    val applicationId: String,
    val apiKey: String,
    val storageBucket: String?,
    val gcmSenderId: String?,
)

const val ANDROID_PACKAGE_NAME = "coredevices.coreapp"

// The committed google-services-dummy.json fills every field with this. Parsing it would
// "succeed" and then fail much later inside Firebase with an opaque error.
private const val PLACEHOLDER = "replaceme"

class GoogleServicesConfigException(message: String) : Exception(message)

fun parseGoogleServicesJson(
    json: String,
    packageName: String = ANDROID_PACKAGE_NAME,
): GoogleServicesConfig {
    val root = try {
        Json.parseToJsonElement(json).jsonObject
    } catch (e: Exception) {
        throw GoogleServicesConfigException("not valid JSON: ${e.message}")
    }
    val projectInfo = root["project_info"]?.jsonObject
        ?: throw GoogleServicesConfigException("no project_info object")
    val clients = root["client"]?.jsonArray
        ?: throw GoogleServicesConfigException("no client array")
    val client = clients.firstOrNull {
        it.jsonObject["client_info"]?.jsonObject
            ?.get("android_client_info")?.jsonObject
            ?.get("package_name")?.jsonPrimitive?.contentOrNull == packageName
    }?.jsonObject ?: throw GoogleServicesConfigException("no client entry for package $packageName")

    val clientInfo = client["client_info"]?.jsonObject
        ?: throw GoogleServicesConfigException("client entry has no client_info")
    val apiKey = client["api_key"]?.jsonArray?.firstOrNull()
        ?.jsonObject?.get("current_key")?.jsonPrimitive?.contentOrNull

    return GoogleServicesConfig(
        projectId = projectInfo.required("project_id"),
        applicationId = clientInfo.required("mobilesdk_app_id"),
        apiKey = apiKey.require("api_key[0].current_key"),
        storageBucket = projectInfo.optional("storage_bucket"),
        gcmSenderId = projectInfo.optional("project_number"),
    )
}

private fun kotlinx.serialization.json.JsonObject.required(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull.require(key)

private fun kotlinx.serialization.json.JsonObject.optional(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != PLACEHOLDER }

private fun String?.require(name: String): String {
    if (this.isNullOrBlank()) throw GoogleServicesConfigException("$name is missing or empty")
    if (this == PLACEHOLDER) {
        throw GoogleServicesConfigException(
            "$name is \"$PLACEHOLDER\" - this is the committed dummy config, not a real one"
        )
    }
    return this
}
