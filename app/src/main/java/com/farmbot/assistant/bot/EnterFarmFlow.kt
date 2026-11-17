package com.farmbot.assistant.bot

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.farmbot.assistant.core.IO
import com.farmbot.assistant.ocr.Ocr
import com.farmbot.assistant.perm.Perms

/**
 * 进入王者农场：大厅左下「王者农场」按钮（模式行：王者农场/对战/排位/玩法大厅）→ 点击 →
 * 等模式行消失 = 已进入（加载过渡也算离开大厅）。
 *
 * 按钮行真机实测 y≈0.80h、x≈0.24w；聊天栏在 y≈0.90h 且玩家常聊到「王者农场」，
 * 用 y<0.87h + x<0.45w 双门闩挡掉聊天行；悬浮窗文案不得含「王者农场」（按它找行）。
 * 游戏是 Unity 自绘，全程截图 + OCR 文字框中心点击，坐标不写死像素。
 */
object EnterFarmFlow {

    private const val TAG = "Farm"
    private const val TICK = 1400L
    /** 总预算：1400ms × 90 ≈ 2 分钟 */
    private const val MAX_FRAMES = 90
    /** 点过之后连续几帧看不到模式行才算真进去了 */
    private const val GONE_FRAMES = 2
    private const val MAX_TAPS = 3
    private const val COOL_TAP = 2000L
    /**
     * 进入后加盯的帧数找农场首弹：实测进农场 2~3s 内就弹，可能两个叠着出
     * （桌面灵宝盖着心愿季），点完第一个要等窗口内确认没有下一个才收尾。
     * 两类都是一次性弹窗：桌面灵宝点「下次吧」当日不再弹；心愿季点「立即开启」后
     * 只要不重装王者不再弹（都是用户口径 + 真机实测）。窗口 6 帧 ≈ 8.4s。
     */
    private const val ENTER_WATCH = 6
    private const val MAX_POP_TAPS = 4
    /** 农场首弹的一次性关闭按钮（点掉即消失，均为新设备首见弹窗） */
    private val POP_DISMISS = listOf("下次吧", "立即开启")
    /** 大厅模式行（不含「王者农场」自身：农场页若显示标题也不能当回大厅） */
    private val MODE_ROW = listOf("对战", "排位", "玩法大厅")
    private val WS = Regex("\\s+")

    private val main = Handler(Looper.getMainLooper())

    private var token = 0
    private var appCtx: Context? = null
    private var statusCb: (String) -> Unit = {}
    private var doneCb: ((String) -> Unit)? = null
    private var finished = false

    private var frames = 0
    private var lastTapAt = 0L
    private var taps = 0
    private var gone = 0
    /** 已离开大厅（模式行消失或见到农场弹窗）后的观察窗 */
    private var entered = false
    private var enterFrame = 0
    private var popTaps = 0

    fun start(ctx: Context, status: (String) -> Unit, done: (String) -> Unit) {
        if (FlowControl.running.value) {
            done("有自动化正在执行，请先点「停止」")
            return
        }
        if (!BotService.isReady) {
            done("${Perms.accessibilityReason(ctx)}，无法进入农场")
            return
        }
        appCtx = ctx.applicationContext
        statusCb = status
        doneCb = done
        finished = false
        frames = 0
        lastTapAt = 0L
        taps = 0
        gone = 0
        entered = false
        enterFrame = 0
        popTaps = 0
        token = FlowControl.begin("进入农场")
        IO.log(ctx, TAG, "开始进入农场")
        status("正在进入农场…")
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
            finish("进入农场超时（点了 $taps 次），请看日志")
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

        // ---- 农场首弹：OCR 到一次性关闭按钮直接点（看见即已在农场）----
        // 放在最前：在农场里重跑本流程也能先关弹窗收尾；文案不得含按钮词防自点
        val pop = lines.firstOrNull { l -> POP_DISMISS.any { l.text.replace(WS, "").contains(it) } }
        if (pop != null) {
            if (!entered) {
                entered = true
                enterFrame = frames
                IO.log(ctx, TAG, "发现农场首弹（帧=$frames）:: ${pop.text}")
            }
            if (popTaps >= MAX_POP_TAPS) {
                if (frames - enterFrame > 40) {
                    finish("已进入农场，但首弹按钮点了没反应，请看日志")
                    return
                }
            } else if (cool > COOL_TAP) {
                lastTapAt = now
                popTaps++
                IO.log(ctx, TAG, "点击 首弹按钮 (${pop.box.centerX()},${pop.box.centerY()}) 第 $popTaps 次 :: ${pop.text}")
                BotService.tap(pop.box.centerX(), pop.box.centerY())
                statusCb("正在关闭弹窗…")
            }
            tick(ctx)
            return
        }

        // 已离开大厅：观察窗结束且当前无首弹 → 收尾（弹窗可能叠层，窗口内逐个点掉）
        if (entered) {
            if (frames - enterFrame >= ENTER_WATCH) {
                finish(if (popTaps > 0) "已进入王者农场（已关提醒弹窗）" else "已进入王者农场")
                return
            }
            tick(ctx)
            return
        }

        // 点过之后模式行连续消失 = 已离开大厅（点不中时模式行一直在，不会误判）
        val modeVisible = lines.any { l ->
            val t = l.text.replace(WS, "")
            MODE_ROW.any { t.contains(it) }
        }
        if (taps > 0 && !modeVisible) {
            gone++
            if (gone >= GONE_FRAMES) {
                entered = true
                enterFrame = frames
                IO.log(ctx, TAG, "模式行消失=已进入，再盯 ${ENTER_WATCH} 帧农场弹窗（帧=$frames）")
            }
        } else {
            gone = 0
        }

        val button = lines.firstOrNull {
            val t = it.text.replace(WS, "")
            t.contains("王者农场") &&
                it.box.centerY() > bmp.height * 0.70f &&
                it.box.centerY() < bmp.height * 0.87f &&
                it.box.centerX() < bmp.width * 0.45f
        }
        if (button == null) {
            if (frames % 4 == 1) {
                IO.log(
                    ctx, TAG,
                    "等待按钮(帧=$frames 点=$taps) " +
                        lines.filter { it.box.centerY() > bmp.height * 0.7f }
                            .take(6).joinToString(" | ") { it.text }
                )
            }
            tick(ctx)
            return
        }
        if (taps >= MAX_TAPS) {
            finish("「王者农场」点了 $taps 次仍在大厅，请看日志")
            return
        }
        if (cool <= COOL_TAP) {
            tick(ctx)
            return
        }
        lastTapAt = now
        taps++
        gone = 0
        IO.log(ctx, TAG, "点击 王者农场 (${button.box.centerX()},${button.box.centerY()}) 第 $taps 次 :: ${button.text}")
        BotService.tap(button.box.centerX(), button.box.centerY())
        // 文案不得含「王者农场」：按钮判定按它找行，写进去会把悬浮窗自己点掉
        statusCb("正在进入农场…")
        tick(ctx)
    }
}
