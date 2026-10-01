package com.morchid.ecardledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 本机账号的密码学与校验规则测试（纯 JVM，不需要 Android）。
 *
 * 这是整个账号体系的地基：如果哈希/校验这里错了，锁就是假的。
 */
class LocalAuthTest {

    private val password = "MySecret123"

    // ------------------------------------------------------------ 哈希

    @Test
    fun `同样的密码和盐得到同样的结果`() {
        val salt = LocalAuth.newSalt()
        assertEquals(LocalAuth.hash(password, salt), LocalAuth.hash(password, salt))
    }

    @Test
    fun `每个账号的盐都不同，同样的密码也得到不同哈希`() {
        val a = LocalAuth.hash(password, LocalAuth.newSalt())
        val b = LocalAuth.hash(password, LocalAuth.newSalt())
        assertNotEquals("盐必须起作用，否则彩虹表就能批量破解", a, b)
    }

    @Test
    fun `盐是随机且长度固定的`() {
        val salts = (1..20).map { LocalAuth.newSalt() }
        assertEquals("20 次生成的盐不该有重复", 20, salts.toSet().size)
        assertEquals(16, Base64.getDecoder().decode(salts.first()).size)
    }

    @Test
    fun `哈希里不含明文密码`() {
        val hash = LocalAuth.hash(password, LocalAuth.newSalt())
        assertFalse("哈希里绝不该出现明文", hash.contains(password))
    }

    @Test
    fun `迭代次数真的生效`() {
        val salt = LocalAuth.newSalt()
        assertNotEquals(
            "轮数不同结果必须不同，否则说明该参数被忽略了",
            LocalAuth.hash(password, salt, 1_000),
            LocalAuth.hash(password, salt, 2_000),
        )
    }

    @Test
    fun `迭代次数不高于 10 万就不要发版`() {
        assertTrue(
            "PBKDF2 的强度全靠轮数，调小等于自废武功（当前 ${LocalAuth.PBKDF2_ITERATIONS}）",
            LocalAuth.PBKDF2_ITERATIONS >= 100_000,
        )
    }

    @Test
    fun `本平台支持 PBKDF2WithHmacSHA256`() {
        // 这条是防「换 JDK / 换 ROM 后算法不可用」——它是账号体系的前提条件
        val hash = LocalAuth.hash(password, LocalAuth.newSalt())
        assertTrue(hash.isNotEmpty())
        assertTrue(base64DecodedLength(hash) == 32) // 256 位
    }

    // ------------------------------------------------------------ 校验密码

    @Test
    fun `正确密码校验通过`() {
        val salt = LocalAuth.newSalt()
        assertTrue(LocalAuth.verify(password, salt, LocalAuth.PBKDF2_ITERATIONS, LocalAuth.hash(password, salt)))
    }

    @Test
    fun `错误密码校验失败`() {
        val salt = LocalAuth.newSalt()
        val hash = LocalAuth.hash(password, salt)
        assertFalse(LocalAuth.verify("MySecret124", salt, LocalAuth.PBKDF2_ITERATIONS, hash))
        assertFalse(LocalAuth.verify("", salt, LocalAuth.PBKDF2_ITERATIONS, hash))
        assertFalse(LocalAuth.verify("mysecret123", salt, LocalAuth.PBKDF2_ITERATIONS, hash))
    }

    @Test
    fun `换了盐之后旧哈希就失效`() {
        val hash = LocalAuth.hash(password, LocalAuth.newSalt())
        assertFalse(
            "拿新盐去校验旧哈希必须失败",
            LocalAuth.verify(password, LocalAuth.newSalt(), LocalAuth.PBKDF2_ITERATIONS, hash),
        )
    }

    @Test
    fun `哈希被篡改时校验失败而不是抛异常`() {
        assertFalse(LocalAuth.verify(password, LocalAuth.newSalt(), LocalAuth.PBKDF2_ITERATIONS, "not-base64!!"))
        assertFalse(LocalAuth.verify(password, "也不是合法盐", LocalAuth.PBKDF2_ITERATIONS, "x"))
    }

    // ------------------------------------------------------------ 用户名规则

    @Test
    fun `用户名规则`() {
        assertNull(LocalAuth.validateUsername("testuser"))
        // 中文名也可以；前后空格会被去掉，所以这里 3 个字是合法的
        assertNull(LocalAuth.validateUsername("  小明明  "))
        assertEquals("请填写用户名", LocalAuth.validateUsername(""))
        assertEquals("请填写用户名", LocalAuth.validateUsername("   "))
        assertTrue(LocalAuth.validateUsername("ab")!!.contains("至少"))
        assertTrue("中文名同样受最短长度限制", LocalAuth.validateUsername("小明")!!.contains("至少"))
        assertTrue(LocalAuth.validateUsername("a".repeat(21))!!.contains("最多"))
        assertTrue(LocalAuth.validateUsername("有 空格")!!.contains("空格"))
    }

    // ------------------------------------------------------------ 密码规则

    @Test
    fun `密码规则`() {
        assertNull(LocalAuth.validatePassword("abc123"))
        assertNull(LocalAuth.validatePassword("123456"))
        assertNull(LocalAuth.validatePassword("中文密码也可以"))
        assertEquals("请填写密码", LocalAuth.validatePassword(""))
        assertTrue(LocalAuth.validatePassword("abc12")!!.contains("至少"))
        assertTrue(LocalAuth.validatePassword("abc 123")!!.contains("空格"))
        assertTrue(LocalAuth.validatePassword("111111")!!.contains("重复"))
    }

    @Test
    fun `两次输入不一致会被拦下`() {
        val error = LocalAuth.validateRegistration("testuser", "abc123456", "abc123457")
        assertEquals("两次输入的密码不一致", error)
    }

    @Test
    fun `合法输入整体通过`() {
        assertNull(LocalAuth.validateRegistration("testuser", "abc123456", "abc123456"))
    }

    @Test
    fun `整体校验会先报用户名的问题`() {
        assertEquals("请填写用户名", LocalAuth.validateRegistration("", "abc", "abc"))
        assertTrue(LocalAuth.validateRegistration("testuser", "ab", "ab")!!.contains("至少"))
    }

    private fun base64DecodedLength(text: String): Int =
        runCatching { Base64.getDecoder().decode(text).size }.getOrDefault(-1)
}
