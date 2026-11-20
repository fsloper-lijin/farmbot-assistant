package com.farmbot.assistant.bot

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.farmbot.assistant.core.IO
import com.farmbot.assistant.ocr.Ocr
import com.farmbot.assistant.perm.Perms

/**
 * 在「作物列表」里选中轮作配置指定的作物。
 *
 * 列表是固定的 4 列网格（用户 2026-11-18 给出顺序，见 CropCatalog）：滑到顶后按
 * 「行 = (i-1)/4、列 = (i-1)%4」直接点格子，再读左侧详情卡名称确认（网格里的名字是艺术字，
 * OCR 读不到；详情卡大字能读到）。
 *
 * 默认模式（没指定具体作物）= 当前等级最高可种：从第一个往下扫，遇到「未解锁」格子就回点
 * 它前一个（最后一个已解锁），再核对小时；不匹配继续往前推（16 高/普 靠 CropCatalog 标记区分）。
 */
object CropPickFlow {

    private const val TAG = "Crop"
    private const val TICK = 900L
    private const val MAX_FRAMES = 160
    private const val COOL = 900L
    /** 校验失败后在同一窗口继续往下试的格子上限 */
    private const val MAX_EXTRA = 8

    /** 网格几何（2880×1800 实测，按截图格子图标中心量的）：四列中心 x、首行中心 y、行距 */
    private val COL_X = floatArrayOf(0.6125f, 0.7031f, 0.7938f, 0.8858f)
    private const val ROW0_Y = 0.4217f
    private const val ROW_DY = 0.1328f
    private const val ROWS_PER_SCREEN = 5
    /**
     * 把「第一个未解锁」行往下推到的目标位置（屏幕高度比例）。
     * 0.70h ≈ 第 3 行：下面留两行未解锁，上面只有两行已解锁，候选更少、干扰更小。
     */
    private const val SETTLE_Y = 0.70f

    private val main = Handler(Looper.getMainLooper())
    private val WS = Regex("\\s+")
    private val HOUR_RX = Regex("(\\d{1,2})\\s*小时")

    private var token = 0
    private var appCtx: Context? = null
    private var statusCb: (String) -> Unit = {}
    private var doneCb: ((String) -> Unit)? = null
    private var finished = false

    private var frames = 0
    private var lastTapAt = 0L
    private var openTries = 0
    private var topTries = 0
    private var atTop = false
    private var scrolled = 0
    private var extra = 0
    /** 当前要尝试的 CROPS 索引 */
    private var idx = 0
    /** 点开某格后等详情卡回显 */
    private var pending = false
    /** 连读几帧没有详情卡 = 点到未解锁格子（它不弹卡） */
    private var noCard = 0
    /** 默认模式下同一格等小时读数的帧数（读不到就不耗在这格上） */
    private var waitFrames = 0
    /** 默认模式：是否已用「解锁」标签定位到未解锁边界 */
    private var boundaryFound = false
    /** 默认模式：边界行在本屏的 y（像素），用于取它上方的最后一格 */
    private var boundaryY = 0
    /** 默认模式找「解锁」标签下滑了几屏 */
    private var labelSwipes = 0
    /** 默认模式：把未解锁行推到屏幕下方的微调次数 */
    private var settleTries = 0
    /** 默认模式为找目标小时上翻了几屏 */
    private var upSwipes = 0
    /** 默认模式：点过的标签中心（累计），避免同一格反复点 */
    private val triedLabels = mutableListOf<Pair<Int, Int>>()
    /** 上一帧读到的格子标签（x,y,小时）：同一个标签连续两帧都读到才敢点，防单帧丢字 */
    private var prevCells: List<Triple<Int, Int, Int>> = emptyList()
    /** 点开的标签所属小时：标签经过两帧确认，比卡片上单帧读的时间可信 */
    private var pendingHour = 0
    /** 同一格点开没弹卡时是否已经重试过一次 */
    private var retriedTap = false
    /** 上次滑动时间：滑动惯性没停时 OCR 位置不准，等它停下再判断 */
    private var lastSwipeAt = 0L
    /** 默认模式：命中的格子是未解锁的前一个，等着记录后收尾 */
    private var wantHour = 0
    private var wantHigh = false
    /** 16 普：必须是普通 16 小时作物（格子上没有「高农场币」标签） */
    private var wantNormal = false
    private var wantCrop = ""

    fun start(
        ctx: Context,
        hour: String,
        crop: String,
        status: (String) -> Unit,
        done: (String) -> Unit,
    ) {
        if (FlowControl.running.value) {
            done("有自动化正在执行，请先点「停止」")
            return
        }
        if (!BotService.isReady) {
            done("${Perms.accessibilityReason(ctx)}，无法选择作物")
            return
        }
        if (hour.isEmpty() && crop.isEmpty()) {
            done("作物是默认项，跳过选择")
            return
        }
        appCtx = ctx.applicationContext
        statusCb = status
        doneCb = done
        finished = false
        frames = 0
        lastTapAt = 0L
        openTries = 0
        topTries = 0
        atTop = false
        scrolled = 0
        extra = 0
        pending = false
        noCard = 0
        waitFrames = 0
        boundaryFound = false
        boundaryY = 0
        labelSwipes = 0
        settleTries = 0
        upSwipes = 0
        triedLabels.clear()
        prevCells = emptyList()
        pendingHour = 0
        retriedTap = false
        lastSwipeAt = 0L
        wantHour = when (hour) {
            "h1" -> 1
            "h8" -> 8
            "h16_hi", "h16" -> 16
            "h32" -> 32
            else -> 0
        }
        wantHigh = hour == "h16_hi"
        wantNormal = hour == "h16"
        wantCrop = crop
        idx = if (crop.isEmpty()) 0 else CROPS.indexOfFirst { it.name == crop }.coerceAtLeast(0)
        token = FlowControl.begin("选择作物")
        IO.log(ctx, TAG, "开始选作物 小时=$hour 目标='${crop.ifEmpty { "最高可种" }}' 起始序号=${idx + 1}")
        statusCb("正在选择作物…")
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
            finish("选作物超时，请看日志")
            return
        }
        main.postDelayed({
            if (!FlowControl.alive(token)) {
                finish("已强制停止")
                return@postDelayed
            }
            val pkg = BotService.foregroundPackage()
            // 游戏是 Unity 自绘，rootInActiveWindow 偶尔拿不到（返回 null）；只有明确前台是别的 App 才等
            if (pkg != null && pkg != GameLauncher.PACKAGE) {
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
        // 刚滑过还带着惯性时这一帧的坐标是飘的，直接丢掉重拍
        if (now - lastSwipeAt < 1700L) {
            tick(ctx)
            return
        }

        // 左侧详情卡名称（x 0.35~0.62w、y 0.36~0.48h 的大字）。
        // 不再要求先看到「购买价格」：蘑菇等卡片这几行常被整段漏读，会把已解锁格误判成未解锁。
        // 只认短文本（作物名 2~4 字），带「-」的是悬浮窗文案。
        val detailName = lines.firstOrNull {
            val t = it.text.replace(WS, "")
            it.box.centerX() > bmp.width * 0.35f &&
                it.box.centerX() < bmp.width * 0.62f &&
                it.box.centerY() > bmp.height * 0.36f &&
                it.box.centerY() < bmp.height * 0.48f &&
                it.box.height() > bmp.height * 0.02f &&
                t.length in 1..5 && !t.contains("-")
        }?.text?.replace(WS, "")
        val detailHour = lines.firstOrNull {
            it.box.centerY() > bmp.height * 0.75f &&
                it.box.centerY() < bmp.height * 0.85f &&
                it.box.centerX() > bmp.width * 0.35f &&
                it.box.centerX() < bmp.width * 0.62f &&
                HOUR_RX.containsMatchIn(it.text.replace(WS, ""))
        }?.let { HOUR_RX.find(it.text.replace(WS, ""))?.groupValues?.get(1)?.toIntOrNull() }
        val pick = lines.firstOrNull {
            val t = it.text.replace(WS, "")
            t.contains("选择") && !t.contains("选择作物") && it.box.centerY() > bmp.height * 0.9f
        }
        // 详情卡里也有「高农场币」绿色标签（柚子卡片实测 x≈0.51w y≈0.57h）。
        // 格子顶部的标签在贴边那一行会被表头挡住读不到，卡片这个才是稳妥判据。
        val cardHigh = lines.any {
            it.text.replace(WS, "").contains("高农场") &&
                it.box.centerX() < bmp.width * 0.56f &&
                it.box.centerY() > bmp.height * 0.45f &&
                it.box.centerY() < bmp.height * 0.68f
        }
        // 网格区的「解锁」字样只用于日志排查，判定本身看「点开后有没有卡片」

        // ---- 1) 点开格子后：读详情卡 ----
        if (pending) {
            if (detailName == null) {
                // 未解锁格子点开不弹卡；连续 5 帧都读不到才认定（OCR 抖动能恢复）
                if (++noCard < 5) {
                    tick(ctx)
                    return
                }
                noCard = 0
                pending = false
                if (wantCrop.isEmpty() && !retriedTap && triedLabels.isNotEmpty()) {
                    // 单次点击可能没点中（真机偶发），同一格再点一次再放弃
                    retriedTap = true
                    val p = triedLabels.last()
                    pending = true
                    IO.log(ctx, TAG, "默认模式：标签格点开后没详情卡，同一格重试")
                    BotService.tap(p.first, p.second)
                    tick(ctx)
                    return
                }
                if (wantCrop.isEmpty()) {
                    // 默认模式点的是带时间标签的格子，不该没卡；当这格读不到，换下一格
                    IO.log(ctx, TAG, "默认模式：标签格点开后没详情卡，换下一格")
                } else {
                    idx++
                    extra++
                }
                tick(ctx)
                return
            }
            pending = false
            noCard = 0
            if (wantCrop.isEmpty() && detailHour == null && cropOf(detailName) == null && ++waitFrames < 3) {
                tick(ctx)
                return
            }
            waitFrames = 0
            val nameHit = wantCrop.isEmpty() || detailName.contains(wantCrop) || wantCrop.contains(detailName)
            // 小时优先用卡片上的「收获时间」；1 小时作物的卡片这行常被 OCR 漏读，用目录兜底。
            // 卡片时间是单帧读的，常把 32 读成 52；点开的格子标签是连续两帧确认的，优先信它。
            val cardHour = detailHour ?: cropOf(detailName)?.hour
            val hourHit = wantHour == 0 || cardHour == wantHour || pendingHour == wantHour
            val highHit = if (wantCrop.isEmpty()) {
                // 默认模式：以详情卡的「高农场币」标签为准
                (!wantHigh || cardHigh) && (!wantNormal || !cardHigh)
            } else !wantHigh || (cropOf(detailName)?.high ?: false)
            IO.log(ctx, TAG, "详情卡=$detailName 时=$detailHour 名中=$nameHit 时中=$hourHit 高中=$highHit 卡标签高=$cardHigh")
            if (nameHit && hourHit && highHit) {
                if (pick == null) {
                    tick(ctx)
                    return
                }
                if (cool <= COOL) {
                    tick(ctx)
                    return
                }
                lastTapAt = now
                IO.log(ctx, TAG, "点击选择 (${pick.box.centerX()},${pick.box.centerY()}) 作物=$detailName")
                BotService.tap(pick.box.centerX(), pick.box.centerY())
                statusCb("已选中 $detailName")
                main.postDelayed({ if (!finished) finish("已选择作物 $detailName") }, 1200)
                return
            }
            if (wantCrop.isEmpty()) {
                // 默认模式：卡片小时不符就回扫描分支继续往前找（上次点的标签会被排除）
                IO.log(ctx, TAG, "默认模式：卡片不符（时/高），继续往前找")
                tick(ctx)
                return
            }
            idx++
            extra++
            if (idx < 0 || extra > MAX_EXTRA + 20) {
                finish("没找到符合条件的作物")
                return
            }
            tick(ctx)
            return
        }

        // ---- 2) 还没进列表：点种植行的「选择作物」入口 ----
        val listSeen = lines.any { it.text.replace(WS, "").contains("作物列表") }
        if (!listSeen) {
            if (openTries < 4 && cool > 3000L) {
                // 面板开着就点「选择作物」；否则先点作物旁的 ✕ 清掉已选，再点种植大按钮
                // （真机实测：已有作物时大按钮不弹列表，清掉后才弹）
                val entry = lines.firstOrNull { it.text.replace(WS, "").contains("选择作物") }
                // 没有面板入口时锚定右下角「种植」大按钮的字（真机：按钮图标在字上方 0.06h，✕ 在右上 0.04w/0.11h）
                val plant = lines.firstOrNull {
                    it.text.replace(WS, "").contains("种植") && it.box.centerY() > bmp.height * 0.85f
                }
                val x = entry?.let { it.box.centerX() }
                    ?: plant?.let {
                        (it.box.centerX() + if (openTries == 0) bmp.width * 0.04f else 0f).toInt()
                    }
                    ?: if (openTries == 0) (bmp.width * 0.842f).toInt() else (bmp.width * 0.811f).toInt()
                val y = entry?.let { it.box.centerY() }
                    ?: plant?.let {
                        (it.box.centerY() - if (openTries == 0) bmp.height * 0.11f else bmp.height * 0.062f).toInt()
                    }
                    ?: if (openTries == 0) (bmp.height * 0.810f).toInt() else (bmp.height * 0.858f).toInt()
                openTries++
                lastTapAt = now
                IO.log(ctx, TAG, "点击作物列表入口 ($x,$y) OCR=${entry != null} 第 $openTries 次")
                BotService.tap(x, y)
                statusCb("正在打开作物列表…")
            }
            tick(ctx)
            return
        }

        // ---- 3) 滑到顶 ----
        if (!atTop) {
            // 首行特征就是 30秒/2分钟/5分钟/20分钟；看到「30秒」说明已经在顶，别再盲滑
            if (lines.any { it.text.replace(WS, "").contains("30秒") }) {
                atTop = true
                IO.log(ctx, TAG, "首行有 30秒，已在顶部")
                tick(ctx)
                return
            }
            if (cool <= COOL) {
                tick(ctx)
                return
            }
            lastTapAt = now
            scrollUpList(bmp)
            if (++topTries >= 8) {
                atTop = true
                IO.log(ctx, TAG, "已连续上滑 $topTries 次，认为到顶")
            }
            tick(ctx)
            return
        }

        // ---- 4) 默认模式：靠格子标签找「当前最高可种」 ----
        // 格子下方标签已解锁显示时间（N小时/N分钟/N秒），未解锁显示「N级解锁」。
        // 找到第一个未解锁格 → 它前一个就是最高已解锁；要指定小时就继续往前找同小时的那格。
        if (wantCrop.isEmpty()) {
            defaultScan(ctx, bmp, lines, now, cool)
            return
        }

        // ---- 5) 指定作物：滚到目标格所在屏 ----
        val row = idx / 4
        val col = idx % 4
        val screen = row / ROWS_PER_SCREEN
        if (scrolled < screen) {
            if (cool <= COOL) {
                tick(ctx)
                return
            }
            lastTapAt = now
            scrollDownList(bmp)
            scrolled++
            IO.log(ctx, TAG, "下滑到第 ${scrolled + 1} 屏（目标行 ${row + 1}）")
            tick(ctx)
            return
        }
        if (scrolled > screen) {
            if (cool <= COOL) {
                tick(ctx)
                return
            }
            lastTapAt = now
            scrollUpList(bmp)
            scrolled--
            IO.log(ctx, TAG, "上滑回第 ${scrolled + 1} 屏")
            tick(ctx)
            return
        }

        tapCell(ctx, bmp, idx)
        tick(ctx)
    }

    /**
     * 默认模式：读格子标签定位「第一个未解锁」，取它前一格；
     * 指定小时时继续往前找同小时的已解锁格。点标签中心即可选中该格（真机验证过）。
     */
    private fun defaultScan(
        ctx: Context,
        bmp: android.graphics.Bitmap,
        lines: List<Ocr.ScanLine>,
        now: Long,
        cool: Long,
    ) {
        val cells = lines.mapNotNull { l ->
            val t = l.text.replace(WS, "")
            if (l.box.centerX() < bmp.width * 0.58f || l.box.centerY() < bmp.height * 0.40f) return@mapNotNull null
            when {
                t.contains("解锁") -> l to -1
                else -> gridHour(t)?.let { l to it }
            }
        }
        if (cells.isEmpty()) {
            prevCells = emptyList()
            // 只可能是 OCR 没读到，退一屏再试
            if (++upSwipes > 6) {
                finish("作物列表里没读到格子标签")
                return
            }
            if (cool <= COOL) {
                tick(ctx)
                return
            }
            lastTapAt = now
            scrollUpList(bmp)
            tick(ctx)
            return
        }
        if (!boundaryFound) {
            val lock = cells.filter { it.second < 0 }.minByOrNull { it.first.box.top }
            if (lock == null) {
                // 微调把未解锁行推到了屏底之外：这一屏全是已解锁，直接取最下面一条
                if (settleTries > 0) {
                    boundaryFound = true
                    boundaryY = Int.MAX_VALUE
                    IO.log(ctx, TAG, "默认模式：未解锁行已滚出屏底，本屏全为已解锁")
                    tick(ctx)
                    return
                }
                if (labelSwipes >= 5) {
                    finish("没找到未解锁的格子，可能已全部解锁")
                    return
                }
                if (cool <= COOL) {
                    tick(ctx)
                    return
                }
                lastTapAt = now
                scrollDownList(bmp)
                labelSwipes++
                IO.log(ctx, TAG, "默认模式：本屏无未解锁格，下滑第 $labelSwipes 屏找边界")
                tick(ctx)
                return
            }
            // 用户思路：把「第一个未解锁」推到屏幕中下部，下面留两行未解锁、上面两行已解锁，倒着数好定位。
            // 真机实测列表位移≈手指位移的 2 倍（200px 手指 ≈ 2 行），按差值算手指距离。
            val lockY = lock.first.box.centerY()
            val need = bmp.height * SETTLE_Y - lockY
            if (need > bmp.height * 0.04f && settleTries < 3) {
                if (cool <= COOL) {
                    tick(ctx)
                    return
                }
                lastTapAt = now
                settleTries++
                val dy = (need * 0.5f)
                    .coerceIn(bmp.height * 0.05f, bmp.height * 0.19f).toInt()
                val x = (bmp.width * 0.75f).toInt()
                val y0 = (bmp.height * 0.56f).toInt()
                BotService.swipe(x, y0, x, y0 + dy, 500L) {}
                lastSwipeAt = System.currentTimeMillis()
                prevCells = emptyList()
                IO.log(ctx, TAG, "默认模式：未解锁行 y=$lockY 偏上，下移 $dy px")
                tick(ctx)
                return
            }
            boundaryFound = true
            boundaryY = lock.first.box.top
            IO.log(ctx, TAG, "默认模式：未解锁边界 y=$boundaryY 标签=${lock.first.text}")
            // 滑动惯性可能还没停，先等一帧再点，否则坐标会偏移
            tick(ctx)
            return
        }
        // 单帧 OCR 会漏读个别标签（曾漏「32小时」导致选错作物）：只采信连续两帧同一位置的标签
        val seenBefore = prevCells
        prevCells = cells.map { Triple(it.first.box.centerX(), it.first.box.centerY(), it.second) }
        val stable = cells.filter { c ->
            seenBefore.any { p ->
                kotlin.math.abs(p.first - c.first.box.centerX()) < 40 &&
                    kotlin.math.abs(p.second - c.first.box.centerY()) < 40 && p.third == c.second
            }
        }
        // 高/普不在格子标签上判断（贴边那行的标签会被表头挡、OCR 也常漏），
        // 统一按「从边界往上逐个点开，读详情卡的『高农场币』标签」确认，见 pending 分支。
        val usable = stable.filter { it.second > 0 }
            .filterNot { c ->
                triedLabels.any {
                    kotlin.math.abs(c.first.box.centerX() - it.first) < 40 &&
                        kotlin.math.abs(c.first.box.centerY() - it.second) < 40
                }
            }
        val order = compareBy<Pair<Ocr.ScanLine, Int>>({ it.first.box.top }, { it.first.box.left })
        val hit = if (wantHour == 0) {
            usable.filter { it.first.box.top < boundaryY - 20 }.maxWithOrNull(order)
        } else {
            usable.filter { it.second == wantHour }.maxWithOrNull(order)
        }
        if (hit == null) {
            // 本帧没有一个标签是「连续两帧」的（刚滑过/刚定位边界），先等下一帧确认，别急着翻屏
            if (stable.isEmpty()) {
                tick(ctx)
                return
            }
            if (upSwipes >= 6) {
                finish("没找到已解锁的 ${wantHour} 小时作物")
                return
            }
            if (cool <= COOL) {
                tick(ctx)
                return
            }
            lastTapAt = now
            // 边界正好在本屏最上面时，上一格在屏幕外；翻上来后 y 会超过 boundaryY，别再按它过滤
            boundaryY = Int.MAX_VALUE
            scrollUpList(bmp)
            upSwipes++
            IO.log(ctx, TAG, "默认模式：本屏无目标作物，上翻第 $upSwipes 屏")
            tick(ctx)
            return
        }
        val cx = hit.first.box.centerX()
        val cy = hit.first.box.centerY()
        if (cool <= COOL) {
            tick(ctx)
            return
        }
        lastTapAt = now
        triedLabels.add(cx to cy)
        pendingHour = hit.second
        retriedTap = false
        pending = true
        IO.log(ctx, TAG, "默认模式：点开标签「${hit.first.text}」 ($cx,$cy)")
        BotService.tap(cx, cy)
        tick(ctx)
    }

    private fun scrollDownList(bmp: android.graphics.Bitmap) {
        val x = (bmp.width * 0.75f).toInt()
        // 手势必须整段落在格子区：终点到表头（y<0.45h）真机实测整次拖拽被丢弃。
        // 0.80h→0.56h 约 400px，实测正好滚 4 行，一屏 5 行有重叠不会漏作物。
        BotService.swipe(x, (bmp.height * 0.80f).toInt(), x, (bmp.height * 0.56f).toInt(), 500L) {}
        lastSwipeAt = System.currentTimeMillis()
        prevCells = emptyList()
    }

    private fun scrollUpList(bmp: android.graphics.Bitmap) {
        val x = (bmp.width * 0.75f).toInt()
        BotService.swipe(x, (bmp.height * 0.56f).toInt(), x, (bmp.height * 0.80f).toInt(), 500L) {}
        lastSwipeAt = System.currentTimeMillis()
        prevCells = emptyList()
    }

    private val NUM_RX = Regex("(\\d{1,2})")

    /** 格子标签 → 小时：N秒/N分钟 = 1 小时作物；N小时（OCR 常认成「小吋」）= N */
    private fun gridHour(text: String): Int? {
        val n = NUM_RX.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return when {
            text.contains("秒") || text.contains("分钟") -> 1
            text.contains("吋") || text.contains("小时") || text.contains("时") -> n
            else -> null
        }
    }

    /** 点第 idx 个作物的格子（自动处理列/行，越界则收尾） */
    private fun tapCell(ctx: Context, bmp: android.graphics.Bitmap, index: Int) {
        if (index < 0 || index >= CROPS.size) {
            finish("作物序号越界，没找到作物")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastTapAt <= COOL) return
        val row = index / 4
        val col = index % 4
        // 第一列格子中心会被左侧详情卡盖住（卡片右缘约 0.62w），往右偏半格点，仍在格内
        val x = (bmp.width * (COL_X[col] + if (col == 0) 0.03f else 0f)).toInt()
        val y = (bmp.height * (ROW0_Y + (row % ROWS_PER_SCREEN) * ROW_DY)).toInt()
        lastTapAt = now
        pending = true
        IO.log(ctx, TAG, "点开格子 序号=${index + 1} ${CROPS[index].name} (${row + 1}行 ${col + 1}列) ($x,$y)")
        BotService.tap(x, y)
    }
}
