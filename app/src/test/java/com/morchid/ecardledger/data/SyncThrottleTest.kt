package com.morchid.ecardledger.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 频率限制的**状态持久化**测试（跑真 SQLite）。
 *
 * 决策逻辑由 [SyncThrottlePolicyTest] 覆盖（纯函数）；这里专门验证一件容易被忽略的事：
 * 限制状态必须落在 `meta` 表里 —— 否则**用户重启一下 App 就把限制绕过去了**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncThrottleTest {

    private lateinit var db: LedgerDb
    private var fakeNow = 1_790_000_000_000L

    private fun throttle() = SyncThrottle(db) { fakeNow }

    @Before
    fun setUp() {
        db = LedgerDb(RuntimeEnvironment.getApplication())
        fakeNow = 1_790_000_000_000L
        // 每个用例都从干净的限制状态开始（reset 会清掉本类用到的 4 个 key）
        SyncThrottle(db) { fakeNow }.reset()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `记一次登录尝试后立刻就被拦住`() {
        val throttle = throttle()
        assertTrue("初始应当允许", throttle.loginDecision().allowed)

        throttle.noteLoginAttempt()

        val decision = throttle.loginDecision()
        assertFalse(decision.allowed)
        assertEquals(fakeNow + SyncThrottlePolicy.MIN_LOGIN_INTERVAL_MS, decision.retryAtMillis)
    }

    @Test
    fun `限制状态跨实例保持——重启 App 绕不过去`() {
        throttle().noteLoginAttempt()

        // 新建实例 = 模拟 App 重启（状态在 meta 表里，不在内存里）
        val afterRestart = SyncThrottle(db) { fakeNow }
        assertFalse(
            "重启后仍应被拦住，否则用户重启一下就能绕过频率限制",
            afterRestart.loginDecision().allowed,
        )
    }

    @Test
    fun `登录成功会清空失败计数与冻结冷却`() {
        val throttle = throttle()
        throttle.noteLoginAttempt()
        throttle.noteLoginFailure("您提供的用户名或者密码有误")
        throttle.noteLoginSuccess()

        fakeNow += SyncThrottlePolicy.MIN_LOGIN_INTERVAL_MS + 1
        assertTrue("成功后不该再被退避挡住", throttle.loginDecision().allowed)
        assertNull("成功后应清掉冻结冷却", throttle.blockedUntil())
    }

    @Test
    fun `失败提示里的冻结时间会被记下来并冷却到那时候`() {
        val throttle = throttle()
        throttle.noteLoginAttempt()
        throttle.noteLoginFailure(
            "登录失败：该账号已被冻结，预计解冻时间：2026-09-28 15:44:30，" +
                "冻结原因：账号频繁访问；可通过账号解禁功能自助解冻。",
        )

        val until = throttle.blockedUntil()
        assertNotNull("必须把解冻时间记下来", until)

        fakeNow = until!! - 1
        val frozen = throttle.loginDecision()
        assertFalse("冻结期间不能放行", frozen.allowed)
        assertTrue("要说明是冻结，而不是笼统的『太频繁』", frozen.reason!!.contains("冻结"))

        fakeNow = until + 1
        assertTrue("过了冻结点应当恢复", throttle.loginDecision().allowed)
    }

    @Test
    fun `同步成功会记时间，一分钟内手动同步被拦`() {
        val throttle = throttle()
        assertTrue(throttle.syncDecision(manual = true).allowed)

        throttle.noteSyncSuccess()
        assertFalse("防止连点", throttle.syncDecision(manual = true).allowed)

        fakeNow += SyncThrottlePolicy.MIN_MANUAL_SYNC_INTERVAL_MS
        assertTrue(throttle.syncDecision(manual = true).allowed)
    }

    @Test
    fun `reset 会清空所有限制状态（解绑校园卡时用）`() {
        val throttle = throttle()
        throttle.noteLoginAttempt()
        throttle.noteLoginFailure("账号已被冻结，预计解冻时间：2026-09-28 15:44:30")
        throttle.noteSyncSuccess()

        throttle.reset()

        assertTrue(throttle.loginDecision().allowed)
        assertNull(throttle.blockedUntil())
        assertNull(throttle.lastSyncMillis())
        assertTrue(throttle.syncDecision(manual = true).allowed)
    }

    @Test
    fun `自动同步的间隔比手动长得多`() {
        val throttle = throttle()
        throttle.noteSyncSuccess()

        fakeNow += 10 * 60_000L // 10 分钟
        assertTrue("手动可以", throttle.syncDecision(manual = true).allowed)
        assertFalse("自动还不到时候（6 小时）", throttle.syncDecision(manual = false).allowed)
    }
}
