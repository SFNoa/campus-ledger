package com.morchid.ecardledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 凭据存储的契约测试。
 *
 * Robolectric 没有实现 AndroidKeyStore，于是这里顺带覆盖了「密钥库不可用」这条失败路径：
 * 它必须返回 false 而不是抛异常 —— 否则保存凭据失败会把已经成功的登录一起带崩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CredentialStoreTest {

    private fun store() = CredentialStore(RuntimeEnvironment.getApplication())

    @Test
    fun `save 绝不抛异常；成功则可读回，失败则返回 false 且不留半截数据`() {
        val s = store()
        val outcome = runCatching { s.save("8207260917", "p@ss word 中文") }
        assertTrue("save 不允许抛异常，实际: ${outcome.exceptionOrNull()}", outcome.isSuccess)

        val saved = outcome.getOrThrow()
        println("[keystore] Robolectric 下 CredentialStore.save 返回 = $saved")

        if (saved) {
            assertEquals("8207260917" to "p@ss word 中文", s.load())
            assertTrue(s.hasCredentials())
        } else {
            assertNull("保存失败时必须读不到凭据", s.load())
        }
    }

    @Test
    fun `没存过凭据时 load 返回 null 且不抛异常`() {
        val s = store()
        assertNull(s.load())
        assertEquals(false, s.hasCredentials())
    }

    @Test
    fun `clear 之后读不到凭据`() {
        val s = store()
        s.save("u", "p")
        s.clear()
        assertNull(s.load())
        assertEquals(false, s.hasCredentials())
    }

    @Test
    fun `重复保存会覆盖旧凭据`() {
        val s = store()
        if (!s.save("u1", "p1")) return // 密钥库不可用，这条不适用
        s.save("u2", "p2")
        assertEquals("u2" to "p2", s.load())
    }
}
