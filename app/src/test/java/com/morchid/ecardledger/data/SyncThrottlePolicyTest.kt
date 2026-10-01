package com.morchid.ecardledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 频率限制策略的测试。
 *
 * 这些用例背后是**一次真实的账号冻结事故**：开发时反复跑联网测试，
 * 学校风控把账号冻了（原文见 [SyncThrottlePolicy]）。所以这里的每条规则都不是拍脑袋定的，
 * 而是为了「让正常使用碰不到风控，且被拦时用户知道等多久」。
 */
class SyncThrottlePolicyTest {

    private val t0 = 1_790_000_000_000L
    private val minute = 60_000L

    // ------------------------------------------------------------ 基本间隔

    @Test
    fun `从没登录过时允许`() {
        assertTrue(SyncThrottlePolicy.decideLogin(t0, emptyList(), 0, null).allowed)
    }

    @Test
    fun `刚登录过就再登录会被拦住`() {
        val decision = SyncThrottlePolicy.decideLogin(
            now = t0,
            recentLoginAttempts = listOf(t0 - 5_000L),
            failStreak = 0,
            blockedUntilMillis = null,
        )
        assertFalse(decision.allowed)
        assertEquals("应当告知 30 秒后可以重试", t0 - 5_000L + 30_000L, decision.retryAtMillis)
    }

    @Test
    fun `超过最小间隔后允许`() {
        val decision = SyncThrottlePolicy.decideLogin(
            now = t0,
            recentLoginAttempts = listOf(t0 - SyncThrottlePolicy.MIN_LOGIN_INTERVAL_MS),
            failStreak = 0,
            blockedUntilMillis = null,
        )
        assertTrue(decision.allowed)
    }

    // ------------------------------------------------------------ 失败退避

    @Test
    fun `连续失败会逐级拉长等待`() {
        // 第 1 次失败后等 1 分钟
        assertFalse(
            SyncThrottlePolicy.decideLogin(t0, listOf(t0 - 30_000L), 1, null).allowed,
        )
        // 第 2 次失败后等 5 分钟：1 分钟后仍不放行
        assertFalse(
            SyncThrottlePolicy.decideLogin(t0, listOf(t0 - 1 * minute), 2, null).allowed,
        )
        // 第 2 次失败后等 5 分钟：5 分钟后放行
        assertTrue(
            SyncThrottlePolicy.decideLogin(t0, listOf(t0 - 5 * minute), 2, null).allowed,
        )
        // 第 3 次失败后等 15 分钟
        assertFalse(
            SyncThrottlePolicy.decideLogin(t0, listOf(t0 - 10 * minute), 3, null).allowed,
        )
        // 第 4 次失败后等 60 分钟
        assertFalse(
            SyncThrottlePolicy.decideLogin(t0, listOf(t0 - 30 * minute), 4, null).allowed,
        )
        assertTrue(
            SyncThrottlePolicy.decideLogin(t0, listOf(t0 - 60 * minute), 4, null).allowed,
        )
    }

    @Test
    fun `失败次数再多也不会越界`() {
        // 退避阶梯只有 4 级，第 10 次失败应当沿用最后一级（60 分钟）而不是崩掉
        val decision = SyncThrottlePolicy.decideLogin(t0, listOf(t0 - 30 * minute), 10, null)
        assertFalse(decision.allowed)
        assertEquals(t0 + 30 * minute, decision.retryAtMillis)
    }

    // ------------------------------------------------------------ 每日上限

    @Test
    fun `一天内登录次数达到上限后不再允许`() {
        // 20 次尝试，每次间隔 1 分钟，最后一次在 1 分钟前
        val attempts = (1..SyncThrottlePolicy.MAX_LOGINS_PER_DAY)
            .map { t0 - it * minute }
        val decision = SyncThrottlePolicy.decideLogin(t0, attempts, 0, null)

        assertFalse("达到上限必须拦住，否则就是这次事故的复现", decision.allowed)
        assertTrue(decision.reason!!.contains("上限"))
        // 最早的那次过期后就能再用
        assertEquals(t0 - SyncThrottlePolicy.MAX_LOGINS_PER_DAY * minute + SyncThrottlePolicy.DAY_MS, decision.retryAtMillis)
    }

    @Test
    fun `超过一天前的尝试不占额度`() {
        val stale = (1..50).map { t0 - SyncThrottlePolicy.DAY_MS - it * minute }
        assertTrue(SyncThrottlePolicy.decideLogin(t0, stale, 0, null).allowed)
    }

    // ------------------------------------------------------------ 冻结冷却

    @Test
    fun `冻结冷却优先于其它所有规则`() {
        val freezeUntil = t0 + 20 * minute
        val decision = SyncThrottlePolicy.decideLogin(
            now = t0,
            recentLoginAttempts = emptyList(), // 其它规则都满足
            failStreak = 0,
            blockedUntilMillis = freezeUntil,
        )
        assertFalse("冻结期间必须拦住——重试只会延长冻结", decision.allowed)
        assertEquals(freezeUntil, decision.retryAtMillis)
        assertTrue(decision.reason!!.contains("冻结"))
    }

    @Test
    fun `冻结到期后恢复`() {
        assertTrue(
            SyncThrottlePolicy.decideLogin(t0, emptyList(), 0, t0 - 1).allowed,
        )
    }

    // ------------------------------------------------------------ 冻结时间解析

    @Test
    fun `能从学校的真实提示里解析出解冻时间`() {
        val message = "登录失败：该账号已被冻结，预计解冻时间：2026-09-28 15:44:30，" +
            "冻结原因：账号频繁访问；可通过账号解禁功能自助解冻。"
        val until = SyncThrottlePolicy.parseFreezeUntil(message, "Asia/Shanghai")

        assertEquals("2026-09-28 07:44:30", formatIn(until!!, "UTC"))
    }

    @Test
    fun `不是冻结提示就不解析`() {
        assertNull(SyncThrottlePolicy.parseFreezeUntil("您提供的用户名或者密码有误", "Asia/Shanghai"))
        assertNull(SyncThrottlePolicy.parseFreezeUntil(null, "Asia/Shanghai"))
        assertNull(SyncThrottlePolicy.parseFreezeUntil("", "Asia/Shanghai"))
    }

    @Test
    fun `说了冻结但没给时间时不瞎猜`() {
        assertNull(SyncThrottlePolicy.parseFreezeUntil("账号已被冻结", "Asia/Shanghai"))
    }

    @Test
    fun `冻结时间按学校时区解析，与设备时区无关`() {
        val message = "该账号已被冻结，预计解冻时间：2026-09-28 15:44:30，冻结原因：账号频繁访问"
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val a = SyncThrottlePolicy.parseFreezeUntil(message, "Asia/Shanghai")
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val b = SyncThrottlePolicy.parseFreezeUntil(message, "Asia/Shanghai")
            assertEquals(a, b)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `冷却时间会留一点余量，避免卡在解冻那一秒又踩风控`() {
        val freezeUntil = t0
        assertEquals(t0 + 60_000L, SyncThrottlePolicy.withSafetyMargin(freezeUntil))
    }

    // ------------------------------------------------------------ 同步间隔

    @Test
    fun `从未同步过时允许`() {
        assertTrue(SyncThrottlePolicy.decideSync(t0, null, manual = true).allowed)
    }

    @Test
    fun `手动同步有 60 秒最小间隔，防止连点`() {
        val decision = SyncThrottlePolicy.decideSync(t0, t0 - 10_000L, manual = true)
        assertFalse(decision.allowed)
        assertEquals(t0 + 50_000L, decision.retryAtMillis)

        assertTrue(SyncThrottlePolicy.decideSync(t0, t0 - 60_000L, manual = true).allowed)
    }

    @Test
    fun `自动同步按 6 小时判断`() {
        val fiveHoursAgo = t0 - 5 * 60 * minute
        assertFalse(SyncThrottlePolicy.decideSync(t0, fiveHoursAgo, manual = false).allowed)

        val sevenHoursAgo = t0 - 7 * 60 * minute
        assertTrue(SyncThrottlePolicy.decideSync(t0, sevenHoursAgo, manual = false).allowed)
    }

    @Test
    fun `手动同步的间隔比自动短`() {
        val tenMinutesAgo = t0 - 10 * minute
        assertTrue("手动可以更频繁", SyncThrottlePolicy.decideSync(t0, tenMinutesAgo, manual = true).allowed)
        assertFalse("自动不行", SyncThrottlePolicy.decideSync(t0, tenMinutesAgo, manual = false).allowed)
    }

    // ------------------------------------------------------------ 文案

    @Test
    fun `剩余时间文案对人友好`() {
        assertEquals("1 分钟", SyncThrottlePolicy.describeRemaining(t0, t0 + 30_000L))
        assertEquals("3 分钟", SyncThrottlePolicy.describeRemaining(t0, t0 + 3 * minute))
        assertEquals("1 小时", SyncThrottlePolicy.describeRemaining(t0, t0 + 60 * minute))
        assertEquals("1 小时 20 分钟", SyncThrottlePolicy.describeRemaining(t0, t0 + 80 * minute))
        assertEquals("现在可以重试", SyncThrottlePolicy.describeRemaining(t0, t0 - 1))
    }

    @Test
    fun `被拦的说明里一定带上还要等多久`() {
        val decision = SyncThrottlePolicy.decideLogin(t0, listOf(t0 - 10_000L), 0, null)
        val text = SyncThrottlePolicy.describeDecision(t0, decision)
        assertTrue("必须告诉用户等多久：$text", text.contains("分钟"))
        assertTrue(text.contains("频繁"))
    }

    @Test
    fun `冻结文案要说清是学校的风控而不是 App 出错`() {
        val text = SyncThrottlePolicy.describeFreeze(t0, t0 + 20 * minute)
        assertTrue(text.contains("20 分钟"))
        assertTrue(text.contains("不是 App 出错"))
    }

    private fun formatIn(epoch: Long, zoneId: String): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            .apply { timeZone = TimeZone.getTimeZone(zoneId) }
            .format(Date(epoch))
}
