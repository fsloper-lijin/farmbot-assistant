package com.farmbot.assistant.bot

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.farmbot.assistant.core.IO
import com.farmbot.assistant.ocr.Ocr
import com.farmbot.assistant.perm.Perms

/**
 * 三按钮旁点「一键务农」执行批量浇水（设置面板已由 HarvestSetupFlow 提交）。
 *
 * 按钮是圆形图标，文字「一键务农」只是下方标签，点文字不会触发 —— 真机实测点文字没反应。
 * 齿轮紧贴圆钮右上，点击取圆钮左侧安全点，不能点圆钮中心或文字。
 *
 * 浇水成功不看飘字：点一次后向前走到左侧植物详情卡，
 * 读「成熟时间」下方进度条的蓝色填充比例，整条变蓝（≥0.9）即成功。
 */
object FarmWaterFlow {

    private const val TAG = "Water"
    private const val TICK = 900L
    private const val MAX_FRAMES = 60
    private const val COOL = 900L
    /** 点击后等浇水结算再开始走过去 */
    private const val SETTLE_TAP = 1500L
    /** 进度条「满」的阈值：实测浇水前 0.67、浇水后 1.00 */
    private const val FULL_RATIO = 0.9
    /** 见到卡片但进度条没满时最多再看几帧 */
    private const val MAX_BAR_FRAMES = 5
    /** 走到详情卡的最大步数（同 LandScanFlow：从雕塑旁约 3 步可见） */
    private const val MAX_WALK_STEPS = 8
    private const val STEP_MS = 1000L
    private const val SETTLE_MS = 900L
    private const val STICK_X = 0.151f
    private const val STICK_Y = 0.729f
    private const val DRAG = 0.06f
    private val WS = Regex("\\s+")

    /** “一键务农”可能读成“一健务农/一键务衣”。 */
    private fun isMenuLabel(t: String): Boolean =
        (t.contains("一键") || t.contains("一健")) && t.contains("务")

    private val main = Handler(Looper.getMainLooper())

    private var token = 0
    private var appCtx: Context? = null
    private var statusCb: (String) -> Unit = {}
    private var doneCb: ((String) -> Unit)? = null
    private var finished = false

    private var frames = 0
    private var taps = 0
    private var walked = 0
    private var barFrames = 0
    private var lastTapAt = 0L
    private var tappedAt = 0L

    fun start(ctx: Context, status: (String) -> Unit, done: (String) -> Unit) {
        if (FlowControl.running.value) {
            done("有自动化正在执行，请先点「停止」")
            return
        }
        if (!BotService.isReady) {
            done("${Perms.accessibilityReason(ctx)}，无法执行一键务农")
            return
        }
        appCtx = ctx.applicationContext
        FarmButtonLocator.init(ctx)
        statusCb = status
        doneCb = done
        finished = false
        frames = 0
        taps = 0
        walked = 0
        barFrames = 0
        lastTapAt = 0L
        tappedAt = 0L
        token = FlowControl.begin("一键务农")
        IO.log(ctx, TAG, "开始执行一键务农（浇水）")
        status("正在执行一键浇水…")
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

    private fun tick(ctx: Context, delay: Long = TICK) {
        if (!FlowControl.alive(token)) {
            finish("已强制停止")
            return
        }
        if (++frames > MAX_FRAMES) {
            finish("一键务农超时，请看日志")
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
        }, delay)
    }

    private fun onLines(ctx: Context, bmp: android.graphics.Bitmap, lines: List<Ocr.ScanLine>) {
        if (!FlowControl.alive(token)) {
            finish("已强制停止")
            return
        }
        val now = System.currentTimeMillis()
        val cool = now - lastTapAt

        // 点过按钮：设置面板又出现说明点到了齿轮，不能当成执行成功。
        val panelOpened = lines.any {
            val t = it.text.replace(WS, "")
            (t.contains("一键务农设置") || t.contains("一键务农設置") ||
                t.contains("一健务农设置") || t.contains("一健务农設置")) &&
                it.box.centerY() < bmp.height * 0.6f
        }
        if (panelOpened) {
            finish(if (tappedAt > 0) "误点一键务农齿轮，未确认浇水" else "设置面板仍打开，请先提交设置")
            return
        }
        tappedAt = 0L

        // 面板识别不可靠时不盲点“确定”，交给前面的 HarvestSetupFlow 按勾选项状态提交。
        val confirm = lines.firstOrNull {
            it.text.replace(WS, "").contains("确定") && it.box.centerX() > bmp.width * 0.55f
        }
        if (confirm != null && lines.any {
                val t = it.text.replace(WS, "")
                (t.contains("浇") || t.contains("种")) && it.box.centerX() > bmp.width * 0.55f
            }
        ) {
            finish("设置面板未提交，请先确认一键务农设置")
            return
        }

        // 第一阶段：在雕塑旁点一次「一键务农」（OCR 标签 + OpenCV 圆钮双重确认，缺一不点）
        if (taps == 0) {
            val hasLabel = lines.any {
                val t = it.text.replace(WS, "")
                isMenuLabel(t) && !t.contains("设置") && !t.contains("設置") &&
                    it.box.centerX() > bmp.width * 0.35f &&
                    it.box.centerY() > bmp.height * 0.3f &&
                    it.box.centerY() < bmp.height * 0.8f
            }
            if (!hasLabel) {
                tick(ctx)
                return
            }
            val hit = FarmButtonLocator.findAction(bmp)
            if (hit == null) {
                if (frames % 4 == 1) IO.log(ctx, TAG, "OCR 已见务农，OpenCV 未确认圆钮")
                tick(ctx)
                return
            }
            if (cool <= COOL) {
                tick(ctx)
                return
            }
            // 命中框中心偏右上，往左下方挪到图标按钮真正接收点击的区域
            val x = (hit.x - bmp.width * 0.010f).toInt().coerceIn(0, bmp.width - 1)
            val y = (hit.y + bmp.height * 0.003f).toInt().coerceIn(0, bmp.height - 1)
            lastTapAt = now
            tappedAt = now
            taps++
            IO.log(ctx, TAG, "点击 一键务农圆钮 ($x,$y)，匹配度=${"%.2f".format(hit.score)}")
            statusCb("正在一键浇水…")
            BotService.tap(x, y)
            tick(ctx, SETTLE_TAP)
            return
        }

        // 第二阶段：向前走到左侧植物详情卡，看成熟时间下方进度条是否全蓝
        val remain = lines.firstOrNull {
            val t = it.text.replace(WS, "")
            (t.contains("成熟") || (t.contains("小时") && t.contains("后"))) &&
                it.box.centerX() < bmp.width * 0.3f &&
                it.box.centerY() < bmp.height * 0.6f
        }
        if (remain != null) {
            val ratio = FarmButtonLocator.waterBarRatio(bmp)
            if (ratio != null && ratio >= FULL_RATIO) {
                finish("浇水完成：进度条已满（${"%.0f".format(ratio * 100)}%）")
                return
            }
            barFrames++
            if (barFrames >= MAX_BAR_FRAMES) {
                val shown = if (ratio == null) "未找到" else "${"%.0f".format(ratio * 100)}%"
                finish("浇水后进度条未满（$shown），请看日志")
                return
            }
            tick(ctx, TICK)
            return
        }
        if (walked >= MAX_WALK_STEPS) {
            finish("浇水后向前走 $walked 步仍没看到植物详情，未确认浇水")
            return
        }
        walked++
        val sx = (bmp.width * STICK_X).toInt()
        val sy = (bmp.height * STICK_Y).toInt()
        val ty = sy - (bmp.height * DRAG).toInt()
        IO.log(ctx, TAG, "确认浇水：向前走第 $walked 步 摇杆 ($sx,$sy)->($sx,$ty)")
        BotService.swipe(sx, sy, sx, ty, STEP_MS)
        statusCb("正在确认浇水…")
        tick(ctx, STEP_MS + SETTLE_MS)
    }
}
