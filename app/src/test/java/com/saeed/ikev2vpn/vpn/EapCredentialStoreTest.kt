package com.saeed.ikev2vpn.vpn

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.*
import javax.crypto.spec.SecretKeySpec

class EapCredentialStoreTest {
    private val values = mutableMapOf<String, String>()
    private val context = mock(Context::class.java)
    private val preferences = mock(SharedPreferences::class.java)
    private val editor = mock(SharedPreferences.Editor::class.java)
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    private fun store(): EapCredentialStore {
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSharedPreferences("encrypted_eap_credential", Context.MODE_PRIVATE)).thenReturn(preferences)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(preferences.getString(anyString(), isNull())).thenAnswer { values[it.getArgument<String>(0)] }
        `when`(editor.putString(anyString(), anyString())).thenAnswer {
            values[it.getArgument<String>(0)] = it.getArgument(1)
            editor
        }
        `when`(editor.remove(anyString())).thenAnswer {
            values.remove(it.getArgument<String>(0))
            editor
        }
        `when`(editor.commit()).thenReturn(true)
        return EapCredentialStore(context) { key }
    }

    @Test
    fun `profile passwords survive recreation and clearing active credentials`() {
        val store = store()
        store.save("password-a", "a")
        store.save("password-b", "b")
        store.save("active-password")
        store.clear()
        val recreated = store()
        assertNull(recreated.load())
        assertEquals("password-a", recreated.load("a"))
        assertEquals("password-b", recreated.load("b"))
        assertNull(recreated.load("missing"))
        assertFalse(values.values.any { it.contains("password") })
    }

    @Test
    fun `editing and removing one password preserve other profile credentials`() {
        val store = store()
        store.save("first", "a")
        store.save("second", "b")
        store.save("updated", "a")
        assertEquals("updated", store.load("a"))
        assertEquals("second", store.load("b"))
        store.clear("a")
        assertNull(store.load("a"))
        assertEquals("second", store.load("b"))
    }

    @Test
    fun `legacy active credential can be retained before provisioning another profile`() {
        val store = store()
        store.save("legacy-password")
        store.save(store.load()!!, "legacy")
        store.save("new-password")
        assertEquals("legacy-password", store.load("legacy"))
        assertEquals("new-password", store.load())
    }
}
