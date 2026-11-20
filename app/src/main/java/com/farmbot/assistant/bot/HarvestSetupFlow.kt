package com.farmbot.assistant.bot

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.farmbot.assistant.core.IO
import com.farmbot.assistant.ocr.Ocr
import com.farmbot.assistant.perm.Perms

/**
 * 「一键务农设置」面板：按 App 里规则页的三项开关勾选游戏里的对应选项，再点「确定」。
 *
 * 面板顺序固定：一键浇水 → 一键收获 → 一键种植（用户确认与 App 规则页一致）。
 * 勾选框的位置与状态都用 OpenCV 模板匹配（`box_off_template` / `box_on_template`），
 * 三个框凑齐且等距才认定面板在；OCR 只负责「确定」按钮与「作物列表」判定。
 *
 * 悬浮窗文案不得包含三项文字与「确定」（会被自己 OCR 成选项点掉）。
 */
object HarvestSetupFlow {

    private const val TAG = "Harvest"
    private const val TICK = 900L
    /** 点一枪后等勾选动画落定的验证等待 */
    private const val VERIFY_WAIT = 700L
    private const val MAX_FRAMES = 40
    /** 每一项最多点几枪（点不中时不无限戳） */
    private const val MAX_TAPS_PER_ROW = 3
    private const val COOL_TAP = 900L

    /** 三项与 App 规则开关的 prefs key；顺序即面板里的上下顺序 */
    private data class Part(val pref: String, val label: String)

    private val PARTS = listOf(
        Part("rule_harvest_water", "一键浇水"),
        Part("rule_harvest_reap", "一键收获"),
        Part("rule_harvest_plant", "一键种植"),
    )
    private val WS = Regex("\\s+")

    private val main = Handler(Looper.getMainLooper())

    private var token = 0
    private var appCtx: Context? = null
    private var statusCb: (String) -> Unit = {}
    private var doneCb: ((String) -> Unit)? = null
    private var finished = false

    private var frames = 0
    private var lastTapAt = 0L
    private val taps = mutableMapOf<String, Int>()
    /** 是否已发过「确定」点击 */
    private var confirmTaps = 0
    /** 面板上一帧还在，这一帧消失 = 已提交 */
    private var panelSeen = false
    /** 面板连续消失的帧数（OCR 偶发全空不算数） */
    private var missFrames = 0
    /**
     * 选作物流程：勾上「一键种植」时游戏会自动弹作物列表，必须先选一个作物才能回面板。
     * 0=不在选作物 / 1=刚点完作物格子等详情卡 / 2=已点「选择」等面板回来
     */
    private var cropStep = 0

    fun start(ctx: Context, status: (String) -> Unit, done: (String) -> Unit) {
        if (FlowControl.running.value) {
            done("有自动化正在执行，请先点「停止」")
            return
        }
        if (!BotService.isReady) {
            done("${Perms.accessibilityReason(ctx)}，无法设置一键务农")
            return
        }
        val prefs = ctx.getSharedPreferences("farmbot", Context.MODE_PRIVATE)
        val on = PARTS.filter { prefs.getBoolean(it.pref, false) }
        if (on.isEmpty()) {
            done("一键务农的三项工作都关着，跳过")
            return
        }
        appCtx = ctx.applicationContext
        FarmButtonLocator.init(ctx)
        statusCb = status
        doneCb = done
        finished = false
        frames = 0
        lastTapAt = 0L
        taps.clear()
        confirmTaps = 0
        panelSeen = false
        missFrames = 0
        cropStep = 0
        token = FlowControl.begin("设置一键务农")
        IO.log(ctx, TAG, "开始设置一键务农 目标=" + on.joinToString(",") { it.label })
        status("正在设置一键务农…")
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
            finish("设置一键务农超时，请看日志")
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
        val now = System.currentTimeMillis()
        val cool = now - lastTapAt
        val prefs = ctx.getSharedPreferences("farmbot", Context.MODE_PRIVATE)

        // 面板里的三项勾选框：OpenCV 直接匹配「未勾 / 已勾」模板，按 y 从上到下即三项顺序
        // 不再从 OCR 行框推算复选框位置（漏读一行、行高抖动都会把点击点带偏）
        val checks = FarmButtonLocator.findChecks(bmp)
        val confirm = lines.firstOrNull {
            it.text.replace(WS, "").contains("确定") &&
                it.box.centerX() > bmp.width * 0.55f
        }

        // 已发「确定」且面板消失 = 提交成功
        if (confirmTaps > 0 && confirm == null) {
            finish("一键务农已按开关设置并提交")
            return
        }

        // 勾上「一键种植」后游戏自动弹作物列表：选第一个作物 → 点「选择」→ 回面板
        val cropList = lines.firstOrNull { it.text.replace(WS, "").contains("作物列表") }
        if (cropStep == 0 && cropList != null) {
            if (cool <= COOL_TAP) {
                tick(ctx, 0L)
                return
            }
            // 列表第一格（2880×1800 实测 (0.62w, 0.41h)），不读作物名：用户没指定就选第一个
            val x = (bmp.width * 0.62f).toInt()
            val y = (bmp.height * 0.41f).toInt()
            lastTapAt = now
            cropStep = 1
            IO.log(ctx, TAG, "点开第一个作物 ($x,$y)")
            BotService.tap(x, y)
            statusCb("正在选择作物…")
            tick(ctx, VERIFY_WAIT)
            return
        }
        if (cropStep == 1) {
            val pick = lines.firstOrNull {
                it.text.replace(WS, "") == "选择" && it.box.centerY() > bmp.height * 0.85f
            }
            if (pick != null && cool > COOL_TAP) {
                lastTapAt = now
                cropStep = 2
                IO.log(ctx, TAG, "点击作物「选择」(${pick.box.centerX()},${pick.box.centerY()})")
                BotService.tap(pick.box.centerX(), pick.box.centerY())
                tick(ctx, VERIFY_WAIT)
            } else {
                tick(ctx, TICK)
            }
            return
        }
        if (cropStep == 2) {
            // 等面板回来；面板重新可见就回到正常比对
            if (checks != null) cropStep = 0 else { tick(ctx, TICK); return }
        }

        if (checks == null && confirm == null) {
            missFrames++
            if (missFrames >= 5) {
                finish(if (panelSeen) "一键务农面板不见了，请看日志" else "没找到一键务农设置面板，请看日志")
                return
            }
            tick(ctx, TICK)
            return
        }
        missFrames = 0
        panelSeen = true

        // 勾选框没凑齐三个就不能按状态点击（农场别处的金色方块会误匹配）
        if (checks == null) {
            if (frames % 4 == 0) IO.log(ctx, TAG, "面板在但只认出不到 3 个勾选框，等下一帧")
            tick(ctx, TICK)
            return
        }

        // 逐项比对目标状态；第一项不符就处理它
        for ((i, p) in PARTS.withIndex()) {
            val want = prefs.getBoolean(p.pref, false)
            val got = checks[i].checked
            if (got == want) continue
            val used = taps[p.label] ?: 0
            if (used >= MAX_TAPS_PER_ROW) {
                IO.log(ctx, TAG, "「${p.label}」点了 $used 枪仍不匹配（当前=${if (got) "勾" else "未勾"}），继续下一项")
                continue
            }
            if (cool <= COOL_TAP) {
                tick(ctx, 0L)
                return
            }
            val x = checks[i].x.coerceIn(0, bmp.width - 1)
            val y = checks[i].y.coerceIn(0, bmp.height - 1)
            lastTapAt = now
            taps[p.label] = used + 1
            IO.log(
                ctx, TAG,
                "点击「${p.label}」复选框 ($x,$y) 第 ${used + 1} 次（当前=${if (got) "勾" else "未勾"} 目标=${if (want) "勾" else "未勾"} 匹配度=${"%.2f".format(checks[i].score)}）"
            )
            BotService.tap(x, y)
            statusCb("正在按开关设置…")
            tick(ctx, VERIFY_WAIT)
            return
        }

        // 全部符合 → 点确定
        if (confirm == null) {
            if (frames % 2 == 0) tick(ctx, TICK)
            return
        }
        if (confirmTaps >= 3) {
            finish("点了确定但面板没关，请看日志")
            return
        }
        if (cool <= COOL_TAP) {
            tick(ctx, 0L)
            return
        }
        lastTapAt = now
        confirmTaps++
        val cx = confirm.box.centerX().coerceIn(0, bmp.width - 1)
        val cy = confirm.box.centerY().coerceIn(0, bmp.height - 1)
        IO.log(ctx, TAG, "点击确定 ($cx,$cy) 第 $confirmTaps 次，三项已与开关一致")
        BotService.tap(cx, cy)
        statusCb("正在提交设置…")
        tick(ctx, VERIFY_WAIT)
    }
}
