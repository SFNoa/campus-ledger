package com.morchid.ecardledger.notification

import com.morchid.ecardledger.data.AccountType

/** 解析出来的一笔支付 */
data class ParsedPayment(
    val accountType: AccountType,
    val amountCents: Long,
    val isIncome: Boolean,
    val merchant: String,
    val rawText: String,
    val confidence: Confidence,
) {
    enum class Confidence { HIGH, LOW }
}

/**
 * 一条通知的解析结果。
 *
 * **三态是必需的**，不能只有「解析成功 / null」两种：
 * 老实现把「解析不出来」和「跟支付无关」混为一谈，于是
 * **每一条微信聊天消息都被存成了一笔「待确认」账目**（真机反馈的问题）。
 */
sealed interface NotificationParse {

    /** 跟支付无关（聊天消息、广告、验证码…）—— **什么都不记** */
    data object Ignore : NotificationParse

    /**
     * 像是支付通知，但金额没认出来 → 存成「待确认」，让用户补。
     * 比如微信的「[转账]」「[微信红包]」通知**本身不带金额**。
     */
    data class NeedsReview(
        val accountType: AccountType,
        val rawText: String,
        val reason: String,
    ) : NotificationParse

    /** 认出来了 */
    data class Parsed(val payment: ParsedPayment) : NotificationParse
}

/**
 * 微信/支付宝通知解析。
 *
 * ## 判定顺序（**发送方优先**，这是真机反馈纠正过来的）
 *
 * 用户的原话：「一般消息会有昵称，而支付的消息会有微信支付字样，不能通过这些差别先筛一道吗」
 * —— 完全正确。聊天通知和支付通知在**通知结构**上就是两类东西：
 *
 * | | 标题（发送方） | 正文 |
 * |---|---|---|
 * | 聊天消息 | 联系人昵称 / 群名 | 消息内容 |
 * | 微信支付 | 微信支付 | 已支付¥25.50 |
 *
 * 所以顺序是：
 *  1. 命中忽略词 → [NotificationParse.Ignore]
 *  2. 命中「[转账]/[微信红包]」这类系统标记 → [NotificationParse.NeedsReview]（这类通知不带金额）
 *  3. **发送方在白名单里**，或正文含平台特有措辞（已支付/支付凭证/收款到账…）
 *     → 才去解析金额；解析不出金额也算 [NotificationParse.NeedsReview]
 *  4. 其余一律 [NotificationParse.Ignore] —— **聊天消息在第 3 步就被挡掉了**
 *
 * 白名单可以由用户增删（见 `LedgerRepository.senders()`），
 * 因为**没人能凭空知道每个微信版本把支付通知的标题写成什么** ——
 * 配合「通知诊断」页，看到真实标题就能一键加入白名单。
 */
object PaymentNotificationParser {

    /** 支持的通知来源；接入更多渠道往这里加 */
    val SUPPORTED_PACKAGES: Map<String, AccountType> = mapOf(
        "com.tencent.mm" to AccountType.WECHAT,
        "com.eg.android.AlipayGphone" to AccountType.ALIPAY,
    )

    fun channelOf(packageName: String?): AccountType? = SUPPORTED_PACKAGES[packageName ?: ""]

    // ------------------------------------------------------------ 发送方白名单

    /** 预置的支付通知发送方（标题里出现即认为是支付通知） */
    val DEFAULT_SENDERS: List<String> = listOf(
        "微信支付",
        "微信收款助手",
        "微信支付凭证",
        "支付宝",
        "支付宝支付",
    )

    /**
     * 平台特有措辞：即使标题不是白名单里的发送方，正文出现这些也认为是支付。
     * 这是防御性的第二道 —— 有些版本可能不在标题里写「微信支付」。
     * 这些词在日常聊天里几乎不会出现（不像「转账」「收款」那样随便就说）。
     *
     * 真机反馈补充：**微信退款**和**支付宝支出**当时认不出金额，原因有两个 ——
     *  a. 它们的标题不是白名单里的发送方，而正文只写了「N 元」（没有 ¥ 符号），
     *     老规则只允许「发送方可信」时才认这种写法 → 只能落成待确认；
     *  b. 退款通知常写的「退款到账」「原路退回」当时不在措辞表里 → 整条被忽略。
     * 现在两条都补上了，并且这类只靠措辞认出来的会标成**待确认**让用户核对一遍。
     */
    private val PLATFORM_PHRASES = listOf(
        "支付成功", "付款成功", "已支付", "支付凭证", "交易成功", "扣款成功",
        "收款到账", "已收款", "退款成功", "已退款", "微信支付凭证", "交易提醒",
        "退款到账", "退款", "已退", "退回", "原路退",
    )

    /** 微信系统生成的、但**不带金额**的标记（红包/转账通知就是这样） */
    private val AMOUNTLESS_MARKERS = listOf(
        "[转账]", "[微信红包]", "[红包]", "转账待收款", "待收款", "已被退还",
    )

    /**
     * 通知**类型**词：不管来源多可信，这些都不是一笔钱。
     * 支付宝会在「支付宝」这个标题下发登录提醒 —— 标题可信不代表内容是一笔支付。
     */
    private val IGNORE_ALWAYS = listOf(
        "余额提醒", "账单提醒", "还款提醒", "安全提醒", "登录提醒", "身份验证", "验证码",
    )

    /**
     * 「可能出现在支付通知正文里」的词 —— **只在来源不可信时**才用来过滤。
     *
     * 真机反馈：微信支付的通知常写「已优惠 ¥1.00」，老规则命中「优惠」就把整条通知丢了，
     * 表现就是「有的微信/支付宝支付金额读不出来」。所以这些词不能一见就杀。
     */
    private val IGNORE_WHEN_UNTRUSTED = listOf("优惠", "红包封面", "更新", "版本")

    // ------------------------------------------------------------ 金额

    /** 强金额写法：带 ¥ 符号，支付通知都用这种 */
    private val STRONG_AMOUNT_PATTERNS = listOf(
        Regex("""[¥￥]\s*(\d{1,9}(?:\.\d{1,2})?)"""),
        Regex("""人民币\s*(\d{1,9}(?:\.\d{1,2})?)"""),
    )

    /** 弱金额写法：「N 元」。聊天里太常见，只在发送方可信时才采信 */
    private val WEAK_AMOUNT_PATTERNS = listOf(
        Regex("""(\d{1,9}(?:\.\d{1,2})?)\s*元"""),
        Regex("""金额[：:]\s*(\d{1,9}(?:\.\d{1,2})?)"""),
    )

    /** 最弱：正文里单独一个「12.00」。只在来源可信时兜底用 */
    private val BARE_AMOUNT_PATTERN = Regex("""(?:^|[\s，,。:：])(\d{1,9}\.\d{2})(?=$|[\s，,。])""")

    // ------------------------------------------------------------ 商户

    private val MERCHANT_PATTERNS = listOf(
        Regex("""向\s*[「“"']?([^「」“”"',，。;；]{1,30}?)[」”"']?\s*(?:付款|支付|转账)"""),
        Regex("""在\s*[「“"']?([^「”"',，。;；]{1,30}?)[」”"']?\s*(?:消费|付款|支付)"""),
        Regex("""[「“]([^」”]{1,30})[」”]\s*(?:付款|支付|消费)"""),
    )

    /**
     * 渠道品牌词要先剔掉再判断方向。
     * 「微信支付」四个字里含「支付」，不剔除的话所有微信通知都会被判成支出。
     */
    private val BRAND_TOKENS = listOf("微信支付", "支付宝", "微信", "Alipay", "WeChat", "alipay")

    private fun stripBrands(text: String): String {
        var result = text
        BRAND_TOKENS.forEach { result = result.replace(it, " ") }
        return result
    }

    // ------------------------------------------------------------ 入口

    /**
     * @param senders 发送方白名单；调用方从仓库取（用户可增删）。不传则用预置的。
     */
    fun parse(
        packageName: String?,
        title: String?,
        text: String?,
        senders: Collection<String> = DEFAULT_SENDERS,
        channelId: String? = null,
        channels: Collection<String> = emptyList(),
    ): NotificationParse {
        val channel = channelOf(packageName) ?: return NotificationParse.Ignore
        val body = listOfNotNull(title, text).joinToString(" ").trim()
        if (body.isEmpty()) return NotificationParse.Ignore

        // 先判断来源可不可信 —— 后面几条规则都取决于它
        val senderTrusted = isTrustedSender(title, senders) ||
            isTrustedChannel(channelId, channels)
        val platformPhrase = PLATFORM_PHRASES.any { body.contains(it) }

        // 通知类型词：不管来源多可信都不是钱（支付宝会在「支付宝」标题下发登录提醒）
        if (IGNORE_ALWAYS.any { body.contains(it) }) return NotificationParse.Ignore

        // 忽略词**只在来源不可信时生效**：它们是用来挡聊天/广告的。
        // 真机反馈：微信支付的通知里常写「已优惠 ¥1.00」，老规则命中「优惠」把整条通知丢了，
        // 表现就是「有的微信/支付宝支付金额读不出来」。
        if (!senderTrusted && IGNORE_WHEN_UNTRUSTED.any { body.contains(it) }) {
            return NotificationParse.Ignore
        }

        // 红包/转账：系统标记，不带金额，记成待确认让用户补
        if (AMOUNTLESS_MARKERS.any { body.contains(it) }) {
            return NotificationParse.NeedsReview(
                accountType = channel,
                rawText = body,
                reason = "这类通知（红包/转账）本身不带金额，需要你手动补上",
            )
        }

        // **发送方优先的硬门槛**：标题不是支付发送方、渠道也不在白名单、正文也没有平台措辞
        // → 不是支付，直接忽略。聊天消息三关都过不了，在这里被剔除。
        if (!senderTrusted && !platformPhrase) return NotificationParse.Ignore

        // 只要发送方可信、**或**命中平台措辞，就允许「N 元」这种写法。
        // 真机反馈：支付宝支出、微信退款常写成「12.00元」而不带 ¥ 符号，
        // 老规则在这种通知上认不出金额，全落成了待确认。
        // 来源可信时再放宽一档：允许正文里孤零零一个「12.00」（有些版本真就这么写）。
        val amountCents = extractAmountCents(body, allowWeakPatterns = true, allowBareNumber = senderTrusted)

        if (amountCents == null || amountCents <= 0L) {
            return NotificationParse.NeedsReview(
                accountType = channel,
                rawText = body,
                reason = "没能自动识别金额，需要你手动补上",
            )
        }

        val direction = detectDirection(body)
        return NotificationParse.Parsed(
            ParsedPayment(
                accountType = channel,
                amountCents = amountCents,
                isIncome = direction == true,
                merchant = extractMerchant(body, channel),
                rawText = body,
                // 只靠正文措辞认出来的（发送方不可信）、或方向不明的 → 标成待确认让用户核对
                confidence = if (direction == null || !senderTrusted) {
                    ParsedPayment.Confidence.LOW
                } else {
                    ParsedPayment.Confidence.HIGH
                },
            ),
        )
    }

    // ------------------------------------------------------------ 内部实现

    /** 标题里是否出现了白名单中的发送方 */
    internal fun isTrustedSender(title: String?, senders: Collection<String>): Boolean {
        val t = title.orEmpty()
        if (t.isBlank()) return false
        return senders.any { it.isNotBlank() && t.contains(it) }
    }

    /**
     * 渠道是否在白名单里。
     *
     * 比标题更硬的结构性信号：微信支付的通知走独立渠道，和聊天消息不是一个渠道。
     * 精确匹配 —— 渠道 id 是 `channel_id` 这种稳定标识，做包含匹配反而会误伤。
     */
    internal fun isTrustedChannel(channelId: String?, channels: Collection<String>): Boolean {
        val id = channelId.orEmpty().trim()
        if (id.isEmpty()) return false
        return channels.any { it.isNotBlank() && it.trim().equals(id, ignoreCase = true) }
    }

    internal fun extractAmountCents(
        text: String,
        allowWeakPatterns: Boolean = true,
        /** 来源可信时才允许「正文里孤零零一个 12.00」这种写法 */
        allowBareNumber: Boolean = false,
    ): Long? {
        for (pattern in STRONG_AMOUNT_PATTERNS) {
            val cents = pattern.find(text)?.groupValues?.get(1)?.let { yuanToCents(it) }
            if (cents != null && cents > 0L) return cents
        }
        if (!allowWeakPatterns) return null
        for (pattern in WEAK_AMOUNT_PATTERNS) {
            val cents = pattern.find(text)?.groupValues?.get(1)?.let { yuanToCents(it) }
            if (cents != null && cents > 0L) return cents
        }
        if (!allowBareNumber) return null
        // 最后一道：来源可信（微信支付/支付宝/用户白名单渠道）时，正文里单独一个两位小数
        // 基本就是金额。要求前后是边界，避免把订单号、手机号里的数字当成钱。
        val cents = BARE_AMOUNT_PATTERN.find(text)?.groupValues?.get(1)?.let { yuanToCents(it) }
        return if (cents != null && cents > 0L) cents else null
    }

    /** 用字符串处理避免浮点误差："12.34" → 1234 */
    private fun yuanToCents(raw: String): Long? = runCatching {
        val parts = raw.split('.')
        val yuan = parts[0].toLongOrNull() ?: return@runCatching null
        val fraction = parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0L
        yuan * 100 + fraction
    }.getOrNull()

    /** @return true=收入 false=支出 null=判断不出 */
    internal fun detectDirection(text: String): Boolean? {
        val cleaned = stripBrands(text)
        val income = INCOME_KEYWORDS.any { cleaned.contains(it) }
        val expense = EXPENSE_KEYWORDS.any { cleaned.contains(it) }
        return when {
            income && !expense -> true
            expense && !income -> false
            income && expense -> {
                val firstIncome = INCOME_KEYWORDS
                    .mapNotNull { k -> cleaned.indexOf(k).takeIf { it >= 0 } }.minOrNull()
                val firstExpense = EXPENSE_KEYWORDS
                    .mapNotNull { k -> cleaned.indexOf(k).takeIf { it >= 0 } }.minOrNull()
                when {
                    firstIncome == null -> false
                    firstExpense == null -> true
                    else -> firstIncome < firstExpense
                }
            }
            else -> null
        }
    }

    private val INCOME_KEYWORDS = listOf(
        "收款", "到账", "已收", "收入", "退款", "退回", "转入", "收到",
    )

    private val EXPENSE_KEYWORDS = listOf(
        "支付成功", "付款成功", "已支付", "支出", "消费", "扣款", "转出",
        "付款", "已付", "支付",
    )

    internal fun extractMerchant(text: String, channel: AccountType): String {
        for (pattern in MERCHANT_PATTERNS) {
            val name = pattern.find(text)?.groupValues?.get(1)?.trim()
            if (!name.isNullOrEmpty()) return name
        }
        return when (channel) {
            AccountType.WECHAT -> "微信支付"
            AccountType.ALIPAY -> "支付宝"
            else -> "未知商户"
        }
    }
}
