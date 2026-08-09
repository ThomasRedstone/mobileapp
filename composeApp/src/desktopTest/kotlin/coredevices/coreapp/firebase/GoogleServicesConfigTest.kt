package coredevices.coreapp.firebase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleServicesConfigTest {

    private fun json(
        projectId: String = "core-app-1234",
        projectNumber: String = "112233445566",
        storageBucket: String? = "core-app-1234.appspot.com",
        appId: String = "1:112233445566:android:abcdef",
        apiKey: String? = "AIzaSyRealLookingKey",
        packageName: String = ANDROID_PACKAGE_NAME,
    ) = """
        {
          "project_info": {
            "project_number": "$projectNumber",
            "project_id": "$projectId"
            ${if (storageBucket != null) ""","storage_bucket": "$storageBucket"""" else ""}
          },
          "client": [
            {
              "client_info": {
                "mobilesdk_app_id": "$appId",
                "android_client_info": { "package_name": "$packageName" }
              },
              "api_key": [ ${if (apiKey != null) """{ "current_key": "$apiKey" }""" else ""} ]
            }
          ],
          "configuration_version": "1"
        }
    """.trimIndent()

    @Test
    fun `parses a real config`() {
        val config = parseGoogleServicesJson(json())

        assertEquals("core-app-1234", config.projectId)
        assertEquals("1:112233445566:android:abcdef", config.applicationId)
        assertEquals("AIzaSyRealLookingKey", config.apiKey)
        assertEquals("core-app-1234.appspot.com", config.storageBucket)
        assertEquals("112233445566", config.gcmSenderId)
    }

    @Test
    fun `optional fields are null when absent`() {
        assertNull(parseGoogleServicesJson(json(storageBucket = null)).storageBucket)
    }

    @Test
    fun `picks the client entry matching the app's package`() {
        val multiClient = """
            {
              "project_info": { "project_id": "p", "project_number": "1" },
              "client": [
                {
                  "client_info": {
                    "mobilesdk_app_id": "other-app",
                    "android_client_info": { "package_name": "com.example.other" }
                  },
                  "api_key": [ { "current_key": "other-key" } ]
                },
                {
                  "client_info": {
                    "mobilesdk_app_id": "our-app",
                    "android_client_info": { "package_name": "$ANDROID_PACKAGE_NAME" }
                  },
                  "api_key": [ { "current_key": "our-key" } ]
                }
              ]
            }
        """.trimIndent()

        val config = parseGoogleServicesJson(multiClient)

        assertEquals("our-app", config.applicationId)
        assertEquals("our-key", config.apiKey)
    }

    @Test
    fun `rejects a config with no entry for our package`() {
        assertFailsWith<GoogleServicesConfigException> {
            parseGoogleServicesJson(json(packageName = "com.example.other"))
        }
    }

    @Test
    fun `rejects the committed dummy config rather than initializing with placeholders`() {
        val dummy = json(projectId = "replaceme", appId = "replaceme", apiKey = "replaceme")

        val error = assertFailsWith<GoogleServicesConfigException> { parseGoogleServicesJson(dummy) }

        assertTrue(error.message!!.contains("dummy"), "unexpected message: ${error.message}")
    }

    @Test
    fun `rejects a missing api key`() {
        assertFailsWith<GoogleServicesConfigException> { parseGoogleServicesJson(json(apiKey = null)) }
    }

    @Test
    fun `rejects malformed json`() {
        assertFailsWith<GoogleServicesConfigException> { parseGoogleServicesJson("not json at all") }
    }
}
