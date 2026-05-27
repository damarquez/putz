package com.damarquez.putz.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.damarquez.putz.security.SecureStorage
import com.damarquez.putz.ui.theme.AppCategory
import com.damarquez.putz.ui.theme.AppMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val secureStorage: SecureStorage,
) {
    // Auth token lives in EncryptedSharedPreferences via SecureStorage.
    // StateFlow so callers get the current value on first collection, no null-initial workaround needed.
    val authTokenFlow: StateFlow<String> get() = secureStorage.authTokenFlow

    val googleTokenFlow: StateFlow<String> get() = secureStorage.googleTokenFlow

    val oauthClientIdFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[AppSettingsKeys.OAUTH_CLIENT_ID] ?: ""
    }

    val appCategoryFlow: Flow<AppCategory> = dataStore.data.map { prefs ->
        when (prefs[AppSettingsKeys.APP_CATEGORY]) {
            AppCategory.EINK.name -> AppCategory.EINK
            else -> AppCategory.NORMAL
        }
    }

    val appModeFlow: Flow<AppMode> = dataStore.data.map { prefs ->
        when (prefs[AppSettingsKeys.APP_MODE]) {
            AppMode.LIGHT.name -> AppMode.LIGHT
            AppMode.DARK.name -> AppMode.DARK
            else -> AppMode.SYSTEM
        }
    }

    val googleWebClientIdFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[AppSettingsKeys.GOOGLE_WEB_CLIENT_ID] ?: ""
    }

    val libraryHasUpdatesFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[AppSettingsKeys.LIBRARY_HAS_UPDATES] ?: false
    }

    val lastSyncTimestampFlow: Flow<Long> = dataStore.data.map { prefs ->
        prefs[AppSettingsKeys.LAST_SYNC_TIMESTAMP] ?: 0L
    }

    val daemonStatusFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[AppSettingsKeys.DAEMON_STATUS]
    }

    val putioLocalLanConnectionIdFlow: Flow<Long?> = dataStore.data.map { prefs ->
        prefs[AppSettingsKeys.PUTIO_LOCAL_LAN_CONNECTION_ID]
    }

    val putioLocalLanPathFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[AppSettingsKeys.PUTIO_LOCAL_LAN_PATH] ?: ""
    }

    val plexLibraryLanConnectionIdFlow: Flow<Long?> = dataStore.data.map { prefs ->
        prefs[AppSettingsKeys.PLEX_LIBRARY_LAN_CONNECTION_ID]
    }

    val plexLibraryLanPathFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[AppSettingsKeys.PLEX_LIBRARY_LAN_PATH] ?: ""
    }

    val lanEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[AppSettingsKeys.LAN_ENABLED] ?: false
    }

    val lanHostFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[AppSettingsKeys.LAN_HOST] ?: ""
    }

    val lanPortFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[AppSettingsKeys.LAN_PORT] ?: 9090
    }

    val lanApiKeyFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[AppSettingsKeys.LAN_API_KEY] ?: ""
    }

    fun saveAuthToken(token: String) = secureStorage.saveAuthToken(token)

    fun clearAuth() = secureStorage.clearAuthToken()

    fun saveGoogleToken(token: String) = secureStorage.saveGoogleToken(token)

    fun clearGoogleToken() = secureStorage.clearGoogleToken()

    suspend fun saveOauthClientId(clientId: String) {
        dataStore.edit { prefs ->
            if (clientId.isBlank()) prefs.remove(AppSettingsKeys.OAUTH_CLIENT_ID)
            else prefs[AppSettingsKeys.OAUTH_CLIENT_ID] = clientId.trim()
        }
    }

    suspend fun saveAppCategory(category: AppCategory) {
        dataStore.edit { prefs -> prefs[AppSettingsKeys.APP_CATEGORY] = category.name }
    }

    suspend fun saveAppMode(mode: AppMode) {
        dataStore.edit { prefs -> prefs[AppSettingsKeys.APP_MODE] = mode.name }
    }

    suspend fun saveGoogleWebClientId(clientId: String) {
        dataStore.edit { prefs ->
            if (clientId.isBlank()) prefs.remove(AppSettingsKeys.GOOGLE_WEB_CLIENT_ID)
            else prefs[AppSettingsKeys.GOOGLE_WEB_CLIENT_ID] = clientId.trim()
        }
    }

    suspend fun saveLibraryHasUpdates(hasUpdates: Boolean) {
        dataStore.edit { prefs -> prefs[AppSettingsKeys.LIBRARY_HAS_UPDATES] = hasUpdates }
    }

    suspend fun saveLastSyncTimestamp(timestamp: Long) {
        dataStore.edit { prefs -> prefs[AppSettingsKeys.LAST_SYNC_TIMESTAMP] = timestamp }
    }

    suspend fun saveDaemonStatus(status: String?) {
        dataStore.edit { prefs ->
            if (status == null) prefs.remove(AppSettingsKeys.DAEMON_STATUS)
            else prefs[AppSettingsKeys.DAEMON_STATUS] = status
        }
    }

    suspend fun savePutioLocalLanConnectionId(id: Long?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(AppSettingsKeys.PUTIO_LOCAL_LAN_CONNECTION_ID)
            else prefs[AppSettingsKeys.PUTIO_LOCAL_LAN_CONNECTION_ID] = id
        }
    }

    suspend fun savePutioLocalLanPath(path: String) {
        dataStore.edit { prefs ->
            if (path.isBlank()) prefs.remove(AppSettingsKeys.PUTIO_LOCAL_LAN_PATH)
            else prefs[AppSettingsKeys.PUTIO_LOCAL_LAN_PATH] = path.trim()
        }
    }

    suspend fun savePlexLibraryLanConnectionId(id: Long?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(AppSettingsKeys.PLEX_LIBRARY_LAN_CONNECTION_ID)
            else prefs[AppSettingsKeys.PLEX_LIBRARY_LAN_CONNECTION_ID] = id
        }
    }

    suspend fun savePlexLibraryLanPath(path: String) {
        dataStore.edit { prefs ->
            if (path.isBlank()) prefs.remove(AppSettingsKeys.PLEX_LIBRARY_LAN_PATH)
            else prefs[AppSettingsKeys.PLEX_LIBRARY_LAN_PATH] = path.trim()
        }
    }

    suspend fun saveLanEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[AppSettingsKeys.LAN_ENABLED] = enabled }
    }

    suspend fun saveLanHost(host: String) {
        dataStore.edit { prefs ->
            if (host.isBlank()) prefs.remove(AppSettingsKeys.LAN_HOST)
            else prefs[AppSettingsKeys.LAN_HOST] = host.trim()
        }
    }

    suspend fun saveLanPort(port: Int) {
        dataStore.edit { prefs -> prefs[AppSettingsKeys.LAN_PORT] = port }
    }

    suspend fun saveLanApiKey(key: String) {
        dataStore.edit { prefs ->
            if (key.isBlank()) prefs.remove(AppSettingsKeys.LAN_API_KEY)
            else prefs[AppSettingsKeys.LAN_API_KEY] = key.trim()
        }
    }

    val historyFileIdFlow: Flow<Long?> = dataStore.data.map { prefs ->
        prefs[AppSettingsKeys.TRANSFER_HISTORY_FILE_ID]
    }

    suspend fun saveHistoryFileId(id: Long?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(AppSettingsKeys.TRANSFER_HISTORY_FILE_ID)
            else prefs[AppSettingsKeys.TRANSFER_HISTORY_FILE_ID] = id
        }
    }

    suspend fun getOrCreateAppId(): String {
        val existing = dataStore.data.map { it[AppSettingsKeys.APP_ID] }.first()
        if (!existing.isNullOrBlank()) return existing
        val newId = UUID.randomUUID().toString()
        dataStore.edit { it[AppSettingsKeys.APP_ID] = newId }
        return newId
    }
}
