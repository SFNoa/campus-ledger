package com.morchid.ecardledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动同步时机的纯逻辑测试：不联网、不需要 Robolectric。
 * 用固定的时间基准，避免依赖真实时钟。
 */
class SyncPolicyTest {

    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    @Test
    fun `从未同步过时需要自动同步`() {
        assertTrue(LedgerRepository.shouldAutoSync(null, now))
    }

    @Test
    fun `刚同步过不久不需要自动同步`() {
        assertFalse(LedgerRepository.shouldAutoSync(now - 60_000L, now))
        assertFalse(LedgerRepository.shouldAutoSync(now - 5 * hour, now))
    }

    @Test
    fun `超过默认阈值需要自动同步`() {
        assertTrue(LedgerRepository.shouldAutoSync(now - 7 * hour, now))
        assertTrue(LedgerRepository.shouldAutoSync(now - 24 * hour, now))
    }

    @Test
    fun `阈值边界：刚好到点算需要，差 1 毫秒则不需要`() {
        assertTrue(LedgerRepository.shouldAutoSync(now - 6 * hour, now))
        assertFalse(LedgerRepository.shouldAutoSync(now - 6 * hour + 1, now))
    }

    @Test
    fun `上次同步时间在未来（设备时钟回拨）也应当同步`() {
        assertTrue(LedgerRepository.shouldAutoSync(now + 3 * hour, now))
    }

    @Test
    fun `阈值可自定义`() {
        assertFalse(LedgerRepository.shouldAutoSync(now - 2 * hour, now, thresholdHours = 12))
        assertTrue(LedgerRepository.shouldAutoSync(now - 13 * hour, now, thresholdHours = 12))
    }

    @Test
    fun `默认阈值是 6 小时`() {
        assertEquals(6, LedgerRepository.AUTO_SYNC_INTERVAL_HOURS)
    }
}
