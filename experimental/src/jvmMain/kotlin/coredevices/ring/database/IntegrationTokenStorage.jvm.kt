package coredevices.ring.database

import coredevices.ring.storage.DesktopSecureStore
import coredevices.util.integrations.IntegrationTokenStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual class IntegrationTokenStorageImpl : IntegrationTokenStorage {

    private val store = DesktopSecureStore("integration-tokens.properties")

    actual override suspend fun saveToken(key: String, token: String) = withContext(Dispatchers.IO) {
        store.put(key, token)
    }

    actual override suspend fun getToken(key: String): String? = withContext(Dispatchers.IO) {
        store.get(key)
    }

    actual override suspend fun deleteToken(key: String) = withContext(Dispatchers.IO) {
        store.remove(key)
    }
}
