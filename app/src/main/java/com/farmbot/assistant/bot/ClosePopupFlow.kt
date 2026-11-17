package com.farmbot.assistant.bot

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.farmbot.assistant.core.IO
import com.farmbot.assistant.ocr.Ocr
import com.farmbot.assistant.perm.Perms

/**
 * 进游戏后的「关弹窗」段（用户口述的两种关闭方法都实现）：
 *
 * 1. 检测活动弹窗：右下角「今日内不再弹出」行是弹窗的稳定锚点（不是所有弹窗都有，先做这类）。
 * 2. 先勾选「今日内不再弹出」：勾选框是图形，OCR 读不到，位置按锚点行文字左缘 −0.75 行高推算；
 *    状态用**差分法**判定（点一枪比对前后白像素变化，静态背景自动抵消），见 verifyCheckTap。
 *    勾上后当天不再弹，省掉之后每次进游戏的识别步骤。
 * 3. 关闭弹窗：
 *    - 主路径（点右上角的叉）：锚点确认弹窗在屏后，在右上固定带里从右往左找最靠右的白色像素簇 = ✕
 *      （弹窗外是遮罩、大厅图标被压暗，过不了白阈；找的是「最右」白簇，白色小字在左边不干扰）；
 *    - 兜底（点广告最边缘）：右上找不到叉时，点锚点行正下方的弹窗底缘，点边缘也会关。
 * 4. 连续多帧没有弹窗特征 → 已清干净，结束；不带勾选项的其它弹窗后续再扩展。
 *
 * 悬浮窗状态文案不得出现「今日内不再弹出」字样（会被自己当锚点行），运行期文案已避开。
 */
object ClosePopupFlow {

    private const val TAG = "Pop"
    /** 处理弹窗（点勾/点叉）时的帧间隔 */
    private const val TICK = 1300L
    /**
     * 空闲观察期的帧间隔。目标不是「0.9s 一帧」—— 帧率地板是截屏限流 1.6s（MIN_SHOT_GAP），
     * 压 tick 的意义是把 900+处理时间压到地板之下，让空闲帧贴着 1.6s 地板跑：
     * 真机实测处理耗时 330~670ms 波动，TICK=1300 时帧间隔 1.6~2.0s，TICK=900 时恒 ~1.6s。
     */
    private const val TICK_IDLE = 900L
    /** 总预算：实际帧率 ~1.6s（截屏限流），100 帧 ≈ 2.7 分钟 */
    private const val MAX_FRAMES = 100
    /**
     * 连续多少帧没有弹窗特征就算清理完成（缺席证明的安静窗口）。
     * 曾为 16 帧 ≈26s；「大厅出现 20s+ 才晚弹」查清多为 OCR 漏读变体，用户拍板压到 10 帧
     * ≈16s。若真出现 >16s 后才弹的新证据，调回 16+。
     */
    private const val IDLE_DONE = 10
    /** 勾选框最多点几枪（差分探测 + 补枪恢复的总上限） */
    private const val MAX_CHECK_TAPS = 4
    private const val COOL_CHECK = 1000L
    private const val COOL_CLOSE = 1500L
    /**
     * 勾选状态「差分法」的变化阈值（1 步采样像素数）。
     * 绝对电平两版都被真机打穿：金色主题未勾=0 还好，蓝色主题未勾整片蓝底 5000+、
     * 月度礼册勾选行还有一条白色虚线（未勾也测出 804）—— 静态背景全会污染电平。
     * 现在只看点前/点后的**变化量**：点中了勾选框 Δ≈±300（勾上/取消都算命中），
     * 没点中 Δ≈0，静态背景怎么污染都会抵消。阈值取 100 留足余量。
     */
    private const val DELTA_MIN = 100
    /**
     * 弹窗锚点行判定（宽松，单行）。真机 OCR 对这句的读法五花八门，已实测六种：
     * 「今日内不再弹出」全对 /「1今日有弹出」丢内不带杂字 /「今内不再弹出」今日读成今内 /
     * 「E内不再弹出」今日整个读成字母 E /「出」只剩最后一个字 —— CMD_SCAN 实录（2335,1499）：
     * 前段浅灰字压在金色花边上，ML Kit 整段丢掉，只剩深色的「出」。
     * 含「出」直接放行（右下角位置过滤在调用方，那一带只可能是这句）；
     * 其余按（含今/内不/不再）&&（含不/弹）并集兜。
     */
    private fun isAnchor(text: String): Boolean {
        val t = text.replace(WS, "")
        if (t.contains("出")) return true
        val head = t.contains("今") || t.contains("内不") || t.contains("不再")
        return head && (t.contains("不") || t.contains("弹"))
    }

    /**
     * 游戏内主大厅的底部导航词（真机实测弹窗遮罩下仍可被 OCR 读到）。
     * 弹窗是大厅加载完之后才弹的：必须先见到大厅标志才开始数「无弹窗」帧，
     * 否则进游戏的加载阶段就会被误判成「没有发现弹窗」提前收工（真机踩过）。
     */
    private val LOBBY_NAV = listOf("英雄", "定制", "备战", "战队", "背包")
    private val WS = Regex("\\s+")

    private val main = Handler(Looper.getMainLooper())

    private var token = 0
    private var appCtx: Context? = null
    private var statusCb: (String) -> Unit = {}
    private var doneCb: ((String) -> Unit)? = null
    private var finished = false

    private var frames = 0
    /** 连续没有弹窗锚点的帧数 */
    private var idle = 0
    /** 上一帧是否在处理弹窗（决定下一帧走 TICK 还是贴地板的 TICK_IDLE） */
    private var handling = false
    /** 是否已见到游戏内大厅（底部导航）：见到之前不数「无弹窗」帧 */
    private var lobbySeen = false
    private var lastTapAt = 0L
    private var checkTaps = 0
    /** 上一次点勾选框之前测的白像素数；>=0 表示正在等这一枪的验证帧 */
    private var checkBase = -1
    private var closeTaps = 0
    /** 本次执行是否见到过弹窗（决定结束文案） */
    private var seenPopup = false
    private var checkWarned = false

    fun start(ctx: Context, status: (String) -> Unit, done: (String) -> Unit) {
        if (FlowControl.running.value) {
            done("有自动化正在执行，请先点「停止」")
            return
        }
        if (!BotService.isReady) {
            done("${Perms.accessibilityReason(ctx)}，无法处理游戏弹窗")
            return
        }
        appCtx = ctx.applicationContext
        statusCb = status
        doneCb = done
        finished = false
        frames = 0
        idle = 0
        handling = false
        lobbySeen = false
        lastTapAt = 0L
        checkTaps = 0
        checkBase = -1
        closeTaps = 0
        seenPopup = false
        checkWarned = false
        token = FlowControl.begin("清理游戏弹窗")
        IO.log(ctx, TAG, "开始检查游戏内弹窗")
        status("正在检查游戏内弹窗…")
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
            appCtx?.let { IO.log(it, TAG, "超时：大厅已见=$lobbySeen 见过弹窗=$seenPopup") }
            finish("清理弹窗超时，请看日志")
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
        }, if (handling) TICK else TICK_IDLE)
    }

    private fun onLines(ctx: Context, bmp: android.graphics.Bitmap, lines: List<Ocr.ScanLine>) {
        if (!FlowControl.alive(token)) {
            finish("已强制停止")
            return
        }
        val now = System.currentTimeMillis()
        val cool = now - lastTapAt

        // 大厅底部导航出现 = 游戏已加载完（弹窗遮罩下也能读到）；见到之前不判「无弹窗」
        if (!lobbySeen && lines.any { l ->
                val t = l.text.replace(WS, "")
                l.box.centerY() > bmp.height * 0.85f && LOBBY_NAV.any { t.contains(it) }
            }
        ) {
            lobbySeen = true
            IO.log(ctx, TAG, "已进入游戏大厅，开始监测弹窗（帧=$frames）")
        }

        // 新手大厅：新号的全屏活动页（无勾选项、无遮罩、盖住底部导航），不能按锚点行识别。
        // 标题「新手大厅」在左上角（y<0.15h，聊天栏在底部够不着），关闭走左上返回钩子 ——
        // 和换区面板/商城同一个 D 形钩子图标，比例坐标实测 (211,91)/2880×1800。
        val newbie = lines.firstOrNull {
            it.text.replace(WS, "").contains("新手大厅") &&
                it.box.centerY() < bmp.height * 0.15f
        }
        if (newbie != null) {
            seenPopup = true
            idle = 0
            handling = true
            if (cool > COOL_CLOSE) {
                lastTapAt = now
                val x = (bmp.width * 0.073f).toInt()
                val y = (bmp.height * 0.05f).toInt()
                IO.log(ctx, TAG, "点击 新手大厅返回 ($x,$y) :: ${newbie.text}")
                BotService.tap(x, y)
                // 文案不能写「新手大厅」：悬浮窗自己会被 OCR，标题判定按它找行
                statusCb("正在关闭新手页…")
            }
            tick(ctx)
            return
        }

        val row = lines.firstOrNull {
            val t = it.text.replace(WS, "")
            // 锚点固定在弹窗右下角（用户口述）：下半屏 + 右半屏双保险 ——
            // 上半屏同词行（悬浮窗文案）、左下聊天栏里的「今日不…」发言都一票否决
            isAnchor(t) &&
                it.box.centerY() > bmp.height * 0.6f &&
                it.box.centerX() > bmp.width * 0.55f
        }
        if (row != null) {
            seenPopup = true
            idle = 0
            handling = true
            handlePopup(ctx, bmp, row, cool, now)
            return
        }

        // 没锚点 = 空闲观察期，下一帧贴截屏地板（~1.6s）跑
        handling = false

        // 没锚点的帧把「像锚点的行」打出来（含今/弹/不出，或落在右下角的文字）：
        // 排查「弹窗在屏上却没识别到」全靠它，普通行没必要刷屏
        if (frames % 2 == 1) {
            val probe = lines.filter {
                val t = it.text.replace(WS, "")
                (t.contains("今") || t.contains("弹") || t.contains("出") || t.contains("新手")) ||
                    (it.box.centerY() > bmp.height * 0.6f && it.box.centerX() > bmp.width * 0.5f)
            }.take(8)
            IO.log(
                ctx, TAG,
                "OCR($frames 大厅=$lobbySeen 空闲=$idle) 候选=${probe.map { it.text }}"
            )
        }

        // 锚点行消失 = 当前弹窗已关（或还没弹出来）：弹窗专属计数清零
        checkTaps = 0
        checkBase = -1
        closeTaps = 0
        if (lobbySeen) {
            idle++
        } else {
            idle = 0
            if (frames % 7 == 0) statusCb("正在进入游戏…")
        }
        if (lobbySeen && idle >= IDLE_DONE) {
            finish(if (seenPopup) "游戏内弹窗已清理" else "游戏内没有发现弹窗")
            return
        }
        if (idle == 1 && seenPopup) statusCb("正在检查剩余弹窗…")
        tick(ctx)
    }

    private fun handlePopup(
        ctx: Context,
        bmp: android.graphics.Bitmap,
        row: Ocr.ScanLine,
        cool: Long,
        now: Long,
    ) {
        val white = countTickWhite(bmp, row)
        if (frames % 4 == 1 || checkBase >= 0) {
            IO.log(
                ctx, TAG,
                "弹窗帧=$frames 锚点=${row.text} 白=$white 基线=$checkBase 点勾=$checkTaps 点关=$closeTaps"
            )
        }

        // ---- 1) 勾选：差分法，点一枪看前后变化量，静态背景自动抵消 ----
        if (!verifyCheckTap(ctx, row, white, cool, now)) {
            tick(ctx)
            return
        }

        // ---- 2) 关闭弹窗 ----
        if (cool <= COOL_CLOSE) {
            tick(ctx)
            return
        }
        val hit = topRightWhite(bmp, bmp.width, bmp.height)
        if (hit != null) {
            lastTapAt = now
            closeTaps++
            IO.log(ctx, TAG, "点击 弹窗右上叉 (${hit.first},${hit.second}) 第 $closeTaps 次")
            BotService.tap(hit.first, hit.second)
            statusCb("正在关闭弹窗…")
            tick(ctx)
            return
        }
        // 兜底：点锚点行正下方的弹窗底缘（广告最边缘，点边缘也会关）
        val x = row.box.centerX().coerceIn(0, bmp.width - 1)
        val y = (row.box.bottom + row.box.height() * 1.5f).toInt().coerceAtMost(bmp.height - 1)
        lastTapAt = now
        closeTaps++
        IO.log(ctx, TAG, "点击 弹窗底缘兜底 ($x,$y) 第 $closeTaps 次（右上没找到叉）")
        BotService.tap(x, y)
        statusCb("正在关闭弹窗…")
        tick(ctx)
    }

    /**
     * 勾选框差分探测：点一枪，下一帧比对白像素变化量。
     *  - Δ ≥ +DELTA_MIN：这枪勾上了 → 收尾，交给关闭逻辑；
     *  - Δ ≤ −DELTA_MIN：点之前本来勾着、被这枪点掉了 → 立刻补一枪恢复；
     *  - |Δ| < DELTA_MIN：没点中框 → 冷却后重试，超上限放弃（只影响当日不再弹，不阻塞关闭）。
     * 静态背景（蓝色弹窗底、白色虚线、白标题）点前点后不变，全被差分抵消 —— 电平法做不到这点。
     * 返回 true = 勾选环节已收尾；false = 本帧已点击或在等冷却，调用方直接等下一帧。
     */
    private fun verifyCheckTap(
        ctx: Context,
        row: Ocr.ScanLine,
        white: Int,
        cool: Long,
        now: Long,
    ): Boolean {
        fun fire() {
            checkBase = white
            lastTapAt = now
            checkTaps++
            BotService.tap(checkBoxX(row), row.box.centerY())
            // 文案不得含「今日/弹出/不再」：锚点按这些词找行，写进去会把悬浮窗自己点掉
            statusCb("正在勾选该选项…")
        }

        if (checkBase < 0) {
            if (checkTaps >= MAX_CHECK_TAPS) {
                if (!checkWarned) {
                    checkWarned = true
                    IO.log(ctx, TAG, "勾选放弃（已点 $checkTaps 枪），继续关闭弹窗")
                }
                return true
            }
            if (cool <= COOL_CHECK) return false
            fire()
            IO.log(ctx, TAG, "点击 勾选框 (${checkBoxX(row)},${row.box.centerY()}) 第 $checkTaps 枪（基线白=$white）")
            return false
        }

        val d = white - checkBase
        when {
            d >= DELTA_MIN -> {
                checkBase = -1
                IO.log(ctx, TAG, "已确认勾上（Δ=$d 白=$white）")
                statusCb("已勾选，正在关闭弹窗…")
                return true
            }
            d <= -DELTA_MIN -> {
                if (checkTaps < MAX_CHECK_TAPS && cool > COOL_CHECK) {
                    fire()
                    IO.log(ctx, TAG, "这枪点掉了原勾选，补枪恢复（第 $checkTaps 枪 Δ=$d）")
                    return false
                }
                if (cool <= COOL_CHECK) return false
                checkBase = -1
                if (!checkWarned) {
                    checkWarned = true
                    IO.log(ctx, TAG, "勾选恢复失败（Δ=$d），继续关闭弹窗")
                }
                return true
            }
            else -> {
                if (cool <= COOL_CHECK) return false
                if (checkTaps < MAX_CHECK_TAPS) {
                    fire()
                    IO.log(ctx, TAG, "没点中勾选框（Δ=$d），重试第 $checkTaps 枪")
                    return false
                }
                checkBase = -1
                if (!checkWarned) {
                    checkWarned = true
                    IO.log(ctx, TAG, "勾选多次未命中（Δ=$d），继续关闭弹窗")
                }
                return true
            }
        }
    }

    /**
     * 勾选框中心 X = 锚点行文字左缘 − 0.75 个行高（区域采样共用同一中心）。
     * 不按「今」字下标折算：行首杂字宽窄不一（实测窄的拉丁杂字 L 会把估算挤进白标题里），
     * 固定比例反而在金色（间隙≈0.94 行高）、蓝色（≈0.6 行高）、带杂字行三种实测里都落在框内
     * （框宽约 1.2 行高，Unity 按钮热区还会外扩一截）。
     */
    private fun checkBoxX(row: Ocr.ScanLine): Int =
        (row.box.left - row.box.height() * 0.75f).toInt().coerceAtLeast(0)

    /**
     * 勾选框区域的白色像素数（1 步采样）。勾选态 = 蓝底白勾，未勾态 = 暗色空框；
     * 采样区只用于**前后差分**，不设绝对阈值 —— 蓝色弹窗底、勾选行白色虚线、白标题
     * 都是静态像素，点前点后一样，差分自然抵消（两版电平法都被这些背景打穿，已废弃）。
     */
    private fun countTickWhite(bmp: android.graphics.Bitmap, row: Ocr.ScanLine): Int {
        val h = row.box.height().coerceAtLeast(30)
        val cx = checkBoxX(row)
        val cy = row.box.centerY()
        val half = (h * 0.9f).toInt().coerceAtLeast(30)
        val left = (cx - half).coerceIn(0, bmp.width - 1)
        val top = (cy - half).coerceIn(0, bmp.height - 1)
        val right = (cx + half).coerceIn(left + 1, bmp.width)
        val bottom = (cy + half).coerceIn(top + 1, bmp.height)
        var n = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val c = bmp.getPixel(x, y)
                if (((c shr 16) and 0xFF) > 170 &&
                    ((c shr 8) and 0xFF) > 170 &&
                    (c and 0xFF) > 170
                ) n++
                x++
            }
            y++
        }
        return n
    }

    /**
     * 弹窗右上角的叉：锚点已确认弹窗在屏，直接在右上固定带（x 0.83~0.985w、y 0.07~0.36h）
     * 从右往左找最靠右的白色像素簇。弹窗外是遮罩，大厅右上图标被压暗过不了白阈，不会误点；
     * 曾按「标题行右侧」找叉，皮肤促销弹窗标题在画面中部，重心被内容带偏点进了商城（真机踩过）。
     * 找不到返回 null，由调用方走点底缘兜底。
     */
    private fun topRightWhite(
        bmp: android.graphics.Bitmap,
        w: Int,
        h: Int,
    ): Pair<Int, Int>? {
        val left = (w * 0.83f).toInt().coerceIn(0, w - 1)
        val right = (w * 0.985f).toInt().coerceAtLeast(left + 1)
        val top = (h * 0.07f).toInt().coerceIn(0, h - 1)
        val bottom = (h * 0.36f).toInt().coerceAtLeast(top + 1)
        if (right - left < 4 || bottom - top < 4) return null

        fun white(x: Int, y: Int): Boolean {
            val c = bmp.getPixel(x, y)
            return ((c shr 16) and 0xFF) > 190 &&
                ((c shr 8) and 0xFF) > 190 &&
                (c and 0xFF) > 190
        }

        var maxX = -1
        var x = right - 1
        while (x >= left && maxX < 0) {
            var y = top
            while (y < bottom) {
                if (white(x, y)) {
                    maxX = x
                    break
                }
                y++
            }
            x--
        }
        if (maxX < 0) return null
        // 右缘白簇超过 0.945w 不是叉：历史截图重放时 (2777,355) 命中过右侧大厅图标（遮罩没压住的帧），
        // 点下去会误开商城。真机验证过的叉都在 0.929w（2675/2880），超界的交给底缘兜底
        if (maxX > w * 0.945f) return null

        val sliceL = (maxX - (h * 0.04f).toInt()).coerceAtLeast(left)
        var sx = 0L
        var sy = 0L
        var n = 0
        var xx = sliceL
        while (xx <= maxX) {
            var y = top
            while (y < bottom) {
                if (white(xx, y)) {
                    sx += xx
                    sy += y
                    n++
                }
                y++
            }
            xx++
        }
        if (n < 15) return null
        return (sx / n).toInt() to (sy / n).toInt()
    }
}
