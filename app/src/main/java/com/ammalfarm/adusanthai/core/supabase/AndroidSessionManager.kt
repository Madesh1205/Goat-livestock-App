package com.ammalfarm.adusanthai.core.supabase

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json

/**
 * Android SharedPreferences-backed SessionManager for Supabase Auth.
 * Automatically saves user authentication tokens and restores sessions across app restarts.
 */
class AndroidSessionManager(private val context: Context) : SessionManager {

    private val prefs: SharedPreferences by lazy {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun saveSession(session: UserSession) {
        try {
            val serialized = json.encodeToString(UserSession.serializer(), session)
            prefs.edit().putString(KEY_SAVED_SESSION, serialized).apply()
            Log.d(TAG, "User session successfully saved to persistent storage.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save user session: ${e.message}", e)
        }
    }

    override suspend fun loadSession(): UserSession? {
        val saved = prefs.getString(KEY_SAVED_SESSION, null) ?: return null
        return try {
            val session = json.decodeFromString(UserSession.serializer(), saved)
            Log.d(TAG, "User session successfully restored from persistent storage.")
            session
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse saved session, clearing invalid cache: ${e.message}", e)
            deleteSession()
            null
        }
    }

    override suspend fun deleteSession() {
        prefs.edit().remove(KEY_SAVED_SESSION).apply()
        Log.d(TAG, "User session cleared from persistent storage.")
    }

    companion object {
        private const val TAG = "AndroidSessionManager"
        private const val PREFS_NAME = "ammal_farm_supabase_auth_session"
        private const val KEY_SAVED_SESSION = "supabase_user_session_json"
    }
}
