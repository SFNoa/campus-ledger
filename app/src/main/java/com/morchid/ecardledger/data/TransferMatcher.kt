package com.morchid.ecardledger.data

import kotlin.math.abs

/**
 * 「内部转账」配对。
 *
 * 为什么需要它：一旦同时记录微信/支付宝和校园卡，一笔「微信 → 校园卡充值」会出现两次 ——
 * 微信侧是一次支出、校园卡侧是一次收入。如果直接相加，总支出和总收入**都会被虚增**。
 * 配对之后两条记录都标记为转账，只影响各自账户余额，**不计入收支统计**。
 *
 * 判定依据（按可靠性排序）：
 *  1. 方向必须相反（一边支出、一边收入）
 *  2. 金额必须完全相等（都是「分」，精确比较，不做容差）
 *  3. 时间必须接近（默认 ±2 小时；充值一般即时到账，但服务端入账可能滞后）
 *  4. 账户必须不同（同一账户里的收支不是转账）
 *  5. 渠道要对得上 —— 校园卡的充值记录里本来就写着「微信支付转账」这类文案，
 *     所以「微信 → 校园卡」可以被确认；两边都判断不出渠道时退化为只靠 1~4 条
 *
 * 全部是纯函数，便于单测。
 */
object TransferMatcher {

    /** 默认配对时间窗：±2 小时 */
    const val DEFAULT_WINDOW_MILLIS = 2 * 60 * 60 * 1000L

    /** 各渠道在校园卡文案里可能出现的关键词（实测「微信支付转账」命中第一条） */
    private val CHANNEL_KEYWORDS: Map<AccountType, List<String>> = mapOf(
        AccountType.WECHAT to listOf("微信", "weixin"),
        AccountType.ALIPAY to listOf("支付宝", "alipay"),
    )

    /** 一笔记录属于哪个第三方支付渠道；校园卡等返回 null */
    fun channelOf(accountId: String): AccountType? = when (accountId) {
        AccountIds.WECHAT -> AccountType.WECHAT
        AccountIds.ALIPAY -> AccountType.ALIPAY
        else -> null
    }

    /**
     * 在候选里为 [new] 找配对对象；找到时间最接近的那一笔。
     * 找不到返回 null。
     */
    fun findCounterpart(
        new: LedgerEntry,
        candidates: List<LedgerEntry>,
        windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    ): LedgerEntry? = candidates
        .filter { isPair(new, it, windowMillis) }
        .minByOrNull { abs(it.epochMillis - new.epochMillis) }

    fun isPair(
        a: LedgerEntry,
        b: LedgerEntry,
        windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    ): Boolean {
        if (a.orderId == b.orderId) return false
        if (a.accountId == b.accountId) return false              // 同账户内不是转账
        if (a.isTransfer || b.isTransfer) return false            // 已配过对
        if (a.transferGroupId != null || b.transferGroupId != null) return false
        if (a.isIncome == b.isIncome) return false                // 方向必须相反
        if (a.amountCents != b.amountCents) return false          // 金额精确相等
        if (a.amountCents <= 0L) return false
        if (abs(a.epochMillis - b.epochMillis) > windowMillis) return false
        return channelMatches(a, b)
    }

    /**
     * 渠道是否一致。
     *  - 两边都是第三方账户：必须同一渠道（微信↔支付宝不算转账）
     *  - 只有一边是：要求另一边的文案里出现该渠道关键词
     *  - 两边都不是（例如两张校园卡、或手动记的记录）：无法判断，放行
     */
    fun channelMatches(a: LedgerEntry, b: LedgerEntry): Boolean {
        val hintA = channelOf(a.accountId)
        val hintB = channelOf(b.accountId)

        if (hintA == null && hintB == null) return true
        if (hintA != null && hintB != null) return hintA == hintB

        val thirdParty = hintA ?: hintB!!
        val other = if (hintA != null) b else a
        val keywords = CHANNEL_KEYWORDS[thirdParty].orEmpty()
        val haystack = buildString {
            append(other.payName); append(' ')
            append(other.merchant); append(' ')
            append(other.kind); append(' ')
            append(other.rawText)
        }.lowercase()
        return keywords.any { haystack.contains(it.lowercase()) }
    }
}
