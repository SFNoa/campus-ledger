package com.morchid.ecardledger.data

import com.morchid.ecardledger.data.school.SchoolRegistry

/**
 * 频率限制的状态存储 + 决策入口。
 *
 * 决策逻辑全在 [SyncThrottlePolicy]（纯函数，可测）；这里只做两件事：
 *  1. 把状态落到 `meta` 表 —— 保证**重启 App 不会把限制重置掉**（否则用户重启就能绕过）
 *  2. 提供 [noteLoginAttempt] / [noteLoginFailure] 这类记账方法
 */
class SyncThrottle(
    private val db: LedgerDb,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun now(): Long = clock()

    private fun attempts(): List<Long> =
        db.getMeta(KEY_LOGIN_ATTEMPTS)
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.filter { it > 0L }
            ?: emptyList()

    private fun saveAttempts(list: List<Long>) {
        db.putMeta(KEY_LOGIN_ATTEMPTS, list.joinToString(","))
    }

    private fun failStreak(): Int = db.getMeta(KEY_FAIL_STREAK)?.toIntOrNull() ?: 0

    fun blockedUntil(): Long? = db.getMeta(KEY_BLOCKED_UNTIL)?.toLongOrNull()

    fun lastSyncMillis(): Long? = db.getMeta(KEY_LAST_SYNC_MS)?.toLongOrNull()

    private fun zone(): String = SchoolRegistry.byId(db.getMeta(KEY_SCHOOL))?.timeZoneId
        ?: SchoolRegistry.default.timeZoneId

    // ------------------------------------------------------------ 决策

    fun loginDecision(): SyncThrottlePolicy.Decision =
        SyncThrottlePolicy.decideLogin(now(), attempts(), failStreak(), blockedUntil())

    fun syncDecision(manual: Boolean): SyncThrottlePolicy.Decision =
        SyncThrottlePolicy.decideSync(now(), lastSyncMillis(), manual)

    // ------------------------------------------------------------ 记账

    /**
     * 记一次登录尝试。**必须在发请求之前调用** ——
     * 失败的尝试同样计入，否则限制形同虚设。
     */
    fun noteLoginAttempt() {
        val current = now()
        val pruned = (attempts() + current).filter { current - it < SyncThrottlePolicy.DAY_MS }
        saveAttempts(pruned)
    }

    fun noteLoginSuccess() {
        db.putMeta(KEY_FAIL_STREAK, "0")
        // 能登录成功就说明没被冻结
        db.putMeta(KEY_BLOCKED_UNTIL, "")
    }

    /** 失败：累计退避；若学校给了冻结时间，就冷却到那时候 */
    fun noteLoginFailure(message: String?) {
        db.putMeta(KEY_FAIL_STREAK, (failStreak() + 1).toString())
        SyncThrottlePolicy.parseFreezeUntil(message, zone())?.let { until ->
            db.putMeta(
                KEY_BLOCKED_UNTIL,
                SyncThrottlePolicy.withSafetyMargin(until).toString(),
            )
        }
    }

    fun noteSyncSuccess() {
        db.putMeta(KEY_LAST_SYNC_MS, now().toString())
    }

    /** 解除绑定 / 换学校时清空限制状态（用户明确要重来，不该被旧状态挡住） */
    fun reset() {
        saveAttempts(emptyList())
        db.putMeta(KEY_FAIL_STREAK, "0")
        db.putMeta(KEY_BLOCKED_UNTIL, "")
        db.putMeta(KEY_LAST_SYNC_MS, "")
    }

    private companion object {
        const val KEY_LOGIN_ATTEMPTS = "throttle_login_attempts"
        const val KEY_FAIL_STREAK = "throttle_fail_streak"
        const val KEY_BLOCKED_UNTIL = "throttle_blocked_until"
        const val KEY_LAST_SYNC_MS = "throttle_last_sync_ms"
        const val KEY_SCHOOL = "school_id"
    }
}
