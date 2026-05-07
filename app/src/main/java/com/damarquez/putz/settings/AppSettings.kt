package com.damarquez.putz.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "putz_settings")

object AppSettingsKeys {
    val OAUTH_CLIENT_ID = stringPreferencesKey("oauth_client_id")
    val APP_CATEGORY = stringPreferencesKey("app_category")
    val APP_MODE = stringPreferencesKey("app_mode")
    val GOOGLE_WEB_CLIENT_ID = stringPreferencesKey("google_web_client_id")
}
