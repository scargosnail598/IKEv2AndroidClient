package com.saeed.ikev2vpn.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.saeed.ikev2vpn.certificate.CertificateLoader
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class AppRoutingPersistenceTest {
    private fun repository(): DataStoreProfileRepository {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        return DataStoreProfileRepository(context, CertificateLoader())
    }

    private fun snapshot(repository: DataStoreProfileRepository, preferences: Preferences): RepositorySnapshot =
        DataStoreProfileRepository::class.java.getDeclaredMethod("toSnapshot", Preferences::class.java).apply {
            isAccessible = true
        }.invoke(repository, preferences) as RepositorySnapshot

    private fun profilePreferences(id: String, routing: AppRoutingConfig? = null) = mutablePreferencesOf().apply {
        this[stringPreferencesKey("profile_id")] = id
        this[stringPreferencesKey("profile_name")] = id
        this[stringPreferencesKey("server_address")] = "vpn.example.com"
        this[stringPreferencesKey("username")] = "user"
        this[stringPreferencesKey("provisioning_status")] = ProvisioningStatus.PROVISIONED.name
        routing?.let { this[stringPreferencesKey("app_routing")] = it.encode() }
    }

    @Test
    fun `legacy profile loads with all apps`() {
        assertEquals(AppRoutingConfig(), snapshot(repository(), profilePreferences("legacy")).profile!!.config.appRouting)
    }

    @Test
    fun `archiving preserves each profiles routing across reloads`() {
        val repository = repository()
        val only = AppRoutingConfig(AppRoutingMode.ONLY_SELECTED, setOf("com.example.browser"))
        val exclude = AppRoutingConfig(AppRoutingMode.EXCLUDE_SELECTED, setOf("com.example.bank"))
        val preferences = profilePreferences("first", only)
        DataStoreProfileRepository::class.java.getDeclaredMethod("archiveCurrent", MutablePreferences::class.java, String::class.java).apply {
            isAccessible = true
        }.invoke(repository, preferences, "second")
        profilePreferences("second", exclude).asMap().forEach { (key, value) ->
            @Suppress("UNCHECKED_CAST")
            preferences[key as Preferences.Key<String>] = value as String
        }
        val reloaded = snapshot(repository, preferences)
        assertEquals(exclude, reloaded.profile!!.config.appRouting)
        assertEquals(only, reloaded.profiles.first { it.config.id == "first" }.config.appRouting)
    }

    @Test
    fun `pending routing remains separate from committed routing`() {
        val committed = AppRoutingConfig(AppRoutingMode.EXCLUDE_SELECTED, setOf("com.example.bank"))
        val pending = AppRoutingConfig(AppRoutingMode.ONLY_SELECTED, setOf("com.example.browser"))
        val preferences = profilePreferences("first", committed)
        listOf("profile_id" to "second", "profile_name" to "Second", "server_address" to "vpn.example.com",
            "username" to "user", "app_routing" to pending.encode()).forEach { (key, value) ->
            preferences[stringPreferencesKey("pending_$key")] = value
        }
        val reloaded = snapshot(repository(), preferences)
        assertEquals(committed, reloaded.profile!!.config.appRouting)
        assertEquals(pending, reloaded.pendingProfile!!.config.appRouting)
    }

    @Test
    fun `corrupted stored routing cannot silently revert to all apps`() {
        val preferences = profilePreferences("first")
        preferences[stringPreferencesKey("app_routing")] = "{\"mode\":\"UNKNOWN\",\"packages\":[]}"
        val reloaded = snapshot(repository(), preferences)
        assertNull(reloaded.profile)
        assertTrue(reloaded.diagnostics.repositoryError.orEmpty().contains("per-app"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `stored empty allowlist is rejected`() {
        AppRoutingConfig.decode("{\"mode\":\"ONLY_SELECTED\",\"packages\":[]}")
    }
}
