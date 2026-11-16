package com.farmbot.assistant.bot

import androidx.compose.runtime.mutableStateOf

/**
 * 全局自动化开关 / 取消令牌。
 *
 * 所有会「自己往下跑」的流程（引导加账号、自动登录、扫描区服）启动时领一个 token，
 * 每个轮询 tick 用 [alive] 检查一次；点「停止」后 token 立即失效，循环在下一拍自行退出。
 * 只有 tick 边界才会停，已经在飞的截屏/OCR 回调最多再走完当前这一拍。
 */
object FlowControl {

    /** 当前 token；每开始一次自增，停止时也自增，让旧循环立刻失效 */
    @Volatile
    private var token = 0

    /** 是否有自动化在跑（Compose 可直接观察，用于「停止」按钮的可用状态） */
    val running = mutableStateOf(false)

    /** 正在跑的是什么，用于界面/日志 */
    val label = mutableStateOf("")

    fun begin(what: String): Int {
        token++
        label.value = what
        running.value = true
        return token
    }

    /** 流程正常结束：只有自己这一轮的 token 有效时才收起状态 */
    fun end(t: Int) {
        if (t != token) return
        running.value = false
        label.value = ""
    }

    /** 强制停止：作废所有在跑的循环 */
    fun cancel() {
        token++
        running.value = false
        label.value = ""
    }

    fun alive(t: Int): Boolean = t == token && running.value
}
