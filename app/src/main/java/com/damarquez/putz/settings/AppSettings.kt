package com.damarquez.putz.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "putz_settings")

object AppSettingsKeys {
    val OAUTH_CLIENT_ID = stringPreferencesKey("oauth_client_id")
    val APP_CATEGORY = stringPreferencesKey("app_category")
    val APP_MODE = stringPreferencesKey("app_mode")
    val GOOGLE_WEB_CLIENT_ID = stringPreferencesKey("google_web_client_id")
    val LIBRARY_HAS_UPDATES = booleanPreferencesKey("library_has_updates")
    val LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")
    val DAEMON_STATUS = stringPreferencesKey("daemon_status")
    // put.io local sync — which LAN connection hosts the local repository and at what path
    val PUTIO_LOCAL_LAN_CONNECTION_ID = longPreferencesKey("putio_local_lan_connection_id")
    val PUTIO_LOCAL_LAN_PATH = stringPreferencesKey("putio_local_lan_path")
    // Plex library — which LAN connection + root path hosts the Plex Movies library
    val PLEX_LIBRARY_LAN_CONNECTION_ID = longPreferencesKey("plex_library_lan_connection_id")
    val PLEX_LIBRARY_LAN_PATH = stringPreferencesKey("plex_library_lan_path")
    // Plexamp (music) library — which LAN connection + root path hosts the music library
    val PLEXAMP_LIBRARY_LAN_CONNECTION_ID = longPreferencesKey("plexamp_library_lan_connection_id")
    val PLEXAMP_LIBRARY_LAN_PATH = stringPreferencesKey("plexamp_library_lan_path")
    // Direct LAN / Tailscale daemon access
    val LAN_ENABLED = booleanPreferencesKey("lan_enabled")
    val LAN_HOST = stringPreferencesKey("lan_host")
    val LAN_PORT = intPreferencesKey("lan_port")
    val LAN_API_KEY = stringPreferencesKey("lan_api_key")
    // Jackett proxy for general torrent search (Find Content tab)
    val JACKETT_BASE_URL = stringPreferencesKey("jackett_base_url")
    val JACKETT_API_KEY = stringPreferencesKey("jackett_api_key")
    // Stable per-installation ID so the daemon can route responses back to the right device
    val APP_ID = stringPreferencesKey("app_id")
    // put.io file ID of the transfer history JSON uploaded by the daemon
    val TRANSFER_HISTORY_FILE_ID = longPreferencesKey("transfer_history_file_id")
    // Last successfully fetched history JSON — survives process death so the screen
    // never shows an error on first open after restart while awaiting a fresh heartbeat
    val TRANSFER_HISTORY_CACHE = stringPreferencesKey("transfer_history_cache")
    // CONTRACT: self-update — the last versionCode this install announced itself as, so it can
    // show a one-time "Updated to X" message on the first launch after a self-update installs.
    val LAST_ANNOUNCED_VERSION_CODE = intPreferencesKey("last_announced_version_code")
}
