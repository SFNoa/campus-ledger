package com.morchid.ecardledger.notification

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.morchid.ecardledger.data.LedgerRepository
import com.morchid.ecardledger.data.NotificationLogEntry
import java.security.MessageDigest

/**
 * 监听微信/支付宝的支付通知，自动记账。
 *
 * 设计要点：
 *  1. **不碰微信/支付宝的服务器** —— 只读系统投递过来的通知内容。
 *     不用账号密码、不逆向它们的私有接口，所以不存在封号风险。
 *  2. **用户没开启的渠道不记账**（[LedgerRepository.isChannelEnabled]）。
 *  3. **解析失败不丢数据** —— 认不出金额的通知会以「待确认」存入，原文保留给用户补全。
 *  4. **任何异常都不许蹦** —— 监听服务崩了会连带影响系统通知，所以整体 try/catch。
 *  5. 同一分钟内的重复/更新通知按同一条处理（通知会刷新，不做去重会重复记账）。
 */
class PaymentNotificationService : NotificationListenerService() {

    /**
     * 整个服务复用一个仓库实例。
     * 不能每条通知都 new 一个 —— 那会反复打开 SQLite 连接而且从不释放。
     */
    private val repository: LedgerRepository by lazy { LedgerRepository(applicationContext) }

    override fun onDestroy() {
        runCatching { repository.close() }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        try {
            handle(notification)
        } catch (t: Throwable) {
            // 绝不能让监听服务抛出异常
            Log.w(TAG, "处理通知失败：${t.message}", t)
        }
    }

    /**
     * 服务刚连上（授权后、或从被系统解绑中恢复）时，下拉栏里可能还挂着刚才错过的支付通知。
     * 顺手把它们处理掉，比干等新通知强。
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        ListenerState.connected = true
        runCatching { repository.markListenerConnected() }
        runCatching {
            activeNotifications?.forEach { sbn ->
                runCatching { handle(sbn) }
            }
        }
    }

    /**
     * 被系统解绑时请求重绑。
     *
     * 国产 ROM 在省电/内存压力下会解绑通知监听，用户看到的现像是「突然不记账了」，
     * 而 App 那边完全无感。requestRebind 让系统尽快把服务接回来。
     */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        ListenerState.connected = false
        runCatching {
            requestRebind(ComponentName(this, PaymentNotificationService::class.java))
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val channel = PaymentNotificationParser.channelOf(sbn.packageName)
        if (channel == null) {
            // 不是我们关心的来源。用 debug 级别记一下，方便排查「为什么没记上账」
            Log.d(TAG, "忽略非支付来源通知：${sbn.packageName}")
            return
        }

        val extras = sbn.notification?.extras ?: return
        val title = firstNonBlank(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG),
        )
        // 金额**不一定在正文里**：有的版本塞在子标题、摘要、多行文本、甚至 ticker 里。
        // 以前只读 TEXT/BIG_TEXT，认不出金额的通知就白抓了 —— 这里把所有可能的位置都收进来，
        // 判断是不是支付仍然由发送方白名单/平台措辞把关，所以放宽正文来源是安全的。
        val body = buildList {
            add(extras.getCharSequence(Notification.EXTRA_TEXT))
            add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            add(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
            add(extras.getCharSequence(Notification.EXTRA_INFO_TEXT))
            add(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add(it) }
            add(sbn.notification?.tickerText)
        }
            .mapNotNull { it?.toString() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")

        if (title.isNullOrBlank() && body.isBlank()) return

        val channelId = channelIdOf(sbn)
        val repository = this.repository
        if (!repository.isChannelEnabled(channel)) {
            Log.d(TAG, "${sbn.packageName} 渠道未开启，忽略")
            return
        }

        val orderId = fingerprint(sbn.packageName, title, body, sbn.postTime)
        // 解码时把用户维护的白名单传进去 —— 标题不是支付来源、渠道也不在白名单、
        // 正文也没有支付措辞的，在这里就被判成 Ignore（聊天消息走的就是这条路）
        val result = PaymentNotificationParser.parse(
            packageName = sbn.packageName,
            title = title,
            text = body,
            senders = repository.senders(),
            channelId = channelId,
            channels = repository.channels(),
        )

        val verdict: String
        val reason: String

        when (result) {
            // 与支付无关（聊天消息、广告…）→ **什么都不记**。
            // 老实现把「解析不出来」也存成「待确认」，于是每条微信聊天消息都进了账本 ——
            // 这是真机反馈「任何微信消息都被抓取记录」的根因。
            NotificationParse.Ignore -> {
                Log.d(TAG, "与支付无关，忽略：${body.take(40)}")
                verdict = "已忽略"
                reason = "发送方不是支付来源，正文也没有支付措辞"
            }

            // 像支付但没金额（微信的红包/转账通知本身不带金额）→ 记成待确认，让用户补
            is NotificationParse.NeedsReview -> {
                val inserted = repository.addNotificationEntry(
                    channel = result.accountType,
                    amountCents = 0,
                    isIncome = false,
                    merchant = "待确认",
                    epochMillis = sbn.postTime,
                    orderId = orderId,
                    rawText = result.rawText,
                    needsReview = true,
                    note = result.reason,
                )
                Log.i(TAG, "待确认入库=$inserted reason=${result.reason}")
                verdict = "待确认"
                reason = result.reason
            }

            is NotificationParse.Parsed -> {
                val payment = result.payment
                val lowConfidence = payment.confidence == ParsedPayment.Confidence.LOW
                val inserted = repository.addNotificationEntry(
                    channel = payment.accountType,
                    amountCents = payment.amountCents,
                    isIncome = payment.isIncome,
                    merchant = payment.merchant,
                    epochMillis = sbn.postTime,
                    orderId = orderId,
                    rawText = payment.rawText,
                    needsReview = lowConfidence,
                    note = if (lowConfidence) "收支方向未确认，请核对" else "",
                )
                Log.i(TAG, "通知入库=$inserted channel=${payment.accountType} body=${body.take(60)}")
                verdict = "已记账"
                reason = (if (payment.isIncome) "收入 " else "支出 ") +
                    (payment.amountCents / 100.0) + " 元 · " + payment.merchant
            }
        }

        // 诊断记录（默认关闭，开了才写；只存本机）
        if (repository.isNotificationDiagnosticsOn()) {
            repository.appendNotificationLog(
                NotificationLogEntry(
                    timeMillis = sbn.postTime,
                    packageName = sbn.packageName,
                    title = title.orEmpty(),
                    text = body,
                    verdict = verdict,
                    reason = reason,
                    channelId = channelId,
                ),
            )
        }
    }

    /**
     * 通知渠道 id。
     * 这是个比标题更硬的结构性信号 —— 微信支付走的是它自己的渠道，
     * 和聊天消息不是一个渠道。诊断页会显示出来，方便排查和后续做渠道白名单。
     */
    internal fun channelIdOf(sbn: StatusBarNotification): String =
        runCatching { sbn.notification?.channelId.orEmpty() }.getOrDefault("")

    private fun firstNonBlank(vararg values: CharSequence?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.toString()

    /**
     * 内容指纹，用于去重。
     * 同一分钟内的相同内容只记一条 —— 通知被更新（比如「支付中」→「支付成功」）时会重复投递。
     */
    internal fun fingerprint(
        packageName: String,
        title: String?,
        body: String,
        postTime: Long,
    ): String {
        val bucket = postTime / 60_000L
        val raw = "$packageName|$title|$body|$bucket"
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
        return "notif-" + digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    private companion object {
        const val TAG = "PaymentNotif"
    }
}
