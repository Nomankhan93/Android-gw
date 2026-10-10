package com.bulktext.gateway.identity

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureCredentialStore(context: Context) : CredentialStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("gateway_secure_prefs", Context.MODE_PRIVATE)

    @Synchronized
    override fun saveCredential(
        deviceId: String,
        credential: String,
        version: Int,
        expiresAt: Long,
        deviceName: String
    ): Boolean {
        val encryptedCredential = encrypt(credential)
        return prefs.edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_DEVICE_NAME, deviceName)
            .putString(KEY_ENCRYPTED_CREDENTIAL, encryptedCredential.ciphertext)
            .putString(KEY_IV, encryptedCredential.iv)
            .putInt(KEY_VERSION, version)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .commit()
    }

    @Synchronized
    override fun getDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)

    @Synchronized
    override fun getDeviceName(): String = prefs.getString(KEY_DEVICE_NAME, "Android Gateway") ?: "Android Gateway"

    @Synchronized
    override fun getCredential(): String? {
        val ciphertext = prefs.getString(KEY_ENCRYPTED_CREDENTIAL, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null
        return try {
            decrypt(ciphertext, iv)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun getCredentialVersion(): Int = prefs.getInt(KEY_VERSION, 1)

    @Synchronized
    override fun getExpiresAt(): Long = prefs.getLong(KEY_EXPIRES_AT, 0L)

    @Synchronized
    override fun clear(): Boolean {
        return prefs.edit().clear().commit()
    }

    private data class EncryptedData(val ciphertext: String, val iv: String)

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val keySpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(keySpec)
            return keyGenerator.generateKey()
        }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
        return entry.secretKey
    }

    private fun encrypt(plainText: String): EncryptedData {
        val secretKey = getSecretKey()
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return EncryptedData(
            ciphertext = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP),
            iv = Base64.encodeToString(iv, Base64.NO_WRAP)
        )
    }

    private fun decrypt(ciphertext: String, iv: String): String {
        val secretKey = getSecretKey()
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
        val decryptedBytes = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
        return String(decryptedBytes, Charsets.UTF_8)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "bulktext_gateway_key"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"

        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_ENCRYPTED_CREDENTIAL = "encrypted_credential"
        private const val KEY_IV = "credential_iv"
        private const val KEY_VERSION = "credential_version"
        private const val KEY_EXPIRES_AT = "expires_at"
    }
}
