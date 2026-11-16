package com.farmbot.assistant.bot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors

/**
 * 唯一的系统接入点。
 *  - 眼睛：takeScreenshot（API 30+，无障碍专用，免录屏授权）
 *  - 手　：dispatchGesture（点击 / 长按 / 拖动）
 *  - 锚点：performGlobalAction（返回键复位）
 */
class BotService : AccessibilityService() {

    companion object {
        @Volatile
        private var inst: BotService? = null

        private val io = Executors.newSingleThreadExecutor()
        private val main = Handler(Looper.getMainLooper())

        val isReady: Boolean get() = inst != null

        /**
         * HyperOS 对无障碍截屏做了间隔限制（实测 1.2s 间隔必定返回 errorCode=1
         * 「INTERVAL_TIME_SHORT」，且每次失败也会刷新系统侧时间戳，于是会出现
         * 失败→1.2s 后重试→再失败的死循环）。这里统一限流：两次请求至少间隔 1.6s，
         * 撞上限流（errorCode=1）时再退避重试两次。
         */
        private const val MIN_SHOT_GAP = 1600L
        private const val SHOT_RETRY_GAP = 1200L
        private const val SHOT_RETRIES = 2
        private var lastShotAt = 0L

        /** 单次截屏，回调在主线程（内部限流 + 失败退避重试） */
        fun capture(cb: (Bitmap?) -> Unit) = shoot(cb, 0)

        private fun shoot(cb: (Bitmap?) -> Unit, retries: Int) {
            val wait = (lastShotAt + MIN_SHOT_GAP - android.os.SystemClock.uptimeMillis())
                .coerceAtLeast(0L)
            main.postDelayed({ shootNow(cb, retries) }, wait)
        }

        private fun shootNow(cb: (Bitmap?) -> Unit, retries: Int) {
            val s = inst
            if (s == null) { main.post { cb(null) }; return }
            lastShotAt = android.os.SystemClock.uptimeMillis()
            s.doCapture { bmp ->
                when {
                    bmp != null -> main.post { cb(bmp) }
                    retries < SHOT_RETRIES ->
                        main.postDelayed({ shoot(cb, retries + 1) }, SHOT_RETRY_GAP)
                    else -> main.post { cb(null) }
                }
            }
        }

        fun tap(x: Int, y: Int, durationMs: Long = 60L, cb: ((Boolean) -> Unit)? = null) {
            val s = inst
            if (s == null) { cb?.invoke(false); return }
            s.doStroke(x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), durationMs, cb)
        }

        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300L, cb: ((Boolean) -> Unit)? = null) {
            val s = inst
            if (s == null) { cb?.invoke(false); return }
            s.doStroke(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs, cb)
        }

        fun back() { inst?.performGlobalAction(GLOBAL_ACTION_BACK) }
        fun home() { inst?.performGlobalAction(GLOBAL_ACTION_HOME) }
        fun recents() { inst?.performGlobalAction(GLOBAL_ACTION_RECENTS) }

        /** 当前前台包名，用于确认画面到底是谁 */
        fun foregroundPackage(): String? = runCatching {
            inst?.rootInActiveWindow?.packageName?.toString()
        }.getOrNull()

        /** 无障碍节点树根，供 Nodes 做设置页点击 */
        fun rootNode(): android.view.accessibility.AccessibilityNodeInfo? = runCatching {
            inst?.rootInActiveWindow
        }.getOrNull()

        /** 所有无障碍窗口的根节点（QQ/微信 授权小窗浮在游戏上，不在 rootInActiveWindow 内）。
         *  排除助手自己的悬浮窗，否则进度提示「正在登录「小号」…」会被当成真实按钮/身份行。 */
        fun windowRoots(): List<android.view.accessibility.AccessibilityNodeInfo> = runCatching {
            inst?.windows?.mapNotNull { it.root }
                ?.filter { it.packageName?.toString() != "com.farmbot.assistant" }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        inst = this
        // FLAG_RETRIEVE_INTERACTIVE_WINDOWS 已在 accessibility_service_config.xml 里声明；
        // 这里不要再重写 serviceInfo —— 那会把系统下发的配置整个替换掉，
        // 是应用侧唯一会碰服务配置的地方，没必要冒这个险。
        android.util.Log.i("FarmBot", "accessibility service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (inst === this) inst = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun doCapture(cb: (Bitmap?) -> Unit) {
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, io, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hb = screenshot.hardwareBuffer
                    val bmp = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    hb.close()
                    cb(bmp)
                }

                override fun onFailure(errorCode: Int) {
                    android.util.Log.w("FarmBot", "takeScreenshot failed: $errorCode")
                    cb(null)
                }
            })
        } catch (t: Throwable) {
            android.util.Log.e("FarmBot", "takeScreenshot threw", t)
            cb(null)
        }
    }

    private fun doStroke(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long, cb: ((Boolean) -> Unit)?) {
        val path = Path().apply {
            moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, maxOf(16L, dur)))
            .build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { cb?.invoke(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { cb?.invoke(false) }
        }, null)
        if (!ok) cb?.invoke(false)
    }
}
