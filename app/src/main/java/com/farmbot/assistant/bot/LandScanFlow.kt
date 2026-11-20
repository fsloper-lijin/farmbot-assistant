package com.farmbot.assistant.bot

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.farmbot.assistant.core.IO
import com.farmbot.assistant.ocr.Ocr
import com.farmbot.assistant.perm.Perms

/**
 * 从雕塑旁继续向前走，直到左侧出现「正在种植植物」的详情卡，记录这一块地的状态。
 *
 * 卡片特征（真机实测）：左侧 `x<0.3w`，含「小时后成熟」一类剩余时间文字；
 * 卡上还有作物名与浇水进度（如 0/5）。
 * 每走一步（摇杆向上一段）截屏 OCR 一次，看到卡片即停；走满 STEP 数仍没有就报错。
 * 记完按原路往回走同样步数回到雕塑旁，后面点齿轮的流程不用再重置+重走整条路线。
 */
object LandScanFlow {

    private const val TAG = "Land"
    private const val MAX_STEPS = 8
    /** 每步按住摇杆的时长 */
    private const val STEP_MS = 1000L
    /** 松开摇杆后等角色停稳 */
    private const val SETTLE_MS = 900L
    private const val STICK_X = 0.151f
    private const val STICK_Y = 0.729f
    private const val DRAG = 0.06f
    private val WS = Regex("\\s+")

    private val main = Handler(Looper.getMainLooper())

    private var token = 0
    private var appCtx: Context? = null
    private var statusCb: (String) -> Unit = {}
    private var doneCb: ((String) -> Unit)? = null
    private var finished = false
    private var steps = 0
    /** true = 已记完，正在按原路退回雕塑旁 */
    private var returning = false
    private var returned = 0
    private var summary = ""

    fun start(ctx: Context, status: (String) -> Unit, done: (String) -> Unit) {
        if (FlowControl.running.value) {
            done("有自动化正在执行，请先点「停止」")
            return
        }
        if (!BotService.isReady) {
            done("${Perms.accessibilityReason(ctx)}，无法读取土地状态")
            return
        }
        appCtx = ctx.applicationContext
        statusCb = status
        doneCb = done
        finished = false
        steps = 0
        returning = false
        returned = 0
        summary = ""
        token = FlowControl.begin("读取土地状态")
        IO.log(ctx, TAG, "开始向前走，找左侧植物详情")
        status("正在查看土地状态…")
        tick(ctx, 0L)
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

    private fun tick(ctx: Context, delayMs: Long) {
        if (!FlowControl.alive(token)) {
            finish("已强制停止")
            return
        }
        main.postDelayed({
            if (!FlowControl.alive(token)) {
                finish("已强制停止")
                return@postDelayed
            }
            if (BotService.foregroundPackage() != GameLauncher.PACKAGE) {
                tick(ctx, 0L)
                return@postDelayed
            }
            BotService.capture { bmp ->
                if (bmp == null) {
                    tick(ctx, 0L)
                    return@capture
                }
                Ocr.recognize(bmp) { lines -> onLines(ctx, bmp, lines) }
            }
        }, delayMs)
    }

    private fun onLines(ctx: Context, bmp: android.graphics.Bitmap, lines: List<Ocr.ScanLine>) {
        if (!FlowControl.alive(token)) {
            finish("已强制停止")
            return
        }

        // 第二阶段：按原路退回雕塑旁（来时走了几步就退几步）
        if (returning) {
            if (returned >= steps) {
                finish("已记录土地状态：$summary")
                return
            }
            returned++
            val sx = (bmp.width * STICK_X).toInt()
            val sy = (bmp.height * STICK_Y).toInt()
            val ty = sy + (bmp.height * DRAG).toInt()
            IO.log(ctx, TAG, "退回第 $returned/$steps 步 摇杆 ($sx,$sy)->($sx,$ty)")
            BotService.swipe(sx, sy, sx, ty, STEP_MS)
            tick(ctx, STEP_MS + SETTLE_MS)
            return
        }

        // 左侧详情卡：剩余时间行（如「12小时3分钟后成熟」）
        val remain = lines.firstOrNull {
            val t = it.text.replace(WS, "")
            (t.contains("成熟") || (t.contains("小时") && t.contains("后"))) &&
                it.box.centerX() < bmp.width * 0.3f &&
                it.box.centerY() < bmp.height * 0.6f
        }
        if (remain != null) {
            // 卡片顶部的作物名：剩余时间行上方最靠上的短词（进度 0/5 夹在中间，要排除）
            val progressRe = Regex("^\\d+\\s*/\\s*\\d+$")
            val name = lines.filter {
                it.box.centerX() < bmp.width * 0.3f &&
                    it.box.bottom < remain.box.top &&
                    it.box.top > remain.box.top - bmp.height * 0.25f &&
                    it.box.top > bmp.height * 0.05f &&
                    it.text.trim().length in 2..6 &&
                    !progressRe.matches(it.text.replace(WS, ""))
            }.minByOrNull { it.box.top }?.text?.trim()
            val progress = lines.firstOrNull {
                progressRe.matches(it.text.replace(WS, "")) &&
                    it.box.centerX() < bmp.width * 0.3f &&
                    it.box.centerY() < bmp.height * 0.5f
            }?.text?.trim()
            summary = listOfNotNull(name, remain.text.trim(), progress).joinToString(" ")
            IO.log(ctx, TAG, "土地状态：$summary（走 $steps 步后看到）")
            statusCb("土地状态：$summary")
            if (steps == 0) {
                finish("已记录土地状态：$summary")
                return
            }
            returning = true
            statusCb("正在退回操作点…")
            tick(ctx, 0L)
            return
        }
        if (steps >= MAX_STEPS) {
            finish("向前走 $steps 步仍没看到植物详情，请看日志")
            return
        }
        steps++
        val sx = (bmp.width * STICK_X).toInt()
        val sy = (bmp.height * STICK_Y).toInt()
        val ty = sy - (bmp.height * DRAG).toInt()
        IO.log(ctx, TAG, "走第 $steps 步 摇杆 ($sx,$sy)->($sx,$ty)")
        BotService.swipe(sx, sy, sx, ty, STEP_MS)
        statusCb("正在查看土地状态…")
        tick(ctx, STEP_MS + SETTLE_MS)
    }
}
