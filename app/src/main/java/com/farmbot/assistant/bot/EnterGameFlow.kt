package com.farmbot.assistant.bot

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.farmbot.assistant.core.IO
import com.farmbot.assistant.data.Account
import com.farmbot.assistant.data.Farm
import com.farmbot.assistant.ocr.Ocr
import com.farmbot.assistant.perm.Perms

/**
 * 「开始执行」的进游戏段：识别当前区 → 需要时换区 → 点开始游戏。
 *
 * 前置：LoginFlow 已把账号登录到「开始游戏」页（有 开始游戏 / 换区 按钮的那一页）。
 * 目标区 = 账号卡里勾选（enabled）的第一个区服。
 *
 * 判定「是不是目标区」：
 * - 双方都有区号 → 比区号。OCR 会把数字 1 稳定读成字母 l，比较前先归一。
 * - 双方都没有区号 → 抢先服（灰度测试区，如「微信王者抢先服」）→ 比「抢先服」+ 登录前缀（手Q/微信）。
 * - 当前区服读不到时不硬猜：等几帧，仍读不到就直接开换区按目标绝对定位选区（选区不依赖当前区）。
 *
 * 换区面板里选中目标行后，面板可能自动关闭、也可能要再点左上返回箭头，两种都兜住；
 * 回到开始游戏页后核对当前区确实变成目标区，才点「开始游戏」。
 * 游戏是 Unity 自绘，节点树为空，全程截图 + OCR + 文字框中心 / 比例坐标点击。
 */
object EnterGameFlow {

    private const val TAG = "Enter"
    private const val TICK = 1400L
    /** 总帧上限（≈ 1400ms × 80 ≈ 110s，覆盖读区/换区/翻页/开始游戏全链路） */
    private const val MAX_FRAMES = 80
    /** 大厅连续读不到「换区」按钮的容忍帧数（字号小，首帧常漏） */
    private const val MAX_CHANGE_MISSES = 6
    /** 换区 / 开始游戏 各最多点几次 */
    private const val MAX_CHANGE_TAPS = 3
    private const val MAX_START_TAPS = 3
    /** 面板里没找到目标区时最多上滑翻几屏 */
    private const val MAX_SCROLLS = 4
    /**
     * 面板里连续多少帧「读到了区服行、但没有目标行」才判找不到。
     * 要容忍 OCR 偶发把目标行读花（游戏花体字），也给翻页留出时间；按 1.6s/帧 ≈ 13s。
     */
    private const val MAX_MISS_FRAMES = 8
    /** 换区面板左上角返回箭头比例位置（LoginFlow 同款：x 固定比例，y 跟标题走） */
    private const val BACK_RX = 0.10f
    private const val BACK_RY = 0.067f

    /** 大厅/面板通用区服行：抢先服没有区号，必须单独放行 */
    private val SERVER_RE = Regex("(手Q|微信)\\s*(王者)?\\s*(抢先服|\\d+\\s*区)")
    /** 区号：先归一 OCR 字母混淆再取「数字+区」 */
    private val ZONE_NO_RE = Regex("(\\d+)\\s*区")
    private val WS = Regex("\\s+")
    /** 行首装饰符（◆ 上次登录标记等） */
    private val LEADING_JUNK = Regex("^[◆◇•·▪◦★☆＊*\\-—~\\s]+")

    private val main = Handler(Looper.getMainLooper())

    private var token = 0
    private var appCtx: Context? = null
    private var target: Farm? = null
    private var statusCb: (String) -> Unit = {}
    private var doneCb: ((String) -> Unit)? = null
    private var finished = false

    private var frames = 0
    private var lastTapAt = 0L
    /** 大厅当前区服行连续读不到的帧数 */
    private var unreadFrames = 0
    /** 大厅「换区」按钮连续读不到的帧数 */
    private var changeMisses = 0
    private var changeTaps = 0
    /** 点过换区面板里的目标行的时间；>0 表示行已点过，等面板关闭或补点返回 */
    private var rowTappedAt = 0L
    private var backTaps = 0
    /** 点过「开始游戏」的时间；>0 表示在等页面离开 */
    private var startTappedAt = 0L
    private var startTaps = 0
    private var scrolls = 0
    /** 面板里连续「有区服行但没目标行」的帧数 */
    private var missFrames = 0

    fun start(ctx: Context, acc: Account, status: (String) -> Unit, done: (String) -> Unit) {
        val t = acc.activeFarms.firstOrNull()
        if (t == null) {
            done("「${acc.name.ifBlank { "账号" }}」没有勾选需要处理的区服")
            return
        }
        if (FlowControl.running.value) {
            done("有自动化正在执行，请先点「停止」")
            return
        }
        if (!BotService.isReady) {
            done("${Perms.accessibilityReason(ctx)}，无法进入游戏")
            return
        }
        target = t
        appCtx = ctx.applicationContext
        statusCb = status
        doneCb = done
        finished = false
        frames = 0
        lastTapAt = 0L
        unreadFrames = 0
        changeMisses = 0
        changeTaps = 0
        rowTappedAt = 0L
        backTaps = 0
        startTappedAt = 0L
        startTaps = 0
        scrolls = 0
        missFrames = 0
        token = FlowControl.begin("选区进游戏")
        IO.log(ctx, TAG, "目标区服=${t.server}（账号=${acc.name}）")
        status("正在确认当前区服…")
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
            finish("选区进游戏超时，未进入「${target?.server ?: "?"}」，请看日志")
            return
        }
        main.postDelayed({
            if (!FlowControl.alive(token)) {
                finish("已强制停止")
                return@postDelayed
            }
            val fg = BotService.foregroundPackage()
            if (fg != GameLauncher.PACKAGE) {
                if (frames % 6 == 0) IO.log(ctx, TAG, "等待游戏前台 fg=$fg")
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
        val t = target ?: run { finish("没有目标区服"); return }
        val w = bmp.width
        val h = bmp.height
        val now = System.currentTimeMillis()
        val cool = now - lastTapAt

        if (frames % 4 == 1) {
            IO.log(ctx, TAG, "OCR($frames) ${lines.take(16).joinToString(" | ") { it.text }}")
        }

        // ---- 换区面板（选择服务器）----
        val panelTitle = lines.firstOrNull { it.text.contains("选择服") }
        val sidebar = lines
            .filter {
                (it.text.contains("我的服") || it.text.contains("推荐服")) && it.box.left < w * 0.4f
            }
            .minByOrNull { it.box.top }
        if (panelTitle != null || sidebar != null) {
            handlePanel(ctx, t, w, h, lines, now, cool)
            return
        }

        // ---- 开始游戏页（大厅）----
        val startBtn = Ui.line(lines, Ui.GAME_START)
        // 不能把自己的悬浮提示「查找换区入口..」当成换区按钮：提示里也含「换区」
        val changeBtn = lines.firstOrNull {
            Ui.matches(it.text, Ui.CHANGE_AREA) &&
                !it.text.contains("…") && !it.text.contains("..") && !it.text.contains("查找")
        }
        if (startBtn != null || changeBtn != null) {
            handleLobby(ctx, t, w, h, lines, startBtn, changeBtn, now, cool)
            return
        }

        // ---- 两边都不像：点过开始游戏后离开选区页 = 进游戏成功 ----
        if (startTappedAt > 0 && now - startTappedAt > 2500) {
            succeed(ctx, t)
            return
        }
        tick(ctx)
    }

    private fun succeed(ctx: Context, t: Farm) {
        IO.log(ctx, TAG, "已点开始游戏并离开选区页：${t.server}")
        finish("已进入「${t.server}」")
    }

    private fun handleLobby(
        ctx: Context,
        t: Farm,
        w: Int,
        h: Int,
        lines: List<Ocr.ScanLine>,
        startBtn: Ocr.ScanLine?,
        changeBtn: Ocr.ScanLine?,
        now: Long,
        cool: Long,
    ) {
        // 点过开始游戏后先给页面 2.5s 离开，期间这一帧做什么都可能打断过渡
        if (startTappedAt > 0 && now - startTappedAt < 2500) {
            tick(ctx)
            return
        }
        val cur = lines.firstOrNull { SERVER_RE.containsMatchIn(it.text) }
        val same = cur != null && sameServer(cur.text, t.server)
        IO.log(ctx, TAG, "大厅 当前区服=${cur?.text} 目标=${t.server} 同区=$same")

        if (same) {
            unreadFrames = 0
            if (startBtn == null) {
                tick(ctx)
                return
            }
            if (startTaps >= MAX_START_TAPS) {
                finish("「开始游戏」点了没反应，请手动点一次（目标区 ${t.server}）")
                return
            }
            if (cool < 1500) {
                tick(ctx)
                return
            }
            lastTapAt = now
            startTaps++
            startTappedAt = now
            IO.log(ctx, TAG, "点击 开始游戏 (${startBtn.box.centerX()},${startBtn.box.centerY()}) :: ${startBtn.text}")
            BotService.tap(startBtn.box.centerX(), startBtn.box.centerY())
            // 文案避开「开始游戏」（Ui.GAME_START）与区服名
            statusCb("正在进入游戏…")
            tick(ctx)
            return
        }

        // 当前区服读不到：大厅首帧 OCR 常漏，多等几帧；一直读不到就走换区（选区按目标绝对定位，不依赖当前区）
        if (cur == null && unreadFrames < 4) {
            unreadFrames++
            tick(ctx)
            return
        }

        // 不是目标区 → 点「换区」
        if (changeBtn == null) {
            changeMisses++
            if (changeMisses > MAX_CHANGE_MISSES) {
                finish("没找到「换区」按钮，无法切换到「${t.server}」")
                return
            }
            tick(ctx)
            return
        }
        changeMisses = 0
        if (startTappedAt > 0) {
            // 刚点过开始游戏却判定不在目标区：以最新一帧为准，重置进游戏状态重新走一遍
            startTappedAt = 0
        }
        if (changeTaps >= MAX_CHANGE_TAPS) {
            finish("换区按钮点了没反应，无法切换到「${t.server}」")
            return
        }
        if (cool < 3000) {
            tick(ctx)
            return
        }
        lastTapAt = now
        changeTaps++
        // 每次重开换区都把面板侧状态清零，避免上一轮的点选痕迹影响这一轮
        rowTappedAt = 0L
        backTaps = 0
        scrolls = 0
        missFrames = 0
        IO.log(ctx, TAG, "点击 换区 (${changeBtn.box.centerX()},${changeBtn.box.centerY()}) 第 $changeTaps 次")
        BotService.tap(changeBtn.box.centerX(), changeBtn.box.centerY())
        // 文案避开区服名与「换区」字样（会命中 Ui.CHANGE_AREA 被自己点掉）
        statusCb("正在打开服务器列表…")
        tick(ctx)
    }

    private fun handlePanel(
        ctx: Context,
        t: Farm,
        w: Int,
        h: Int,
        lines: List<Ocr.ScanLine>,
        now: Long,
        cool: Long,
    ) {
        // 已点过目标行：面板还开着就补点左上返回箭头，关掉了就交给大厅帧核对区服
        if (rowTappedAt > 0) {
            if (now - rowTappedAt < 1500) {
                tick(ctx)
                return
            }
            if (backTaps < 2) {
                backTaps++
                lastTapAt = now
                tapBack(ctx, w, h, lines)
                tick(ctx)
                return
            }
            // 返回也点过、面板还在：当这次选择没生效，重新找行
            IO.log(ctx, TAG, "点选后仍停在面板，重置重新选区")
            rowTappedAt = 0L
            backTaps = 0
        }

        // 面板里的区服行。行首（去掉 ◆ 装饰后）必须是 手Q/微信：SERVER_RE 只保证行内出现区名，
        // 而悬浮窗状态文案里也可能带目标区名，光靠它会把悬浮窗那行当区服行点掉
        // （实测点中悬浮窗直接返回了助手）。侧栏标签（我的服务器 / 1区-10区…）行首不是
        // 手Q/微信，同样被这条过滤掉，不再需要按坐标划分区域 —— 两列网格左列中心只有 0.25w。
        val rows = lines.filter {
            val plain = it.text.replace(LEADING_JUNK, "")
            SERVER_RE.containsMatchIn(it.text) &&
                (plain.startsWith("手Q") || plain.startsWith("微信"))
        }
        val hit = rows.firstOrNull { sameServer(it.text, t.server) }
        if (hit != null) {
            if (cool < 1200) {
                tick(ctx)
                return
            }
            lastTapAt = now
            rowTappedAt = now
            IO.log(ctx, TAG, "点击 目标区服 (${hit.box.centerX()},${hit.box.centerY()}) :: ${hit.text}")
            BotService.tap(hit.box.centerX(), hit.box.centerY())
            // 悬浮窗文案不能出现区服名 / 换区 / 选择服 / 开始游戏等流程正在搜索的词，
            // 否则下一帧自己被 OCR 当成目标点掉（项目既有约定，见 AddAccountFlow 悬浮窗文案）
            statusCb("已选定目标区服…")
            tick(ctx)
            return
        }

        // 一屏都没读到区服行：面板可能还在渲染，只等待，不消耗判断预算
        if (rows.isEmpty()) {
            missFrames = 0
            tick(ctx)
            return
        }

        // 读到了区服行但连续多帧没有目标行（容忍 OCR 漏读）→ 翻页找，翻完仍没有才算真找不到
        missFrames++
        if (missFrames >= MAX_MISS_FRAMES || scrolls >= MAX_SCROLLS) {
            IO.log(
                ctx, TAG,
                "没找到目标行，可见行=${rows.joinToString { "${it.text}@x=${it.box.centerX()}" }}"
            )
            finish("「选择服务器」里没找到「${t.server}」，请确认该账号勾选的区服已同步")
            return
        }
        if (cool < 1600) {
            tick(ctx)
            return
        }
        scrolls++
        lastTapAt = now
        // 比例坐标滑动面板内容区（不写死像素，手机/平板通用）
        BotService.swipe(
            (w * 0.65f).toInt(), (h * 0.72f).toInt(),
            (w * 0.65f).toInt(), (h * 0.38f).toInt(),
            400,
        )
        IO.log(ctx, TAG, "面板没找到目标区，上滑翻第 $scrolls 屏")
        // 文案避开区服名（会命中面板行过滤被自己点掉）
        statusCb("正在查找目标区服…")
        tick(ctx)
    }

    /**
     * 从换区面板回开始游戏页。返回箭头是图片没有文字，只能点坐标：
     * x 用固定比例，y 跟着「选择服务器」标题走 —— 平板 21:9 上下黑边把 UI 整体下移，
     * 写死 0.067h 会点进黑边（LoginFlow 同款逻辑，游戏不吃系统返回键）。
     */
    private fun tapBack(ctx: Context, w: Int, h: Int, lines: List<Ocr.ScanLine>) {
        val x = (w * BACK_RX).toInt()
        // 面板标题 OCR 常把「服务器」读错一个字，只认前三个字
        val title = lines.firstOrNull { it.text.contains("选择服") }
        // 标题没读到时，用左侧「我的服务器 / 推荐服务器」第一行估算标题行（往上约 0.072H）
        val sidebar = lines
            .filter {
                (it.text.contains("我的服") || it.text.contains("推荐服")) && it.box.left < w * 0.4f
            }
            .minByOrNull { it.box.top }
        val y = when {
            title != null -> title.box.centerY()
            sidebar != null -> (sidebar.box.top - h * 0.072f).toInt().coerceAtLeast(0)
            else -> (h * BACK_RY).toInt()
        }
        IO.log(ctx, TAG, "点击 返回箭头 ($x,$y)")
        BotService.tap(x, y)
    }

    // ------------------------------------------------------------------
    // 区服识别
    // ------------------------------------------------------------------

    /**
     * 归一 OCR 数字混淆：游戏字体里数字 1 稳定被读成字母 l（实测 3x/5x 放大均无解）。
     * 只影响区号比较，区号是纯数字，这些字母只可能来自 OCR 混淆。
     */
    private fun normalizeZone(text: String): String =
        text.replace(Regex("[lLiI|]"), "1").replace(Regex("[oO]"), "0")

    /** 提取区号（如「手Q549区」→ 549）；抢先服没有区号，返回 null */
    private fun serverNo(text: String): String? =
        ZONE_NO_RE.find(normalizeZone(text))?.groupValues?.get(1)

    /**
     * 当前区服行与目标是不是同一个区：
     * - 都有区号 → 比区号（唯一标识，区名 OCR 错字不影响）；
     * - 一边有一边没有 → 不是同一个区（有区号 vs 抢先服）；
     * - 都没有区号 → 抢先服：登录前缀（手Q/微信）都能读出来时必须一致，且双方都含「抢先服」。
     */
    private fun sameServer(cur: String, target: String): Boolean {
        val c = cur.replace(LEADING_JUNK, "").replace(WS, "")
        val tg = target.replace(LEADING_JUNK, "").replace(WS, "")
        val cn = serverNo(c)
        val tn = serverNo(tg)
        if (cn != null && tn != null) return cn == tn
        if (cn != null || tn != null) return false
        val cWx = c.contains("微信")
        val tWx = tg.contains("微信")
        val cQq = c.contains("手Q") || c.contains("QQ")
        val tQq = tg.contains("手Q") || tg.contains("QQ")
        if ((cWx || cQq) && (tWx || tQq) && (cWx != tWx)) return false
        return c.contains("抢先服") && tg.contains("抢先服")
    }
}
