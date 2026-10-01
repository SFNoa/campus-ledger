package com.morchid.ecardledger.data

import com.morchid.ecardledger.data.school.SchoolTime

/**
 * 学校登录 / 同步的频率限制策略（纯函数，方便测试）。
 *
 * ## 为什么必须有这个
 *
 * 中南大学的统一身份认证带风控。开发期间我反复跑联网测试，把账号触发了冻结，
 * 学校返回的原文是：
 *
 * > 该账号已被冻结，预计解冻时间：2026-09-28 15:44:30，冻结原因：**账号频繁访问**
 *
 * ## 两条硬性设计约束
 *
 * 1. **必须在发请求之前判断**。先登录、失败了再判断「是不是太频繁」是没用的 ——
 *    那次访问已经被服务端算进去了，正是它触发的风控。
 * 2. **冻结时间要照着学校的说法等**。学校已经明确给了「预计解冻时间」，
 *    继续重试只会延长冻结，所以直接冷却到那个时刻（再留一点余量）。
 */
object SyncThrottlePolicy {

    /** 两次登录尝试之间的最小间隔 */
    const val MIN_LOGIN_INTERVAL_MS = 30_000L

    /** 24 小时内的登录次数上限 */
    const val MAX_LOGINS_PER_DAY = 20

    /** 手动同步的最小间隔（防止连点） */
    const val MIN_MANUAL_SYNC_INTERVAL_MS = 60_000L

    /** 自动同步间隔：6 小时 */
    const val AUTO_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L

    /** 连续失败后的退避阶梯（第 1 次失败等 1 分钟，之后逐级拉长） */
    val BACKOFF_MS = longArrayOf(
        60_000L,
        5 * 60_000L,
        15 * 60_000L,
        60 * 60_000L,
    )

    const val DAY_MS = 24 * 60 * 60 * 1000L

    data class Decision(
        val allowed: Boolean,
        /** 允许重试的最早时刻；被拦时一定有值 */
        val retryAtMillis: Long? = null,
        val reason: String? = null,
    ) {
        companion object {
            val ALLOW = Decision(allowed = true)
        }
    }

    // ------------------------------------------------------------ 登录

    /**
     * 判断现在能不能发起一次登录。
     *
     * @param recentLoginAttempts 最近 24 小时内的登录尝试时间戳，可乱序
     * @param failStreak 连续失败次数（成功即清零）
     * @param blockedUntilMillis 已知的冻结到期时刻（从学校提示里解析出来的）
     */
    fun decideLogin(
        now: Long,
        recentLoginAttempts: List<Long>,
        failStreak: Int,
        blockedUntilMillis: Long?,
    ): Decision {
        // 1. 冻结冷却优先：学校给了明确时间，就老老实实等
        if (blockedUntilMillis != null && now < blockedUntilMillis) {
            return Decision(
                allowed = false,
                retryAtMillis = blockedUntilMillis,
                reason = "账号被学校风控冻结中",
            )
        }

        val lastAttempt = recentLoginAttempts.maxOrNull()

        // 2. 连续失败的退避
        if (failStreak > 0 && lastAttempt != null) {
            val backoff = BACKOFF_MS[(failStreak - 1).coerceIn(0, BACKOFF_MS.size - 1)]
            val retryAt = lastAttempt + backoff
            if (now < retryAt) {
                return Decision(
                    allowed = false,
                    retryAtMillis = retryAt,
                    reason = "上次登录失败",
                )
            }
        }

        // 3. 两次尝试之间的最小间隔
        if (lastAttempt != null && now - lastAttempt < MIN_LOGIN_INTERVAL_MS) {
            return Decision(
                allowed = false,
                retryAtMillis = lastAttempt + MIN_LOGIN_INTERVAL_MS,
                reason = "登录太频繁",
            )
        }

        // 4. 每日上限
        val withinDay = recentLoginAttempts.filter { now - it < DAY_MS }
        if (withinDay.size >= MAX_LOGINS_PER_DAY) {
            val oldest = withinDay.min()
            return Decision(
                allowed = false,
                retryAtMillis = oldest + DAY_MS,
                reason = "今天的登录次数已达上限（$MAX_LOGINS_PER_DAY 次）",
            )
        }

        return Decision.ALLOW
    }

    // ------------------------------------------------------------ 同步

    fun decideSync(now: Long, lastSyncMillis: Long?, manual: Boolean): Decision {
        if (lastSyncMillis == null) return Decision.ALLOW

        val interval = if (manual) MIN_MANUAL_SYNC_INTERVAL_MS else AUTO_SYNC_INTERVAL_MS
        if (now - lastSyncMillis < interval) {
            return Decision(
                allowed = false,
                retryAtMillis = lastSyncMillis + interval,
                reason = if (manual) "同步太频繁" else "还没到自动同步的时间",
            )
        }
        return Decision.ALLOW
    }

    // ------------------------------------------------------------ 冻结提示解析

    /**
     * 从学校的提示里解析「预计解冻时间」。
     *
     * 实测原文：
     * 「该账号已被冻结，预计解冻时间：2026-09-28 15:44:30，冻结原因：账号频繁访问；可通过账号解禁功能自助解冻。」
     */
    fun parseFreezeUntil(message: String?, timeZoneId: String): Long? {
        if (message.isNullOrBlank()) return null
        if (!message.contains("冻结") && !message.contains("解冻")) return null
        val raw = Regex("""预计解冻时间[：:]\s*(\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2})""")
            .find(message)?.groupValues?.get(1) ?: return null
        // 学校时间按学校时区解析（与同步流水同一套规则）
        return SchoolTime.parseWallClock(raw.replace('T', ' '), timeZoneId)
    }

    /**
     * 冻结到期时刻再加一点余量。
     * 卡在解冻那一秒重试很容易又踩到风控，所以多等一会儿。
     */
    fun withSafetyMargin(freezeUntil: Long, marginMillis: Long = 60_000L): Long =
        freezeUntil + marginMillis

    // ------------------------------------------------------------ 文案

    /** 人类可读的剩余时间，例如「3 分钟」「1 小时 20 分钟」 */
    fun describeRemaining(now: Long, retryAtMillis: Long): String {
        val remain = retryAtMillis - now
        if (remain <= 0) return "现在可以重试"
        val minutes = (remain + 59_999L) / 60_000L
        return if (minutes < 60) {
            "$minutes 分钟"
        } else {
            val hours = minutes / 60
            val mins = minutes % 60
            if (mins == 0L) "$hours 小时" else "$hours 小时 $mins 分钟"
        }
    }

    /** 被拦时的完整说明，直接可以显示给用户 */
    fun describeDecision(now: Long, decision: Decision): String {
        val retryAt = decision.retryAtMillis
        val base = decision.reason ?: "暂时不能操作"
        return if (retryAt == null) {
            base
        } else {
            "$base，请 ${describeRemaining(now, retryAt)}后再试"
        }
    }

    /** 冻结的专门文案：要说清是学校的风控，而不是 App 出错 */
    fun describeFreeze(now: Long, untilMillis: Long): String =
        "账号被学校风控临时冻结，${describeRemaining(now, untilMillis)}后可重试。" +
            "这是学校对短时间内频繁登录的保护机制，不是 App 出错。"
}
