package com.morchid.ecardledger.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 里生成的一把 AES 密钥加密学号密码，密文存 SharedPreferences。
 * 密钥由系统密钥库保管、不出应用，磁盘上不会出现明文密码。
 */
class CredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences("ecard_credentials", Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /**
     * 加密保存凭据。
     *
     * @return true 表示已保存；Keystore 不可用或加密失败时返回 false，**不抛异常**。
     *   这一点很关键：保存凭据失败不该把已经成功的登录流程一起带崩
     *   （登录本身只依赖网络，和密钥库可用性无关）。
     */
    fun save(username: String, password: String): Boolean = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(ct, Base64.NO_WRAP))
            .apply()
        true
    }.getOrDefault(false)

    /** @return 学号 to 密码；没有或解不开则返回 null */
    fun load(): Pair<String, String>? {
        val username = prefs.getString(KEY_USERNAME, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val ct = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val password = String(
                cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)),
                Charsets.UTF_8,
            )
            username to password
        }.getOrNull()
    }

    fun hasCredentials(): Boolean = prefs.contains(KEY_CIPHERTEXT)

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val ALIAS = "ecard_ledger_master_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_USERNAME = "username"
        const val KEY_IV = "iv"
        const val KEY_CIPHERTEXT = "ciphertext"
    }
}
