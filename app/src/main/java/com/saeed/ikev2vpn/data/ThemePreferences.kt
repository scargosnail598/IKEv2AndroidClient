package com.saeed.ikev2vpn.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.themeDataStore by preferencesDataStore(name = "theme_preferences")

class ThemePreferences(context: Context) {
    private val applicationContext = context.applicationContext

    val darkModeOverride: Flow<Boolean?> = applicationContext.themeDataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences -> preferences[DARK_MODE] }

    suspend fun setDarkMode(enabled: Boolean) {
        applicationContext.themeDataStore.edit { preferences ->
            preferences[DARK_MODE] = enabled
        }
    }

    private companion object {
        val DARK_MODE = booleanPreferencesKey("dark_mode")
    }
}
