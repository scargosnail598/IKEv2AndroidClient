package com.saeed.ikev2vpn.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.themeDataStore by preferencesDataStore(name = "theme_preferences")

class ThemePreferences(context: Context) {
    private val applicationContext = context.applicationContext

    private val preferences = applicationContext.themeDataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }

    val darkModeOverride: Flow<Boolean?> = preferences.map { it[DARK_MODE] }
    val theme: Flow<AppTheme> = preferences.map { AppTheme.fromId(it[THEME]) }

    suspend fun setTheme(theme: AppTheme) {
        applicationContext.themeDataStore.edit { preferences ->
            preferences[THEME] = theme.id
        }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        applicationContext.themeDataStore.edit { preferences ->
            preferences[DARK_MODE] = enabled
        }
    }

    private companion object {
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val THEME = stringPreferencesKey("theme")
    }
}
