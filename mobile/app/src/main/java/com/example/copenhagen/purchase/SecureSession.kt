package com.example.copenhagen.purchase

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Tokens are encrypted with a non-exportable device key and excluded from backup. */
class SecureSession(context: Context) : ApiSession {
    private val preferences = context.getSharedPreferences("private_session", Context.MODE_PRIVATE)
    private val key: SecretKey
        get() {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            return (store.getKey("copenhagen-session", null) as? SecretKey)
                ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                    init(KeyGenParameterSpec.Builder("copenhagen-session",
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                    generateKey()
                }
        }
    override var tokens: JSONObject
        get() = try {
            val bytes = Base64.decode(preferences.getString("tokens", "")!!, Base64.NO_WRAP)
            if (bytes.size < 13) JSONObject() else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            }
        } catch (_: Exception) { JSONObject() }
        set(value) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encoded = cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
            preferences.edit().putString("tokens", Base64.encodeToString(encoded, Base64.NO_WRAP)).commit()
        }
    override val deviceId: String
        get() = preferences.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("deviceId", it).commit()
        }
    var route: String?
        get() = preferences.getString("route", null)
        set(value) { preferences.edit().putString("route", value).apply() }
    fun clear() { preferences.edit().remove("tokens").remove("route").commit() }
}
