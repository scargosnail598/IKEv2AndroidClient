package com.saeed.ikev2vpn.data

import android.content.Context
import androidx.datastore.preferences.core.*
import com.saeed.ikev2vpn.certificate.CertificateLoader
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.lang.reflect.InvocationTargetException

class ProfileManagementPersistenceTest {
    private fun repository(): DataStoreProfileRepository {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        return DataStoreProfileRepository(context, CertificateLoader())
    }
    private val repository = repository()
    private val routing = AppRoutingConfig(AppRoutingMode.EXCLUDE_SELECTED, setOf("com.example.bank"))
    private fun profile(id: String) = mutablePreferencesOf().apply {
        this[stringPreferencesKey("profile_id")] = id
        this[stringPreferencesKey("profile_name")] = "Name $id"
        this[stringPreferencesKey("server_address")] = "$id.example.com"
        this[stringPreferencesKey("username")] = "user-$id"
        this[stringPreferencesKey("app_routing")] = routing.encode()
        this[stringPreferencesKey("provisioning_status")] = ProvisioningStatus.PROVISIONED.name
    }
    private fun state(): MutablePreferences {
        val preferences = profile("first")
        DataStoreProfileRepository::class.java.getDeclaredMethod("archiveCurrent", MutablePreferences::class.java, String::class.java).apply { isAccessible = true }
            .invoke(repository, preferences, "second")
        profile("second").asMap().forEach { (key, value) ->
            @Suppress("UNCHECKED_CAST")
            preferences[key as Preferences.Key<String>] = value as String
        }
        return preferences
    }
    private fun change(preferences: MutablePreferences, id: String, name: String?) {
        DataStoreProfileRepository::class.java.getDeclaredMethod("updateStoredProfile", MutablePreferences::class.java, String::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(repository, preferences, id, name)
    }
    private fun snapshot(preferences: Preferences): RepositorySnapshot = DataStoreProfileRepository::class.java
        .getDeclaredMethod("toSnapshot", Preferences::class.java).apply { isAccessible = true }.invoke(repository, preferences) as RepositorySnapshot

    @Test
    fun `rename current and archived profiles preserves identity routing and selected profile across reloads`() {
        val preferences = state()
        change(preferences, "first", "Work")
        change(preferences, "second", "Personal")
        val state = snapshot(preferences)
        assertEquals("second", state.profile!!.config.id)
        assertEquals("Personal", state.profile!!.config.profileName)
        assertEquals("Work", state.profiles.first { it.config.id == "first" }.config.profileName)
        for (profile in state.profiles) {
            assertEquals(routing, profile.config.appRouting)
            assertEquals("${profile.config.id}.example.com", profile.config.serverAddress)
            assertEquals("user-${profile.config.id}", profile.config.username)
        }
    }

    @Test
    fun `deleting an archived profile leaves the current profile unchanged`() {
        val preferences = state()
        val current = snapshot(preferences).profile
        change(preferences, "first", null)
        assertEquals(current, snapshot(preferences).profile)
        assertEquals(listOf("second"), snapshot(preferences).profiles.map { it.config.id })
    }

    @Test
    fun `deleting the selected profile keeps remaining profiles without silently selecting one`() {
        val preferences = state()
        change(preferences, "second", null)
        val remaining = snapshot(preferences)
        assertNull(remaining.profile)
        assertEquals(listOf("first"), remaining.profiles.map { it.config.id })
        assertEquals(routing, remaining.profiles.single().config.appRouting)
        change(preferences, "first", null)
        assertTrue(snapshot(preferences).profiles.isEmpty())
        assertNull(snapshot(preferences).diagnostics.repositoryError)
    }

    @Test
    fun `pending setup blocks rename and deletion without partial changes`() {
        val preferences = state().apply {
            this[stringPreferencesKey("pending_profile_name")] = "Pending"
            this[stringPreferencesKey("pending_server_address")] = "pending.example.com"
            this[stringPreferencesKey("pending_username")] = "user"
        }
        val before = preferences.asMap().toMap()
        for (name in listOf("New name", null)) {
            try { change(preferences, "first", name); fail("Pending setup must block changes") }
            catch (exception: InvocationTargetException) { assertTrue(exception.cause is IllegalStateException) }
            assertEquals(before, preferences.asMap())
        }
    }
}
