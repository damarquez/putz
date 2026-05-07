package com.damarquez.putz.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences = createPrefs()

    // StateFlow so consumers get the current value immediately without an initial = null trick
    private val _authToken = MutableStateFlow(prefs.getString(KEY_AUTH_TOKEN, "") ?: "")
    val authTokenFlow: StateFlow<String> = _authToken.asStateFlow()

    private val _googleToken = MutableStateFlow(prefs.getString(KEY_GOOGLE_TOKEN, "") ?: "")
    val googleTokenFlow: StateFlow<String> = _googleToken.asStateFlow()

    fun saveAuthToken(token: String) {
        prefs.edit().putString(KEY_AUTH_TOKEN, token).apply()
        _authToken.value = token
    }

    fun clearAuthToken() {
        prefs.edit().remove(KEY_AUTH_TOKEN).apply()
        _authToken.value = ""
    }

    fun saveGoogleToken(token: String) {
        prefs.edit().putString(KEY_GOOGLE_TOKEN, token).apply()
        _googleToken.value = token
    }

    fun clearGoogleToken() {
        prefs.edit().remove(KEY_GOOGLE_TOKEN).apply()
        _googleToken.value = ""
    }

    private fun createPrefs(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // Keystore can become unavailable after OS security updates on some Samsung devices.
            // Log the failure and fall back to plain prefs — the token will need to be re-entered.
            Log.e(TAG, "EncryptedSharedPreferences unavailable, falling back to plain prefs", e)
            context.getSharedPreferences("${FILE_NAME}_fallback", Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val TAG = "SecureStorage"
        private const val FILE_NAME = "putz_secure"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_GOOGLE_TOKEN = "google_token"
    }
}
