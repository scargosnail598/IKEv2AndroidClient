package com.saeed.ikev2vpn.vpn

import android.content.Context
import java.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the EAP password encrypted with an app-specific Android Keystore key. */
class EapCredentialStore(
    context: Context,
    private val keyProvider: (() -> SecretKey)? = null,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun save(password: String, profileId: String? = null) {
        require(password.isNotEmpty()) { "The EAP password must not be empty." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider?.invoke() ?: getOrCreateKey())
        val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        check(
            preferences.edit()
                .putString(key(KEY_IV, profileId), Base64.getEncoder().encodeToString(cipher.iv))
                .putString(key(KEY_CIPHERTEXT, profileId), Base64.getEncoder().encodeToString(ciphertext))
                .commit(),
        ) { "The encrypted EAP credential could not be saved." }
    }

    @Synchronized
    fun load(profileId: String? = null): String? {
        val encodedIv = preferences.getString(key(KEY_IV, profileId), null)
        val encodedCiphertext = preferences.getString(key(KEY_CIPHERTEXT, profileId), null)
        if (encodedIv == null && encodedCiphertext == null) return null
        check(encodedIv != null && encodedCiphertext != null) {
            "The encrypted EAP credential is incomplete. Save the profile again."
        }

        val key = keyProvider?.invoke() ?: (loadKeyStore().getKey(KEY_ALIAS, null) as? SecretKey
            ?: error("The Android Keystore key is unavailable. Save the profile again."))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.getDecoder().decode(encodedIv)),
        )
        return cipher.doFinal(Base64.getDecoder().decode(encodedCiphertext))
            .toString(Charsets.UTF_8)
    }

    @Synchronized
    fun clear(profileId: String? = null) {
        check(preferences.edit().remove(key(KEY_IV, profileId)).remove(key(KEY_CIPHERTEXT, profileId)).commit()) {
            "The encrypted EAP credential could not be removed."
        }
    }

    private fun key(name: String, profileId: String?): String =
        if (profileId == null) name else "profile.$profileId.$name"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = loadKeyStore()
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.saeed.ikev2vpn.eap-credential"
        const val PREFERENCES_NAME = "encrypted_eap_credential"
        const val KEY_IV = "iv"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
