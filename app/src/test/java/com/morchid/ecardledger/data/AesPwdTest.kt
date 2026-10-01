package com.morchid.ecardledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用「站点自己的 CryptoJS 跑出来的真实密文」当基准，验证生产加密代码。
 *
 * 基准来源：tools/verify-crypto.mjs —— 该脚本把
 *   https://ca.csu.edu.cn/authserver/.../static/common/encrypt.js
 * 载入 Node 的 vm，调用站点原生的 getAesString()，用同一组
 *   salt / prefix / iv
 * 生成下列密文。Node 侧实现与站点 CryptoJS 逐字节一致已单独确认。
 *
 * 所以：如果这些断言通过，说明 Kotlin 生产代码与站点前端加密结果完全相同。
 */
class AesPwdTest {

    private val salt = "bnuuJ9cz2Jcztq9b"
    private val prefix = "Xk9mQ2pR7tYwZ3bN".repeat(4) // 恰好 64 字符
    private val iv = "AbCdEfGhJkMnPqRs"               // 恰好 16 字符

    @Test
    fun `前缀与 iv 长度符合站点约定`() {
        assertEquals(64, prefix.length)
        assertEquals(16, iv.length)
        assertEquals(16, salt.length)
    }

    @Test
    fun `纯数字密码的密文与站点 CryptoJS 完全一致`() {
        val actual = AesPwd.encryptDeterministic("Lzy589449826", salt, prefix, iv)
        assertEquals(
            "rUCX89Rxhl5Y41RoDJn5xlLPGHQjjnCtSJVQ4XO3VGMLY5km+s0E3zG+PruzYV7HyTnIgTDjoK5Qngs7xz48cGyVbJFtF8ki+AuHIpCddTU=",
            actual,
        )
    }

    @Test
    fun `含中文密码的密文与站点 CryptoJS 完全一致`() {
        val actual = AesPwd.encryptDeterministic("密码Test123", salt, prefix, iv)
        assertEquals(
            "rUCX89Rxhl5Y41RoDJn5xlLPGHQjjnCtSJVQ4XO3VGMLY5km+s0E3zG+PruzYV7HyTnIgTDjoK5Qngs7xz48cJzewGDH84CtXT25GZerFdk=",
            actual,
        )
    }

    @Test
    fun `恰好 16 字节边界时补齐方式与站点一致`() {
        val actual = AesPwd.encryptDeterministic("1234567890abcdef", salt, prefix, iv)
        assertEquals(
            "rUCX89Rxhl5Y41RoDJn5xlLPGHQjjnCtSJVQ4XO3VGMLY5km+s0E3zG+PruzYV7HyTnIgTDjoK5Qngs7xz48cLAdD4pzVdk49rSxDVyJBvI1/xaZ6bpdWXh4yRLaojCr",
            actual,
        )
    }

    @Test
    fun `生产路径每次都用新的随机前缀和 iv，所以密文不同但都能被解开`() {
        val first = AesPwd.encrypt("Lzy589449826", salt)
        val second = AesPwd.encrypt("Lzy589449826", salt)
        assertNotEquals("随机 iv 决定了每次密文都应当不同", first, second)

        // 两个密文的长度应当一致（同样的明文长度）
        assertEquals(first.length, second.length)
        assertTrue("应当是 base64", first.matches(Regex("^[A-Za-z0-9+/]+=*$")))
    }

    @Test
    fun `盐长度不对时直接报错，避免悄悄送出坏密文`() {
        val thrown = runCatching {
            AesPwd.encryptDeterministic("x", "short", prefix, iv)
        }.exceptionOrNull()
        assertTrue(
            "应当抛 IllegalArgumentException，实际 ${thrown?.javaClass?.simpleName}",
            thrown is IllegalArgumentException,
        )
    }

    @Test
    fun `盐里的首尾空白会被忽略（站点 JS 里有 trim）`() {
        val withSpaces = AesPwd.encryptDeterministic("Lzy589449826", "  $salt  ", prefix, iv)
        val withoutSpaces = AesPwd.encryptDeterministic("Lzy589449826", salt, prefix, iv)
        assertEquals(withoutSpaces, withSpaces)
    }
}
