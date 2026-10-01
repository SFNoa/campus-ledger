package com.morchid.ecardledger.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 与 ca.csu.edu.cn 的 encrypt.js 完全一致的密码加密。
 *
 * 站点实现（摘自 /authserver/gorgeousCsu20260806/static/common/encrypt.js）：
 *
 *   function getAesString(n, f, c) {
 *     f = f.replace(/(^\s+)|(\s+$)/g, "");   // salt 先去首尾空白
 *     f = CryptoJS.enc.Utf8.parse(f);        // key
 *     c = CryptoJS.enc.Utf8.parse(c);        // iv
 *     return CryptoJS.AES.encrypt(n, f, {iv: c, mode: CryptoJS.mode.CBC, padding: CryptoJS.pad.Pkcs7}).toString();
 *   }
 *   function encryptAES(n, f) { return f ? getAesString(randomString(64) + n, f, randomString(16)) : n }
 *   var $aes_chars = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678";  // 48 个字符
 *
 * 正确性验证：
 *  1. 用 Node 把站点自己的 encrypt.js(CryptoJS) 载入 vm，与同一算法逐字节比对 6 种密码形态，全部一致。
 *  2. AesPwdTest 用上面跑出来的真实密文当基准，直接断言本文件的输出 —— 保证生产代码路径也被测到。
 *
 * 用 java.util.Base64 而不是 android.util.Base64：minSdk 26 起 java.util.Base64 可用，
 * 这样整个加密逻辑是纯 JVM 代码，可以在单元测试里跑。
 */
object AesPwd {

    private const val CHARSET = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
    private const val SALT_LENGTH = 16
    private const val PREFIX_LENGTH = 64
    private val random = SecureRandom()

    internal fun randomString(length: Int): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(CHARSET[random.nextInt(CHARSET.length)]) }
        return sb.toString()
    }

    /** 生产路径：前缀与 iv 都用随机字符 */
    fun encrypt(password: String, salt: String): String =
        encryptDeterministic(password, salt, randomString(PREFIX_LENGTH), randomString(SALT_LENGTH))

    /**
     * 确定性版本，仅供测试比对（前缀与 iv 由调用方给定）。
     * 对应站点的 getAesString(randomString(64) + password, salt, randomString(16))。
     */
    internal fun encryptDeterministic(
        password: String,
        salt: String,
        prefix: String,
        iv: String,
    ): String {
        val key = salt.trim().toByteArray(Charsets.UTF_8)
        require(key.size == SALT_LENGTH) { "pwdEncryptSalt 应为 16 字节，实际 ${key.size}" }
        require(iv.length == SALT_LENGTH) { "iv 应为 16 字符，实际 ${iv.length}" }

        val plain = (prefix + password).toByteArray(Charsets.UTF_8)
        // JCE 的 PKCS5Padding 对 16 字节分组等价于 PKCS7，与 CryptoJS pad.Pkcs7 一致
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
        )
        // 与 CryptoJS 的 .toString() 相同：标准 base64，带 padding，不换行
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain))
    }
}
