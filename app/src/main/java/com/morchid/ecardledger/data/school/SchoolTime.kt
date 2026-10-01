package com.morchid.ecardledger.data.school

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 学校时间的解析。
 *
 * 为什么需要单独处理：校园卡接口返回的是**服务端本地时间**的字符串，
 * 形如 `2026-09-28 12:02:13`，不带时区信息。
 *
 * 老做法是按**设备时区**解析，于是：
 *  - 手机时区设为中国时区 → 正确
 *  - 手机被设成其他时区（旅行、或某些模拟器默认 UTC）→ 所有同步记录的时间戳整体偏移，
 *    表现成「同步来的记录排到了今天手动记的记录前面」，而且内部转账的 ±2 小时配对窗口也会错位
 *
 * 正确做法是按**学校所在时区**解析，这样时间戳与设备设置无关。
 * 展示仍然直接用服务端给的字符串（就是学校的本地时间），不需要换算。
 */
object SchoolTime {

    private const val WALL_CLOCK_PATTERN = "yyyy-MM-dd HH:mm:ss"

    /** 把学校的墙上时间字符串解析成时间戳；解析不了返回 null */
    fun parseWallClock(text: String, timeZoneId: String): Long? = runCatching {
        SimpleDateFormat(WALL_CLOCK_PATTERN, Locale.CHINA)
            .apply { timeZone = TimeZone.getTimeZone(timeZoneId) }
            .parse(text)?.time
    }.getOrNull()

    /** 学校时区（非法 id 时回落到东八区，而不是悄悄用设备时区） */
    fun zoneOf(timeZoneId: String): TimeZone =
        runCatching { TimeZone.getTimeZone(timeZoneId) }
            .getOrNull()
            ?.takeIf { it.id == timeZoneId }
            ?: TimeZone.getTimeZone("Asia/Shanghai")
}
