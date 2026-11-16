package com.farmbot.assistant.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.farmbot.assistant.core.IO
import kotlin.math.abs

/**
 * 运行状态悬浮窗。
 *
 * 用 TYPE_APPLICATION_OVERLAY（API 26+）盖在王者荣耀上面，
 * 不加 FLAG_NOT_FOCUSABLE 之外的可聚焦标志，保证不影响游戏触摸。
 * 用 applicationContext + WindowManager 直接挂载，不依赖 Activity 生命周期。
 */
object FloatingOverlay {

    private const val TAG = "Overlay"
    private const val PREFS = "overlay_pos"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"

    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private val main = Handler(Looper.getMainLooper())

    val isShowing: Boolean get() = root != null

    fun show(ctx: Context, text: String = "运行中"): Boolean {
        val app = ctx.applicationContext
        if (root != null) {
            setText(text)
            return true
        }
        if (!Settings.canDrawOverlays(app)) {
            IO.log(app, TAG, "未授予悬浮窗权限，跳过显示")
            return false
        }
        val d = app.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val pill = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xE60B0E14.toInt())
                setStroke(dp(1), 0x554DE1E1)
            }
            alpha = 0.90f
        }

        val dot = View(app).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF4DE1E1.toInt())
            }
        }
        pill.addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(7) })

        val label = TextView(app).apply {
            this.text = text
            setTextColor(0xFFE9EEF7.toInt())
            textSize = 11f
        }
        pill.addView(label)

        // 关闭按钮：之前用长按隐藏，但拖拽起始的按住动作会被判定成长按，导致一拖就消失
        val close = TextView(app).apply {
            this.text = "✕"
            setTextColor(0xFF8C9AB0.toInt())
            textSize = 10f
            setPadding(dp(8), 0, 0, 0)
            setOnClickListener { hide(app) }
        }
        pill.addView(close)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 有保存过的位置就沿用，否则默认贴左边缘、垂直居中并略微沉入
            val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            val dm = app.resources.displayMetrics
            if (sp.contains(KEY_X)) {
                x = sp.getInt(KEY_X, 0)
                    .coerceIn(-dp(30), dm.widthPixels - dp(30))
                y = sp.getInt(KEY_Y, 0)
                    .coerceIn(-dm.heightPixels / 2 + dp(40), dm.heightPixels / 2 - dp(40))
            } else {
                x = -dp(5)
                y = 0
            }
        }

        pill.setOnTouchListener(
            DragTouch(manager, lp) { nx, ny ->
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_X, nx).putInt(KEY_Y, ny).apply()
                IO.log(app, TAG, "位置已保存 ($nx,$ny)")
            }
        )

        // 点左侧（圆点+文字区域）回到助手；右侧 ✕ 是子 View，点击不会冒泡到这里
        pill.setOnClickListener {
            val i = Intent(app, com.farmbot.assistant.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            runCatching { app.startActivity(i) }
                .onSuccess { IO.log(app, TAG, "点击悬浮窗，返回助手") }
                .onFailure { IO.log(app, TAG, "返回助手失败: ${it.message}") }
        }

        val ok = runCatching { manager.addView(pill, lp) }.isSuccess
        IO.log(app, TAG, "显示悬浮窗 ok=$ok")
        if (!ok) return false

        wm = manager
        root = pill
        params = lp
        return true
    }

    fun setText(text: String) {
        val pill = root as? LinearLayout ?: return
        for (i in 0 until pill.childCount) {
            val t = pill.getChildAt(i) as? TextView ?: continue
            t.text = text
            break
        }
    }

    fun hide(ctx: Context? = null) {
        val v = root ?: return
        val m = wm
        runCatching { m?.removeView(v) }
        IO.log(ctx?.applicationContext ?: v.context, TAG, "悬浮窗已移除")
        root = null
        params = null
        wm = null
    }

    /** 延迟显示，给游戏一点启动时间，避免只在助手界面上闪一下 */
    fun showDelayed(ctx: Context, delayMs: Long = 1200, text: String = "运行中") {
        main.postDelayed({ show(ctx, text) }, delayMs)
    }

    private class DragTouch(
        private val manager: WindowManager,
        private val lp: WindowManager.LayoutParams,
        private val onDrop: (Int, Int) -> Unit,
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var originX = 0
        private var originY = 0
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX.toInt()
                    startY = e.rawY.toInt()
                    originX = lp.x
                    originY = lp.y
                    moved = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX.toInt() - startX
                    val dy = e.rawY.toInt() - startY
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    if (moved) {
                        lp.x = originX + dx
                        lp.y = originY + dy
                        runCatching { manager.updateViewLayout(v, lp) }
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (moved) onDrop(lp.x, lp.y)
                }
            }
            return moved
        }
    }
}
