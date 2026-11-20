package com.farmbot.assistant.bot

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.farmbot.assistant.core.IO
import com.farmbot.assistant.ocr.Ocr
import com.farmbot.assistant.perm.Perms

/**
 * 从游戏大厅回到「开始游戏 / 换区」页（用户口述路径）：
 * 右上角齿轮 → 设置面板「退出登录」→ 确认框「确定」→ 选区页。全程不杀游戏。
 *
 * 齿轮是图标无文字，按比例点（2880×1800 实测 0.872w、0.352h）；
 * 「退出登录」「确定」用 OCR 文字中心，避免写死像素。以「开始游戏」出现为回到选区页。
 */
object ReturnToSelectFlow {

    private const val TAG = "Select"
    private const val TICK = 1200L
    private const val MAX_FRAMES = 80
    private const val COOL = 2500L
    private val WS = Regex("\\s+")

    private val main = Handler(Looper.getMainLooper())

    private var token = 0
    private var appCtx: Context? = null
    private var statusCb: (String) -> Unit = {}
    private var doneCb: ((String) -> Unit)? = null
    private var finished = false
    private var frames = 0
    private var lastTapAt = 0L
    private var gearTaps = 0
    private var logoutTaps = 0
    private var confirmTaps = 0

    fun start(ctx: Context, status: (String) -> Unit, done: (String) -> Unit) {
        if (FlowControl.running.value) {
            done("有自动化正在执行，请先点「停止」")
            return
        }
        if (!BotService.isReady) {
            done("${Perms.accessibilityReason(ctx)}，无法回到选区页")
            return
        }
        appCtx = ctx.applicationContext
        FarmButtonLocator.init(ctx)
        statusCb = status
        doneCb = done
        finished = false
        frames = 0
        lastTapAt = 0L
        gearTaps = 0
        logoutTaps = 0
        confirmTaps = 0
        token = FlowControl.begin("回到选区")
        IO.log(ctx, TAG, "开始回到选区/换号页")
        status("正在退出账号回到选区…")
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
            finish("回到选区页超时，请看日志")
            return
        }
        main.postDelayed({
            if (!FlowControl.alive(token)) {
                finish("已强制停止")
                return@postDelayed
            }
            if (BotService.foregroundPackage() != GameLauncher.PACKAGE) {
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

        // 到选区页：出现「开始游戏」
        val atStart = lines.any { it.text.replace(WS, "").contains("开始游戏") }

        // 1) 退出登录确认框
        val askLogout = lines.any { it.text.replace(WS, "").contains("确定要退出登录") }
        if (askLogout) {
            val ok = lines.firstOrNull {
                val t = it.text.replace(WS, "")
                t == "确定" && it.box.centerY() > bmp.height * 0.6f
            }
            if (ok != null && cool > COOL) {
                if (confirmTaps >= 3) {
                    finish("「确定」退出登录点了 3 次没生效，请看日志")
                    return
                }
                lastTapAt = now
                confirmTaps++
                IO.log(ctx, TAG, "点击 确定退出登录 (${ok.box.centerX()},${ok.box.centerY()}) 第 $confirmTaps 次")
                BotService.tap(ok.box.centerX(), ok.box.centerY())
                statusCb("正在退出账号…")
            }
            tick(ctx)
            return
        }

        // 2) 设置面板：点「退出登录」
        val logout = lines.firstOrNull { it.text.replace(WS, "").contains("退出登录") }
        if (logout != null) {
            if (cool > COOL) {
                if (logoutTaps >= 3) {
                    finish("「退出登录」点了 3 次没生效，请看日志")
                    return
                }
                lastTapAt = now
                logoutTaps++
                IO.log(ctx, TAG, "点击 退出登录 (${logout.box.centerX()},${logout.box.centerY()}) 第 $logoutTaps 次")
                BotService.tap(logout.box.centerX(), logout.box.centerY())
                statusCb("正在退出登录…")
            }
            tick(ctx)
            return
        }

        // 3) 大厅：点右上角齿轮（图标无文字），OpenCV 定位；匹配不到才退回比例坐标
        val inLobby = lines.any {
            val t = it.text.replace(WS, "")
            t.contains("对战") || t.contains("排位") || t.contains("王者农场")
        }
        if (inLobby && !atStart) {
            if (cool > COOL) {
                if (gearTaps >= 3) {
                    finish("大厅齿轮点了 3 次没打开设置，请看日志")
                    return
                }
                lastTapAt = now
                gearTaps++
                val hit = FarmButtonLocator.findGear(bmp)
                val x = hit?.x ?: (bmp.width * 0.872f).toInt()
                val y = hit?.y ?: (bmp.height * 0.352f).toInt()
                IO.log(
                    ctx, TAG,
                    "点击 大厅齿轮 ($x,$y) 第 $gearTaps 次" +
                        if (hit == null) "（OpenCV 未命中，用比例坐标）"
                        else "，匹配度=${"%.2f".format(hit.score)}",
                )
                BotService.tap(x, y)
                statusCb("正在打开设置…")
            }
            tick(ctx)
            return
        }

        if (atStart) {
            finish("已回到选区页")
            return
        }
        // 加载/过渡画面：等
        if (frames % 6 == 0) statusCb("正在回到选区…")
        tick(ctx)
    }
}
