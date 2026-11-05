package com.taskra.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
internal data class SyncCredentials(val endpoint: String, val deviceId: String, val token: String)

/** Keystore 中的密钥不导出；设备令牌仅以 AES-GCM 密文保存在应用私有存储。 */
internal class SyncCredentialStore(context: Context, namespace: String = "sync_credentials") {
    private val preferences = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private val alias = "${context.packageName}.$namespace"
    private companion object { val lock = Any() }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore()
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        // 已有密文时不能悄悄换密钥，否则原设备凭据会无法恢复。
        check(!preferences.contains("sealed")) { "同步密钥不可用，需要重新配对" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    fun save(value: SyncCredentials) = synchronized(lock) {
        require(value.endpoint.startsWith("https://") && value.deviceId.isNotBlank() && value.token.isNotBlank())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        check(cipher.iv.size == 12) { "同步凭据加密失败" }
        val encrypted = cipher.doFinal(Json.encodeToString(value).toByteArray(Charsets.UTF_8))
        val bytes = cipher.iv + encrypted
        check(preferences.edit().putString("sealed", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) {
            "保存设备凭据失败"
        }
    }

    fun load(): SyncCredentials? = synchronized(lock) {
        val encoded = preferences.getString("sealed", null) ?: return@synchronized null
        try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > 12 + 16)
            val key = keyStore().getKey(alias, null) as? SecretKey ?: error("missing key")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            Json.decodeFromString<SyncCredentials>(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw IllegalStateException("同步凭据不可用，需要重新配对", e)
        }
    }

    /** 配对尝试确认后清除密文；不删除 Keystore 密钥。 */
    fun clear() = synchronized(lock) {
        check(preferences.edit().remove("sealed").commit()) { "无法清除已完成的配对尝试" }
    }
}
