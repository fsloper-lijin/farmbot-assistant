package com.farmbot.assistant.bot

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.farmbot.assistant.core.IO
import com.farmbot.assistant.ocr.Ocr
import com.farmbot.assistant.perm.Perms

/**
 * 退出王者农场回大厅（用户口述）：左上角箭头 → 弹「请选择需要返回的场景」→ 点「返回大厅」。
 *
 * 弹窗里还有「返回稷下学院」，只认「返回大厅」；箭头是图标没有文字，按屏幕比例点
 * （真机 2880×1800 实测 0.1375w、0.375h，和换区面板的返回钩子同一带）。
 * 回到大厅以底部导航（英雄/定制/备战/战队/背包）出现为准。
 */
object ExitFarmFlow {

    private const val TAG = "ExitFarm"
    private const val TICK = 1200L
    /** 1200ms × 60 ≈ 1 分 12 秒 */
    private const val MAX_FRAMES = 60
    private const val MAX_ARROW_TAPS = 3
    private const val MAX_DIALOG_TAPS = 3
    private const val COOL = 2500L
    private val LOBBY_NAV = listOf("英雄", "定制", "备战", "战队", "背包")
    private val WS = Regex("\\s+")

    private val main = Handler(Looper.getMainLooper())

    private var token = 0
    private var appCtx: Context? = null
    private var statusCb: (String) -> Unit = {}
    private var doneCb: ((String) -> Unit)? = null
    private var finished = false

    private var frames = 0
    private var lastTapAt = 0L
    private var arrowTaps = 0
    private var dialogTaps = 0

    fun start(ctx: Context, status: (String) -> Unit, done: (String) -> Unit) {
        if (FlowControl.running.value) {
            done("有自动化正在执行，请先点「停止」")
            return
        }
        if (!BotService.isReady) {
            done("${Perms.accessibilityReason(ctx)}，无法退出农场")
            return
        }
        appCtx = ctx.applicationContext
        FarmButtonLocator.init(ctx)
        statusCb = status
        doneCb = done
        finished = false
        frames = 0
        lastTapAt = 0L
        arrowTaps = 0
        dialogTaps = 0
        token = FlowControl.begin("退出农场")
        IO.log(ctx, TAG, "开始退出农场")
        status("正在退出农场…")
        tick(ctx)
    }

    private fun finish(msg: String) {
        if (finished) return
        finished = true
        FlowControl.end(token)
        appCtx?.let { IO.log(it, TAG, msg) }
        val cb = doneCb
        doneCb = null
        cb?.invoke(msg)
    }

    private fun tick(ctx: Context) {
        if (!FlowControl.alive(token)) {
            finish("已强制停止")
            return
        }
        if (++frames > MAX_FRAMES) {
            finish("退出农场超时，请看日志")
            return
        }
        main.postDelayed({
            if (!FlowControl.alive(token)) {
                finish("已强制停止")
                return@postDelayed
            }
            val fg = BotService.foregroundPackage()
            if (fg != GameLauncher.PACKAGE) {
                tick(ctx)
                return@postDelayed
            }
            BotService.capture { bmp ->
                if (bmp == null) {
                    tick(ctx)
                    return@capture
                }
                Ocr.recognize(bmp) { lines -> onLines(ctx, bmp, lines) }
            }
        }, TICK)
    }

    private fun onLines(ctx: Context, bmp: android.graphics.Bitmap, lines: List<Ocr.ScanLine>) {
        if (!FlowControl.alive(token)) {
            finish("已强制停止")
            return
        }
        val now = System.currentTimeMillis()
        val cool = now - lastTapAt

        // 1) 确认框：只认「返回大厅」（同一框里还有「返回稷下学院」）
        val back = lines.firstOrNull {
            it.text.replace(WS, "").contains("返回大厅")
        }
        if (back != null) {
            if (dialogTaps >= MAX_DIALOG_TAPS) {
                finish("点了 $dialogTaps 次「返回大厅」仍没回大厅，请看日志")
                return
            }
            if (cool > COOL) {
                lastTapAt = now
                dialogTaps++
                val x = back.box.centerX().toInt()
                val y = back.box.centerY().toInt()
                IO.log(ctx, TAG, "点击 返回大厅 ($x,$y) 第 $dialogTaps 次 :: ${back.text}")
                BotService.tap(x, y)
                statusCb("正在返回游戏大厅…")
            }
            tick(ctx)
            return
        }

        // 2) 已经在大厅：底部导航出现即完成
        val inLobby = lines.any { l ->
            val t = l.text.replace(WS, "")
            l.box.centerY() > bmp.height * 0.85f && LOBBY_NAV.any { t.contains(it) }
        }
        if (inLobby) {
            finish("已退出农场，回到游戏大厅")
            return
        }

        // 3) 农场页：左上角 D 形箭头（图标无文字），OpenCV 定位；匹配不到才退回比例坐标
        val inFarm = lines.any {
            val t = it.text.replace(WS, "")
            (t.contains("种植") && it.box.centerY() > bmp.height * 0.75f && it.box.centerX() > bmp.width * 0.7f) ||
                (t.contains("仓库") && it.box.centerX() > bmp.width * 0.8f)
        }
        if (inFarm) {
            if (arrowTaps >= MAX_ARROW_TAPS) {
                finish("点了 $arrowTaps 次返回箭头仍没弹出确认框，请看日志")
                return
            }
            if (cool > COOL) {
                val hit = FarmButtonLocator.findBack(bmp)
                lastTapAt = now
                arrowTaps++
                val x = hit?.x ?: (bmp.width * 0.1375f).toInt()
                val y = hit?.y ?: (bmp.height * 0.375f).toInt()
                IO.log(
                    ctx, TAG,
                    "点击 左上返回箭头 ($x,$y) 第 $arrowTaps 次" +
                        if (hit == null) "（OpenCV 未命中，用比例坐标）"
                        else "，匹配度=${"%.2f".format(hit.score)}",
                )
                BotService.tap(x, y)
                statusCb("正在返回农场入口…")
            }
            tick(ctx)
            return
        }

        // 4) 加载/过渡画面：等
        if (frames % 6 == 0) statusCb("正在退出农场…")
        tick(ctx)
    }
}
