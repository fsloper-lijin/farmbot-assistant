package com.farmbot.assistant.bot

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.farmbot.assistant.core.IO
import com.farmbot.assistant.perm.Perms

/**
 * 农场内走到「雕塑」旁 → 出现三个操作按钮（流光加速 / 一键务农 / 农场升级）→
 * 点「一键务农」右上角的齿轮。
 *
 * 走路只能靠左下角摇杆：dispatchGesture 按住摇杆往某个方向拖、按住时长 = 跑动时长。
 * 进农场出生点固定，雕塑在左前方，所以按「斜向左前」分段跑；每段跑完截屏，
 * 用 OpenCV 匹配「一键务农」圆钮模板判定到位（按钮文字 OCR 会漏读，图标更稳）。
 *
 * 齿轮位置由圆钮模板命中点按比例偏移换算（跨分辨率成立）。
 * 悬浮窗文案不参与判定，不会把自己当按钮。
 */
object FarmWalkFlow {

    private const val TAG = "Walk"
    /** 松开摇杆后等角色停稳再截屏 */
    private const val SETTLE_MS = 200L
    /** 总预算：7 段 × ~2.3s + 走完补查 4 帧 + 点齿轮，60 帧 ≈ 1.5 分钟 */
    private const val MAX_FRAMES = 60
    /**
     * 分段脚步（方向 x 右为正 / y 下为正，按住时长）。真机实测路线：
     * 出生点在广场，雕塑在左前方约 3~4 秒脚程；按钮触发范围很小（贴到台座才出），
     * 所以步子压到 0.6s 左右，每走一步截屏确认一次。
     * shortcut: 曾有 4 段反向补步（21:9 视口下正向走完已近触发区，反向会走出触发区，
     * 2026-11-20 真机「往回走」踩坑）已删；走过头改由走完后的原地补查兜底，改视口/路径后需复测
     */
    private val STEPS = listOf(
        Triple(0f, -1f, 600L),
        Triple(0f, -1f, 600L),
        Triple(-0.62f, -0.78f, 650L),
        Triple(-0.62f, -0.78f, 650L),
        Triple(-0.62f, -0.78f, 650L),
        Triple(-1f, 0f, 550L),
        Triple(-1f, 0f, 550L),
    )
    /** 摇杆圆心（屏幕比例）：站立时左下角可见淡圆，2880×1800 真机实测 (0.151w, 0.729h) */
    private const val STICK_X = 0.151f
    private const val STICK_Y = 0.729f
    /** 摇杆拖出距离（按屏高），约底座半径的 0.7 → 全速 */
    private const val DRAG = 0.06f
    /**
     * 齿轮相对「一键务农」圆钮模板命中中心的比例偏移。
     * 2880×1800 实测：模板中心 (1940,1000)，齿轮中心 (2028,913)。
     */
    private const val GEAR_OFF_X = 0.0306f
    private const val GEAR_OFF_Y = -0.0483f
    private const val MAX_GEAR_TAPS = 2
    private const val COOL_TAP = 1200L

    private val main = Handler(Looper.getMainLooper())

    private var token = 0
    private var appCtx: Context? = null
    private var statusCb: (String) -> Unit = {}
    private var doneCb: ((String) -> Unit)? = null
    private var finished = false

    private var frames = 0
    /** 已跑完的分段数 */
    private var steps = 0
    /** 走完分段后的原地补查帧数 */
    private var linger = 0
    private var gearTaps = 0
    private var lastTapAt = 0L
    /** true = 走到位后接着点齿轮；false = 只走到位（读土地状态用） */
    private var openSettings = true

    fun start(
        ctx: Context,
        status: (String) -> Unit,
        done: (String) -> Unit,
        openSettings: Boolean = true,
    ) {
        if (FlowControl.running.value) {
            done("有自动化正在执行，请先点「停止」")
            return
        }
        if (!BotService.isReady) {
            done("${Perms.accessibilityReason(ctx)}，无法走到农场操作点")
            return
        }
        appCtx = ctx.applicationContext
        FarmButtonLocator.init(ctx)
        statusCb = status
        doneCb = done
        finished = false
        frames = 0
        steps = 0
        linger = 0
        gearTaps = 0
        lastTapAt = 0L
        this.openSettings = openSettings
        token = FlowControl.begin("走向农场操作点")
        IO.log(ctx, TAG, "开始走向农场操作点")
        status("正在走向操作点…")
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
        if (++frames > MAX_FRAMES) {
            finish("走到操作点超时（已走 $steps 段），请看日志")
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
                onFrame(ctx, bmp)
            }
        }, delayMs)
    }

    private fun onFrame(ctx: Context, bmp: android.graphics.Bitmap) {
        if (!FlowControl.alive(token)) {
            finish("已强制停止")
            return
        }
        val now = System.currentTimeMillis()
        val cool = now - lastTapAt

        // 圆钮模板命中 = 到雕塑旁了
        val hit = FarmButtonLocator.findAction(bmp)
        if (hit != null) {
            // 只负责走到位（读土地状态用），齿轮留到下一个流程点
            if (!openSettings) {
                IO.log(ctx, TAG, "已走到雕塑旁，圆钮匹配度=${"%.2f".format(hit.score)}")
                finish("已走到雕塑")
                return
            }
            // 点过齿轮还能看到圆钮 = 上一枪没点中（或面板没盖住按钮）：
            // 冷却后补一枪，上限后按失败收，别无限点
            if (gearTaps >= MAX_GEAR_TAPS) {
                logSnapshot(ctx, bmp, "齿轮点了没反应")
                finish("一键务农齿轮点击无效，请看日志")
                return
            }
            if (cool <= COOL_TAP) {
                tick(ctx, 0L)
                return
            }
            val gx = (hit.x + bmp.width * GEAR_OFF_X).toInt().coerceIn(0, bmp.width - 1)
            // 齿轮在内容区里的相对位置，21:9 视口下偏移按内容高算才准
            val contentH = if (hit.ch > 0) hit.ch else bmp.height
            val gy = (hit.y + contentH * GEAR_OFF_Y).toInt().coerceIn(0, bmp.height - 1)
            lastTapAt = now
            gearTaps++
            IO.log(
                ctx, TAG,
                "点击 一键务农齿轮 ($gx,$gy) 第 $gearTaps 次，圆钮匹配度=${"%.2f".format(hit.score)}"
            )
            BotService.tap(gx, gy)
            // 文案不得含三个按钮的文字
            statusCb("正在打开设置…")
            tick(ctx, SETTLE_MS)
            return
        }

        // 按钮消失之前点过齿轮 = 设置面板已打开（或按钮被面板盖住）
        if (gearTaps > 0) {
            logSnapshot(ctx, bmp, "齿轮点击后按钮消失")
            finish("已打开一键务农设置")
            return
        }

        // 没到：走完后原地补查几帧（按钮晚出现/帧间漏匹配），仍无命中才失败并留现场快照
        if (steps >= STEPS.size) {
            linger++
            if (linger >= 4) {
                logSnapshot(ctx, bmp, "没走到农场操作点")
                finish("没走到农场操作点（已走 ${STEPS.size} 段），请看日志")
                return
            }
            tick(ctx, 0L)
            return
        }
        val (dx, dy, holdMs) = STEPS[steps]
        steps++
        val sx = (bmp.width * STICK_X).toInt()
        val sy = (bmp.height * STICK_Y).toInt()
        val len = (bmp.height * DRAG).toInt()
        val ex = (sx + dx * len).toInt()
        val ey = (sy + dy * len).toInt()
        IO.log(ctx, TAG, "走第 $steps 段 摇杆 ($sx,$sy)->($ex,$ey) 按住 ${holdMs}ms")
        BotService.swipe(sx, sy, ex, ey, holdMs)
        statusCb("正在走向操作点…")
        tick(ctx, holdMs + SETTLE_MS)
    }

    private fun logSnapshot(
        ctx: Context,
        bmp: android.graphics.Bitmap,
        why: String,
    ) {
        val shot = java.io.File(IO.dir(ctx, "shots"), "walk_${System.currentTimeMillis()}.png")
        IO.savePng(bmp, shot)
        IO.log(ctx, TAG, "$why（帧=$frames 段=$steps）截图=${shot.absolutePath}")
    }
}
