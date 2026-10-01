package com.morchid.ecardledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 本机账号的仓库层测试（跑真 SQLite）。
 *
 * 关注的是「注册 → 登录 → 改密码 → 注销」这条链路的**持久化行为**，
 * 以及一条安全要求：**明文密码不能出现在数据库里**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalAccountTest {

    private val username = "testuser"
    private val password = "MySecret123"

    private fun repo() = LedgerRepository(RuntimeEnvironment.getApplication())
    private fun db() = LedgerDb(RuntimeEnvironment.getApplication())

    @Before
    fun setUp() {
        // 每个用例都从「本机没有账号」开始
        repo().deleteLocalAccount()
    }

    // ------------------------------------------------------------ 注册

    @Test
    fun `注册后本机就有账号了`() {
        val repository = repo()
        assertFalse(repository.hasLocalAccount())
        assertNull(repository.localUsername())

        assertNull("合法输入应当成功", repository.registerLocalAccount(username, password, password))

        assertTrue(repository.hasLocalAccount())
        assertEquals(username, repository.localUsername())
    }

    @Test
    fun `数据库里存的是哈希，不是明文密码`() {
        repo().registerLocalAccount(username, password, password)

        val account = readAccount()
        assertFalse("明文密码绝不能落盘", account.passwordHash.contains(password))
        assertFalse("明文密码也不能藏在盐里", account.salt.contains(password))
        assertTrue("派生结果不能是空的", account.passwordHash.isNotEmpty())
        assertTrue("盐不能是空的", account.salt.isNotEmpty())
        assertEquals("轮数要落库，将来才能升级算法", LocalAuth.PBKDF2_ITERATIONS, account.iterations)
        assertEquals(username, account.username)
    }

    @Test
    fun `重复注册会被拒绝`() {
        val repository = repo()
        repository.registerLocalAccount(username, password, password)

        val error = repository.registerLocalAccount("another", "abc123456", "abc123456")
        assertEquals("本机已经有账号了，请直接登录", error)
        assertEquals("原账号不该被覆盖", username, repository.localUsername())
    }

    @Test
    fun `校验不通过时不会建账号`() {
        val repository = repo()
        assertEquals("请填写用户名", repository.registerLocalAccount("", password, password))
        assertTrue(repository.registerLocalAccount("ab", password, password)!!.contains("至少"))
        assertTrue(repository.registerLocalAccount(username, "abc", "abc")!!.contains("至少"))
        assertEquals(
            "两次输入的密码不一致",
            repository.registerLocalAccount(username, password, "Other123"),
        )
        assertFalse("任何一条不合法都不该建账号", repository.hasLocalAccount())
    }

    // ------------------------------------------------------------ 登录

    @Test
    fun `正确凭据能登录`() {
        val repository = repo()
        repository.registerLocalAccount(username, password, password)
        assertNull(repository.verifyLocalLogin(username, password))
        assertNull("用户名前后空格应当被容忍", repository.verifyLocalLogin("  $username  ", password))
    }

    @Test
    fun `密码错误被拒绝`() {
        val repository = repo()
        repository.registerLocalAccount(username, password, password)
        assertEquals("用户名或密码不正确", repository.verifyLocalLogin(username, "MySecret124"))
        assertEquals("用户名或密码不正确", repository.verifyLocalLogin(username, ""))
        assertEquals("用户名或密码不正确", repository.verifyLocalLogin(username, password.uppercase()))
    }

    @Test
    fun `用户名错与密码错的提示完全一样，不泄漏哪个用户名存在`() {
        val repository = repo()
        repository.registerLocalAccount(username, password, password)

        val wrongName = repository.verifyLocalLogin("someoneelse", password)
        val wrongPassword = repository.verifyLocalLogin(username, "wrong-password")
        assertEquals("两种失败的提示必须一字不差", wrongPassword, wrongName)
    }

    @Test
    fun `还没有账号时提示先注册`() {
        assertEquals("本机还没有账号，请先注册", repo().verifyLocalLogin(username, password))
    }

    // ------------------------------------------------------------ 改密码

    @Test
    fun `当前密码不对就不给改`() {
        val repository = repo()
        repository.registerLocalAccount(username, password, password)
        assertEquals("当前密码不正确", repository.changeLocalPassword("wrong", "NewSecret456", "NewSecret456"))
        assertNull("旧密码应当仍然有效", repository.verifyLocalLogin(username, password))
    }

    @Test
    fun `新密码不合法也不给改`() {
        val repository = repo()
        repository.registerLocalAccount(username, password, password)
        assertTrue(
            repository.changeLocalPassword(password, "abc", "abc")!!.contains("至少"),
        )
        assertEquals(
            "两次输入的新密码不一致",
            repository.changeLocalPassword(password, "NewSecret456", "NewSecret457"),
        )
        assertNull(repository.verifyLocalLogin(username, password))
    }

    @Test
    fun `改密码成功后新密码生效、旧密码失效`() {
        val repository = repo()
        repository.registerLocalAccount(username, password, password)

        assertNull(repository.changeLocalPassword(password, "NewSecret456", "NewSecret456"))

        assertNull("新密码应当能登录", repository.verifyLocalLogin(username, "NewSecret456"))
        assertEquals("旧密码应当失效", "用户名或密码不正确", repository.verifyLocalLogin(username, password))
    }

    @Test
    fun `改密码会换盐`() {
        val repository = repo()
        repository.registerLocalAccount(username, password, password)
        val before = readAccount().salt

        repository.changeLocalPassword(password, "NewSecret456", "NewSecret456")

        val after = readAccount().salt
        assertNotEquals("换密码必须换盐，否则旧哈希能被复用", before, after)
        assertTrue("updatedAt 应当被刷新", readAccount().updatedAt >= readAccount().createdAt)
    }

    // ------------------------------------------------------------ 持久化与注销

    @Test
    fun `账号跨实例保持——重启 App 还在`() {
        repo().registerLocalAccount(username, password, password)

        // 新实例 = 模拟重启
        val afterRestart = repo()
        assertTrue(afterRestart.hasLocalAccount())
        assertNull(afterRestart.verifyLocalLogin(username, password))
    }

    @Test
    fun `注销只删账号，不动账本数据`() {
        val repository = repo()
        repository.registerLocalAccount(username, password, password)
        repository.addManualEntry(
            amountCents = 1250, isIncome = false, merchant = "打印店", category = "学习", note = "",
        )

        repository.deleteLocalAccount()

        assertFalse(repository.hasLocalAccount())
        assertEquals("账本数据不该被注销账号连带删掉", 1, repository.count())
    }

    private fun readAccount(): LocalAccount {
        val helper = db()
        return try {
            helper.localAccount()!!
        } finally {
            helper.close()
        }
    }
}
