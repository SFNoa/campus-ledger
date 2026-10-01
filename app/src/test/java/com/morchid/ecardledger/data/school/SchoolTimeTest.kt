package com.morchid.ecardledger.data.school

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 学校时间解析的测试。
 *
 * 这里的重点是钉死一个**真实存在过的隐患**：
 * 服务端返回的时间字符串不带时区，早先按「设备时区」解析，
 * 于是设备时区一变（旅行、或模拟器默认 UTC），同步记录的时间戳就整体偏移，
 * 连带把内部转账的 ±2 小时配对窗口也弄错。
 */
class SchoolTimeTest {

    private fun formatIn(epoch: Long, zoneId: String): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            .apply { timeZone = TimeZone.getTimeZone(zoneId) }
            .format(Date(epoch))

    @Test
    fun `按学校时区把墙上时间解析成正确的时间戳`() {
        val epoch = SchoolTime.parseWallClock("2026-09-28 12:02:13", "Asia/Shanghai")!!
        // 北京时间 12:02:13 就是 UTC 04:02:13
        assertEquals("2026-09-28 04:02:13", formatIn(epoch, "UTC"))
    }

    @Test
    fun `解析结果与设备时区无关`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val inChina = SchoolTime.parseWallClock("2026-09-28 12:02:13", "Asia/Shanghai")

            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val inUtc = SchoolTime.parseWallClock("2026-09-28 12:02:13", "Asia/Shanghai")

            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val inNewYork = SchoolTime.parseWallClock("2026-09-28 12:02:13", "Asia/Shanghai")

            assertEquals("设备在 UTC 时不该变", inChina, inUtc)
            assertEquals("设备在纽约时也不该变", inChina, inNewYork)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `旧做法会整整错 8 小时——证明这个修复是必要的`() {
        val correct = SchoolTime.parseWallClock("2026-09-28 12:02:13", "Asia/Shanghai")!!
        // 旧做法：按设备/UTC 时区解析同一个字符串
        val wrong = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse("2026-09-28 12:02:13")!!.time

        assertEquals(
            "按 UTC 解析会比真实时刻晚 8 小时，足以让 2 小时的配对窗口完全失效",
            8 * 3600_000L,
            wrong - correct,
        )
    }

    @Test
    fun `时区非法时回落到东八区，而不是悄悄用设备时区`() {
        assertEquals("Asia/Shanghai", SchoolTime.zoneOf("Not/AZone").id)
        assertEquals("Asia/Shanghai", SchoolTime.zoneOf("").id)
    }

    @Test
    fun `合法时区原样返回`() {
        assertEquals("Asia/Shanghai", SchoolTime.zoneOf("Asia/Shanghai").id)
        assertEquals("Asia/Tokyo", SchoolTime.zoneOf("Asia/Tokyo").id)
    }

    @Test
    fun `解析不了的字符串返回 null 而不是抛异常`() {
        assertNull(SchoolTime.parseWallClock("", "Asia/Shanghai"))
        assertNull(SchoolTime.parseWallClock("不是时间", "Asia/Shanghai"))
    }

    @Test
    fun `中南大学的配置带上了时区`() {
        assertEquals("Asia/Shanghai", SchoolRegistry.CSU.timeZoneId)
    }
}
