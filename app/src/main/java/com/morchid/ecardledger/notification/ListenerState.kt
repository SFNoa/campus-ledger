package com.morchid.ecardledger.notification

/**
 * 通知监听服务当前是否被系统绑着。
 *
 * 服务和界面跑在**同一个进程**里，所以一个进程内的标记就够用，不需要跨进程通信。
 * 为什么需要它：MIUI 会出现"权限明明在授权列表里、但系统拒绝绑定服务"的情况
 * （日志：`AutoStartManagerService: MIUILOG- Reject service`，原因是自启动被关）。
 * 这种情况下 App 看起来一切正常却收不到任何通知 —— 必须能自检出来并提示用户。
 */
object ListenerState {
    @Volatile
    var connected: Boolean = false
}