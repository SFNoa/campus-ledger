package com.morchid.ecardledger.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 本机账号的密码学与校验规则（纯函数，不碰数据库，方便测试）。
 *
 * ## 存法
 *
 * PBKDF2-HMAC-SHA256 加盐派生，**明文密码永不落盘、永不入库、永不上传**。
 * 每个账号一个 16 字节随机盐；改密码时会**重新生成盐**（旧哈希无法复用）。
 *
 * ## 为什么选 PBKDF2 而不是「SHA256 撒点盐」
 *
 * 单轮 SHA256 对离线爆破几乎不设防 —— 手机丢了、数据库被拷走，弱密码几秒就出来了。
 * PBKDF2 用 10 万轮把每次猜测的成本抬高几个数量级。API 26+ 和 JDK 都自带这个算法，
 * 不需要额外依赖。
 *
 * ## 纯本地的代价
 *
 * 没有服务器，也就**没有「找回密码」**。忘了只能清数据重来。这是刻意的取舍：
 * 一旦提供找回入口，锁就形同虚设。
 */
object LocalAuth {

    const val MIN_USERNAME_LENGTH = 3
    const val MAX_USERNAME_LENGTH = 20
    const val MIN_PASSWORD_LENGTH = 6

    /** 迭代次数。手机上约几十到一两百毫秒，用户几乎无感，但离线爆破成本被抬得很高。 */
    const val PBKDF2_ITERATIONS = 100_000

    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    // ------------------------------------------------------------ 校验规则

    /** @return 不合法时返回可直接显示的原因；合法返回 null */
    fun validateUsername(username: String): String? {
        val name = username.trim()
        return when {
            name.isEmpty() -> "请填写用户名"
            name.length < MIN_USERNAME_LENGTH -> "用户名至少 $MIN_USERNAME_LENGTH 个字符"
            name.length > MAX_USERNAME_LENGTH -> "用户名最多 $MAX_USERNAME_LENGTH 个字符"
            name.any { it.isWhitespace() } -> "用户名不能包含空格"
            else -> null
        }
    }

    /** @return 不合法时返回原因；合法返回 null */
    fun validatePassword(password: String): String? = when {
        password.isEmpty() -> "请填写密码"
        password.length < MIN_PASSWORD_LENGTH -> "密码至少 $MIN_PASSWORD_LENGTH 位"
        password.any { it.isWhitespace() } -> "密码不能包含空格"
        password.toSet().size == 1 -> "别用重复的同一个字符当密码"
        else -> null
    }

    /** 注册/改密码的整体校验（含两次输入一致） */
    fun validateRegistration(username: String, password: String, confirm: String): String? {
        validateUsername(username)?.let { return it }
        validatePassword(password)?.let { return it }
        if (password != confirm) return "两次输入的密码不一致"
        return null
    }

    // ------------------------------------------------------------ 哈希

    fun newSalt(): String {
        val bytes = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /** 派生密钥（Base64）。同样的密码 + 同样的盐 + 同样的轮数才会得到同样的结果。 */
    fun hash(password: String, saltBase64: String, iterations: Int = PBKDF2_ITERATIONS): String {
        val salt = Base64.getDecoder().decode(saltBase64)
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            val key = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
            Base64.getEncoder().encodeToString(key)
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * 校验密码。
     *
     * 用 [MessageDigest.isEqual] 做**常量时间比较**，避免通过响应时间差逐字节猜哈希。
     * 对比的是 Base64 字符串的字节，长度固定（256 位 → 44 字符），不会泄漏信息。
     */
    fun verify(
        password: String,
        saltBase64: String,
        iterations: Int,
        expectedHashBase64: String,
    ): Boolean {
        val actual = runCatching { hash(password, saltBase64, iterations) }.getOrNull() ?: return false
        return MessageDigest.isEqual(
            actual.toByteArray(Charsets.UTF_8),
            expectedHashBase64.toByteArray(Charsets.UTF_8),
        )
    }
}
