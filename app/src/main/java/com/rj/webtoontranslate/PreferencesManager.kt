package com.rj.webtoontranslate

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "webtoon_translate_prefs")

/**
 * Persists user choices with DataStore so they survive the app (and the device)
 * being restarted -- not just backgrounded.
 */
class PreferencesManager(private val context: Context) {

    companion object {
        private val TARGET_LANGUAGE = stringPreferencesKey("target_language_code")
    }

    val targetLanguageCode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[TARGET_LANGUAGE] ?: LanguageOptions.DEFAULT_TARGET_CODE
    }

    suspend fun setTargetLanguage(code: String) {
        context.dataStore.edit { it[TARGET_LANGUAGE] = code }
    }
}
