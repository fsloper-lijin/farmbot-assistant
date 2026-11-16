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

        /** 单次截屏，回调在主线程 */
        fun capture(cb: (Bitmap?) -> Unit) {
            val s = inst
            if (s == null) { main.post { cb(null) }; return }
            s.doCapture { bmp -> main.post { cb(bmp) } }
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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        inst = this
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
