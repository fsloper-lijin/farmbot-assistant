package com.farmbot.assistant.bot

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import com.farmbot.assistant.core.IO
import com.farmbot.assistant.data.Account
import com.farmbot.assistant.data.Farm
import com.farmbot.assistant.data.LoginType
import com.farmbot.assistant.ocr.Ocr
import com.farmbot.assistant.perm.Perms

/**
 * 账号自动登录：从大厅外一路带到「已登录该账号的大厅」。
 *
 * QQ 走「选号选身份」：卡里已存 QQ 昵称 + 身份时直接按名字选号，不再每次重扫切号列表；
 * 只有还空着的账号才进列表清点。微信直接点「与微信好友玩」。
 *
 * 游戏是 Unity 自绘，无障碍节点树拿不到文字，只能靠截图 OCR 定位。
 */
object LoginFlow {

    private const val TAG = "Login"
    /** 身份列表最多向下翻几页 / 找目标时最多向上翻几页 */
    private const val IDENTITY_MAX_SCROLLS = 8
    private const val IDENTITY_MAX_UP_SCROLLS = 10

    private val main = Handler(Looper.getMainLooper())

    private fun tapLine(ctx: Context, line: Ocr.ScanLine, what: String) {
        val x = line.box.centerX()
        val y = line.box.centerY()
        IO.log(ctx, TAG, "点击 $what ($x,$y) :: ${line.text}")
        BotService.tap(x, y)
    }

    // ------------------------------------------------------------------
    // 已有账号时的全自动登录
    //   登录页 → 与QQ好友玩 → 系统「想要打开QQ」→ 允许
    //   → QQ 授权窗口：切换账号 → 切号列表记下所有 QQ 号
    //   → 逐个点开，把每个 QQ 号下的全部游戏身份抓全（不点同意、不产生登录）
    //   → 回到目标 QQ，选中目标身份 → 同意 ×2 → 游戏大厅
    //
    // 为什么只对「还空着的账号」清点：QQ 授权页默认停在「上次登录的号」上，小窗里的
    // 身份列表只露出一两行，不知道身份就没法选。账号卡里存过 QQ 昵称 + 身份之后，
    // 直接按名字找号 → 选身份，不再每次重扫一遍切号列表。
    // 微信没有多账号问题：点「与微信好友玩」后微信直接授权。
    // ------------------------------------------------------------------

    private const val QQ_PKG = "com.tencent.mobileqq"
    private const val WX_PKG = "com.tencent.mm"
    /** 大厅右上角「退出」的兜底比例坐标（OCR 找不到文字时用） */
    private const val EXIT_RX = 0.937f
    private const val EXIT_RY = 0.05f
    /** 换区面板左上角返回箭头的相对位置（手机/平板通用比例） */
    private const val BACK_RX = 0.10f
    private const val BACK_RY = 0.067f
    /** 大厅顶部当前区服提示行。抢先服没有区号（如「微信王者抢先服」），必须单独放行 */
    private const val SERVER_RE = "(手Q|微信)\\s*(王者)?\\s*(抢先服|\\d+\\s*区)"

    @Volatile
    private var autoRunning = false
    /** 自动登录这一轮的取消令牌 */
    private var autoToken = 0
    private var autoTappedAt = 0L
    /** 切号列表里实际点中的 QQ 昵称，登录成功后用于回写 qqNick */
    private var autoPickedText = ""
    /** 已尝试「退出重登」的次数，防止昵称一直对不上时无限循环 */
    private var logoutAttempts = 0
    /** 大厅帧里没找到「换区」入口时的重试次数（换区字号小，OCR 首帧常漏） */
    private var lobbyVisits = 0
    /** 大厅「开始游戏」比右上角昵称先渲染完：命中首帧后先等 1 秒重拍一帧再读昵称 */
    private var lobbySettled = false
    /** 等大厅昵称渲染的时长 */
    private const val LOBBY_SETTLE_MS = 1000L
    /** 已被其他 QQ 账号占用的 QQ 昵称/游戏身份：挑新身份时要跳过 */
    private var excludeQq: Set<String> = emptySet()

    // ---- QQ 切号列表 / 身份目录 ----

    private enum class QqMode {
        /** 清点阶段：把所有 QQ 号的身份列表抓全 */
        SCAN,
        /** 登录阶段：选中目标 QQ 的目标身份 */
        TARGET,
    }

    /** 切号列表里的一个 QQ 账号（QQ 昵称 + QQ 号 + 昵称行位置） */
    private data class QqSlot(val nick: String, val no: String, val box: Rect)

    private var qqMode = QqMode.SCAN
    /** 切号列表扫到的 QQ 账号（按列表顺序去重） */
    private val qqSlots = ArrayList<QqSlot>()
    /** 清点结果：QQ 昵称 → 该号下的全部游戏身份（原始身份固定排第一） */
    private val qqCatalog = LinkedHashMap<String, List<String>>()
    /** 刚点开、正在等身份页出现的 QQ 昵称 */
    private var qqWaitNick: String? = null
    private var qqTarget: QqSlot? = null
    private var qqTargetIdent = ""
    private var qqPickedIdentity = false
    private var qqIdentityRetaps = 0
    /** 目标身份已经点过「同意」的时间；>0 表示授权页一已过，后面只等第二页 */
    private var qqAgreedAt = 0L
    /** 身份页和点击的 QQ 对不上时回列表重选的次数 */
    private var qqSwitchTries = 0
    /** 切号列表是否已向下翻过（账号多于一屏时） */
    private var qqPickerScrolled = false
    private var qqPickerMark = 0
    /** 目标 QQ 不在当前一屏时翻列表找的方向/次数 */
    private var qqPickerFindForward = true
    private var qqPickerFindScrolls = 0
    /** 目标身份不在当前一屏时翻身份列表找的方向/次数/连续无变化次数 */
    private var qqFindForward = true
    private var qqFindScrolls = 0
    private var qqFindSame = 0
    private var qqFindFlips = 0
    private var qqFindLastVisible: List<String> = emptyList()
    /** 小窗拉全屏已尝试次数（下滑白条手势失败上限，防止一直只做手势不登录） */
    private var qqExpandTries = 0
    /** 上一次下滑白条的时间：手势之间要留动画生效的空档 */
    private var qqExpandAt = 0L

    // ---- 当前身份页的翻页扫描 ----

    /** 当前身份页已扫到的昵称（原始身份固定排第一） */
    private val identSeen = LinkedHashSet<String>()
    /** QQ 原始身份昵称（副标题「QQ头像昵称」那行） */
    private var identOrig = ""
    private var identIdle = 0
    private var identScrolls = 0
    private var identPending = false
    private var identLastVisible: List<String> = emptyList()

    /** 换一个 QQ 号时重置身份页扫描状态 */
    private fun resetIdentScan() {
        identSeen.clear()
        identOrig = ""
        identIdle = 0
        identScrolls = 0
        identPending = false
        identLastVisible = emptyList()
    }

    /** 换一个 QQ 号时重置「在身份列表里找目标身份」的状态 */
    private fun resetQqFind() {
        qqFindForward = true
        qqFindScrolls = 0
        qqFindSame = 0
        qqFindFlips = 0
        qqFindLastVisible = emptyList()
    }

    /** 每次自动登录 / 退出重登后重置 QQ 清点状态 */
    private fun resetQqFlow(keepTarget: Boolean = false) {
        qqSlots.clear()
        qqCatalog.clear()
        qqWaitNick = null
        qqPickedIdentity = false
        qqIdentityRetaps = 0
        qqAgreedAt = 0L
        qqSwitchTries = 0
        qqPickerScrolled = false
        qqPickerMark = 0
        qqPickerFindForward = true
        qqPickerFindScrolls = 0
        qqExpandTries = 0
        qqExpandAt = 0L
        lobbySettled = false
        resetIdentScan()
        resetQqFind()
        // 已经知道要登哪个 QQ 号、哪个身份时（来自账号卡），退出重登后也不退回清点模式
        if (!keepTarget) {
            qqMode = QqMode.SCAN
            qqTarget = null
            qqTargetIdent = ""
        }
    }

    /** 本次清点到的其它 QQ 账号（除目标 QQ 外），只上报一次 */
    private var discoveredCb: ((List<Account>) -> Unit)? = null
    /** 登录成功后是否进「换区」面板同步区服。单独点「同步区服」时才为 true */
    private var autoSyncFarms = true

    fun startAuto(
        ctx: Context,
        acc: Account,
        status: (String) -> Unit,
        done: (Account, String) -> Unit,
        excludeQq: Set<String> = emptySet(),
        /** false = 只登录，不进换区面板抓区服（日常执行走这条，省掉每个账号两次 OCR 同步） */
        syncFarms: Boolean = true,
        onDiscovered: (List<Account>) -> Unit = {},
    ) {
        if (autoRunning) {
            done(acc, "上一次自动登录还没结束")
            return
        }
        if (FlowControl.running.value) {
            done(acc, "有自动化正在执行，请先点「停止」")
            return
        }
        if (!BotService.isReady) {
            done(acc, "${Perms.accessibilityReason(ctx)}，无法自动登录")
            return
        }
        if (acc.loginType == LoginType.QQ_QR || acc.loginType == LoginType.WECHAT_QR) {
            done(acc, "扫码账号无法自动登录，请手动扫码登录")
            return
        }
        this.excludeQq = excludeQq.map { it.replace(WS, "") }.filter { it.isNotBlank() }.toSet()
        autoSyncFarms = syncFarms
        discoveredCb = onDiscovered
        if (this.excludeQq.isNotEmpty()) IO.log(ctx, TAG, "切号时排除 QQ 账号=$excludeQq")
        autoRunning = true
        autoToken = FlowControl.begin("自动登录")
        autoPickedText = ""
        resetQqFlow()
        // 账号卡里已经存着 QQ 昵称 + 身份列表：不用再把切号列表逐个点开清点一遍，
        // 直接按名字找到这个 QQ、选中这个身份 —— 手里有啥就用啥
        val wantIdent = acc.name.replace(WS, "")
        val knownIdent = wantIdent.isNotBlank() && acc.qqNick.isNotBlank() &&
            acc.identities.any { plainText(it) == wantIdent }
        if (knownIdent) {
            qqMode = QqMode.TARGET
            qqTarget = QqSlot(acc.qqNick, "", Rect())
            qqTargetIdent = acc.identities.first { plainText(it) == wantIdent }
            IO.log(ctx, TAG, "已知身份「${acc.qqNick}」->「$qqTargetIdent」，跳过 QQ 列表清点")
        }
        autoTappedAt = 0L
        logoutAttempts = 0
        lobbyVisits = 0
        IO.log(ctx, TAG, "自动登录 账号=${acc.name.ifEmpty { acc.id }} 方式=${acc.loginType.name}")
        status("正在登录「${acc.name}」…")
        if (BotService.foregroundPackage() != GameLauncher.PACKAGE) {
            if (!GameLauncher.launch(ctx)) {
                finishAuto(ctx, acc, "无法启动王者荣耀", done)
                return
            }
        }
        pollAuto(ctx, acc, 0, status, done)
    }

    private fun finishAuto(ctx: Context, updated: Account, msg: String, done: (Account, String) -> Unit) {
        if (!autoRunning) return
        autoRunning = false
        FlowControl.end(autoToken)
        IO.log(ctx, TAG, "自动登录结束：$msg")
        done(updated, msg)
    }

    private fun pollAuto(
        ctx: Context,
        acc: Account,
        attempt: Int,
        status: (String) -> Unit,
        done: (Account, String) -> Unit,
    ) {
        if (!FlowControl.alive(autoToken)) {
            finishAuto(ctx, acc, "已强制停止", done)
            return
        }
        if (attempt >= 130) {
            finishAuto(ctx, acc, "登录等待超时，请手动登录", done)
            return
        }
        main.postDelayed({
            val fg = BotService.foregroundPackage()
            val allow = fg == GameLauncher.PACKAGE || fg == QQ_PKG || fg == WX_PKG || fg == null
            if (!allow) {
                if (attempt % 8 == 0) status("等待游戏回到前台…")
                pollAuto(ctx, acc, attempt + 1, status, done)
                return@postDelayed
            }
            BotService.capture { bmp ->
                if (bmp == null) {
                    pollAuto(ctx, acc, attempt + 1, status, done)
                    return@capture
                }
                Ocr.recognize(bmp) { lines ->
                    onAutoLines(ctx, acc, fg, bmp, bmp.width, bmp.height, lines, attempt + 1, status, done)
                }
            }
        }, 1200)
    }

    private fun onAutoLines(
        ctx: Context,
        acc: Account,
        fg: String?,
        bmp: android.graphics.Bitmap,
        w: Int,
        h: Int,
        lines: List<Ocr.ScanLine>,
        next: Int,
        status: (String) -> Unit,
        done: (Account, String) -> Unit,
    ) {
        fun hit(item: Ui.Item) = Ui.line(lines, item)
        fun retry(a: Account = acc) = pollAuto(ctx, a, next, status, done)

        if (!FlowControl.alive(autoToken)) {
            finishAuto(ctx, acc, "已强制停止", done)
            return
        }

        if (next % 4 == 1) {
            IO.log(
                ctx, TAG,
                "OCR(${fg}) ${lines.take(20).joinToString(" | ") { it.text }}"
            )
        }

        // 1) 系统「想要打开 QQ / 是否允许」→ 允许
        if (Ui.has(lines, Ui.OPEN_APP)) {
            val allowLine = lines.asSequence()
                .filter {
                    val t = it.text.replace(Regex("\\s+"), "")
                    t.contains("允许") && t.length <= 8 && !t.contains("不允许")
                }
                .filter { it.box.left >= 0 && it.box.top >= 0 && it.box.right <= w && it.box.bottom <= h }
                .minByOrNull { it.box.top }
            if (System.currentTimeMillis() - autoTappedAt > 2000) {
                autoTappedAt = System.currentTimeMillis()
                if (allowLine != null) tapLine(ctx, allowLine, "允许打开QQ")
                else BotService.tap((w * 0.66f).toInt(), (h * 0.48f).toInt())
                status("已允许打开 QQ，等待授权…")
            }
            retry()
            return
        }

        // 1.5) 版本更新公告等冷启动弹窗：只有右上角一个 ✕ 图形按钮（OCR 读不到），
        //      按 OCR 标题行定位同高度的白色像素簇来点，点不到再按比例兜底
        if (fg == GameLauncher.PACKAGE &&
            Ui.has(lines, Ui.UPDATE_POPUP) && hit(Ui.AGREE_BTN) == null
        ) {
            if (System.currentTimeMillis() - autoTappedAt > 3000) {
                autoTappedAt = System.currentTimeMillis()
                val title = lines.firstOrNull { it.text.contains("更新公告") }
                    ?: lines.firstOrNull { Ui.matches(it.text, Ui.UPDATE_POPUP) }
                val found = title?.let { findWhiteCluster(bmp, w, h, it.box) }
                val x = found?.first ?: (w * 0.769f).toInt()
                val y = found?.second
                    ?: title?.box?.centerY()?.toInt()?.takeIf { it > h * 0.05f }
                    ?: (h * 0.248f).toInt()
                IO.log(ctx, TAG, "关闭公告弹窗 ($x,$y) 像素命中=${found != null} 标题=${title?.text}")
                BotService.tap(x, y)
                status("关闭公告弹窗…")
            }
            retry()
            return
        }

        // 2) 游戏协议弹窗（只在游戏界面，避免误点 QQ/微信 授权页的「同意」）
        val agreeShort = hit(Ui.AGREE_BTN)
        if (fg == GameLauncher.PACKAGE && agreeShort != null &&
            Ui.has(lines, Ui.AGREEMENT_MARKERS)
        ) {
            tapLine(ctx, agreeShort, "同意协议")
            retry()
            return
        }

        // 3) 游戏登录页（必须限定游戏前台：QQ 小窗模式下小窗后面的登录页也会被 OCR 读到，
        //    不加限定会在这里空转、永远轮不到下面的 QQ 弹窗分支）
        val qqLogin = hit(Ui.QQ_LOGIN)
        val wxLogin = hit(Ui.WX_LOGIN)
        if (fg == GameLauncher.PACKAGE && (qqLogin != null || wxLogin != null)) {
            // 刚同意完 QQ 授权时登录页会短暂残留：这时再点一次「与QQ好友玩」会重新拉起
            // 授权窗口，把正在进行的登录打断。留 20s 窗口，只等大厅
            if (qqAgreedAt > 0L && System.currentTimeMillis() - qqAgreedAt < 20000) {
                retry()
                return
            }
            val useWx = acc.loginType == LoginType.WECHAT
            val btn = if (useWx) wxLogin else qqLogin
            if (btn != null && System.currentTimeMillis() - autoTappedAt > 1500) {
                autoTappedAt = System.currentTimeMillis()
                tapLine(ctx, btn, if (useWx) "与微信好友玩" else "与QQ好友玩")
                status(if (useWx) "已点微信登录，等待授权…" else "已点 QQ 登录，等待 QQ 授权…")
            }
            retry()
            return
        }

        // 4) QQ 授权小窗（MIUI 自由窗口）：先清点所有 QQ 号的身份，再选目标身份登录
        if (fg == QQ_PKG) {
            onQqWindow(ctx, acc, bmp, w, h, lines, status, { retry() }) { msg ->
                finishAuto(ctx, acc, msg, done)
            }
            return
        }

        // 5) 微信授权页：确认/允许
        if (fg == WX_PKG) {
            val allow = hit(Ui.WX_ALLOW)
            if (allow != null && System.currentTimeMillis() - autoTappedAt > 2000) {
                autoTappedAt = System.currentTimeMillis()
                tapLine(ctx, allow, "微信授权")
                status("已确认微信授权…")
            }
            retry()
            return
        }

        // 6) 已回到游戏大厅：读昵称 → 必要时退出切换账号 → 同步区服 → 回写账号
        val lobby = hit(Ui.GAME_START) ?: hit(Ui.CHANGE_AREA)
        if (fg == GameLauncher.PACKAGE && lobby != null) {
            // 大厅先画出「开始游戏」，右上角昵称要晚约 1 秒才渲染：命中首帧先等 1 秒重拍，
            // 否则读到的是空昵称或上一帧残留文字
            if (!lobbySettled) {
                lobbySettled = true
                IO.log(ctx, TAG, "命中大厅首帧，等 ${LOBBY_SETTLE_MS}ms 再读昵称")
                main.postDelayed({ pollAuto(ctx, acc, next, status, done) }, LOBBY_SETTLE_MS)
                return
            }
            handleLobby(ctx, acc, bmp, w, h, lines, status, done)
            return
        }

        retry()
    }

    // ------------------------------------------------------------------
    // QQ 授权窗口：先清点、后登录
    //   QQ 小窗只有两种页面：切号列表（选择要登录的账号）和身份列表
    //   （你可以使用不同的头像/昵称登录）。切号列表是目录入口，身份列表是该号的内容。
    //   清点阶段只翻列表、不点同意；全部记完后才回到目标 QQ 选身份登录。
    // ------------------------------------------------------------------

    private fun onQqWindow(
        ctx: Context,
        acc: Account,
        bmp: android.graphics.Bitmap,
        w: Int,
        h: Int,
        ocrLines: List<Ocr.ScanLine>,
        status: (String) -> Unit,
        retry: () -> Unit,
        fail: (String) -> Unit,
    ) {
        // QQ 还是小窗时昵称列太窄、显示不全：先下滑窗口底部白条把它拉成全屏再读
        if (maybeExpandQqWindow(ctx, bmp, w, h, status)) { retry(); return }

        // QQ 授权小窗是标准原生控件，优先用控件树；读不到再用 OCR 兜底
        val winText = Nodes.allWindowsText()
        val lines = Nodes.allWindowsLines()
            .map { Ocr.ScanLine(it.first, it.second) }
            .ifEmpty { ocrLines }
        if (winText.isBlank() && lines.isEmpty()) { retry(); return }
        val cool = System.currentTimeMillis() - autoTappedAt

        val inPicker = Ui.inText(winText, Ui.PICKER_PAGE) ||
            lines.any { Ui.matches(it.text, Ui.PICKER_TITLE) }
        if (inPicker) {
            onQqPicker(ctx, acc, w, h, lines, cool, status, retry, fail)
            return
        }
        val inIdentity = Ui.inText(winText, Ui.IDENTITY_PAGE) ||
            Ui.inText(winText, Ui.IDENTITY_SUBTITLE) ||
            lines.any { SUBTITLE_RE.containsMatchIn(plainText(it.text)) }
        if (inIdentity) {
            onQqIdentity(ctx, acc, bmp, w, h, lines, cool, status, retry, fail)
            return
        }

        // 授权页二「QQ 申请使用 你在王者荣耀的以下信息」：授权页一同意过之后才点
        if (qqAgreedAt > 0L && cool > 2500) {
            if (Nodes.clickByTextAllWindows(Ui.AGREE_ANY.keys) != null) {
                autoTappedAt = System.currentTimeMillis()
                qqAgreedAt = System.currentTimeMillis()
                IO.log(ctx, TAG, "节点点击 同意QQ授权（读取社交信息）")
                status("已同意 QQ 授权，等待进入游戏…")
            }
        }
        retry()
    }

    /**
     * QQ 授权窗还是小窗时，切号列表的昵称列太窄会被截断。小窗底部有条灰色白条，
     * 从白条上向下滑（系统「小窗全屏」手势）就能把它拉成全屏。
     * 白条位置用像素在窗口底部一带扫出来（和公告 ✕ 的白色簇同一套思路），不写死坐标。
     *
     * 返回 true = 本帧只做这一件事（发了手势/等动画），调用方直接等下一帧。
     * 已经是全屏、窗口读不到、或连续失败 3 次则返回 false，按小窗原样继续登录。
     */
    private fun maybeExpandQqWindow(
        ctx: Context,
        bmp: android.graphics.Bitmap,
        w: Int,
        h: Int,
        status: (String) -> Unit,
    ): Boolean {
        val win = BotService.windowBoundsOf(QQ_PKG) ?: return false
        // 铺满屏幕（或接近）＝已经是全屏，不用折腾
        if (win.width() >= w * 0.9f && win.height() >= h * 0.9f) return false
        if (qqExpandTries >= 3) return false
        val now = System.currentTimeMillis()
        if (qqExpandAt > 0 && now - qqExpandAt < 2500) return true   // 手势刚发出去，等动画落地

        // 在窗口底部 10% 一带逐行数灰白像素（白条是灰胶囊，横向很长）
        val bandTop = (win.bottom - win.height() * 0.10f).toInt().coerceAtLeast(win.top)
        var barY = -1
        var barX = -1
        var bestHit = 0
        for (y in bandTop until win.bottom) {
            var left = -1
            var right = -1
            var n = 0
            var x = win.left
            while (x < win.right) {
                val c = bmp.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (kotlin.math.abs(r - g) < 22 && kotlin.math.abs(g - b) < 22 && r in 70..200) {
                    if (left < 0) left = x
                    right = x
                    n++
                }
                x += 3
            }
            // 白条横向占窗口宽度 15% 以上才算数，单颗噪点不算
            if (n > bestHit && n * 3 >= win.width() * 0.15f) {
                bestHit = n
                barY = y
                barX = (left + right) / 2
            }
        }
        qqExpandTries++
        qqExpandAt = now
        if (barY < 0) {
            IO.log(ctx, TAG, "小窗白条没扫到（窗口=$win），第 $qqExpandTries 次")
            return qqExpandTries < 3   // 还有次数就等下一帧再试扫，3 次后按小窗继续
        }
        val fromY = barY
        val toY = (barY + h * 0.12f).toInt().coerceAtMost(h - 4)
        IO.log(ctx, TAG, "小窗拉全屏：白条($barX,$barY) 下滑到 $toY（窗口=$win 第 $qqExpandTries 次）")
        status("正在展开 QQ 小窗…")
        BotService.swipe(barX, fromY, barX, toY, 350)
        return true
    }

    /** 切号列表：先把所有 QQ 号记下来，再逐个点开清点身份，最后点目标 QQ */
    private fun onQqPicker(
        ctx: Context,
        acc: Account,
        w: Int,
        h: Int,
        lines: List<Ocr.ScanLine>,
        cool: Long,
        status: (String) -> Unit,
        retry: () -> Unit,
        fail: (String) -> Unit,
    ) {
        val rows = parsePickerRows(lines, w, h)
        mergePickerRows(rows)
        if (rows.isNotEmpty()) {
            IO.log(ctx, TAG, "切号列表=${rows.joinToString { "${it.nick}(${it.no})" }} 已清点=${qqCatalog.keys}")
        }

        if (qqMode == QqMode.SCAN) {
            // 1) 还有没清点的 QQ 号 → 依次点开
            val unscanned = qqSlots.filter { qqCatalog[it.nick] == null }
            if (unscanned.isNotEmpty()) {
                if (qqWaitNick != null) { retry(); return }   // 上一次点击还在切页
                val next = unscanned.first()
                if (cool > 1200) {
                    autoTappedAt = System.currentTimeMillis()
                    qqWaitNick = next.nick
                    qqSwitchTries = 0
                    resetIdentScan()
                    IO.log(ctx, TAG, "点开 QQ「${next.nick}(${next.no})」记录身份")
                    status("正在记录 QQ「${next.nick}」的登录身份…")
                    BotService.tap(next.box.centerX(), next.box.centerY())
                }
                retry(); return
            }
            // 2) 当前一屏都清点完了：账号多于一屏时向下翻一次继续找
            if (!qqPickerScrolled) {
                qqPickerScrolled = true
                qqPickerMark = qqSlots.size
                scrollPicker(lines, h, true)
                IO.log(ctx, TAG, "切号列表向下翻（已记录 ${qqSlots.size} 个 QQ）")
                retry(); return
            }
            if (qqSlots.size > qqPickerMark) { qqPickerScrolled = false; retry(); return }
            // 3) 清点完成 → 按目录算目标 QQ 与身份
            qqMode = QqMode.TARGET
            if (!resolveQqTarget(ctx, acc)) {
                fail(
                    if (acc.name.isBlank()) "没找到可用的 QQ 登录身份，请先在 QQ 里创建"
                    else "没有在任何 QQ 账号下找到登录身份「${acc.name}」，请检查账号名"
                )
                return
            }
            // 目录已经完整：把除目标 QQ 外的其它号交给上层补成账号卡片
            notifyDiscovered(ctx)
            retry(); return
        }

        // TARGET：在列表里点目标 QQ
        val target = qqTarget ?: run { fail("没读到 QQ 账号列表，请重试"); return }
        val row = rows.firstOrNull { it.nick == target.nick }
        if (row == null) {
            if (qqPickerFindScrolls < 4 && cool > 900) {
                qqPickerFindScrolls++
                autoTappedAt = System.currentTimeMillis()
                if (qqPickerFindScrolls == 3) qqPickerFindForward = false
                scrollPicker(lines, h, qqPickerFindForward)
                IO.log(ctx, TAG, "切号列表找目标「${target.nick}」翻页 $qqPickerFindScrolls（forward=$qqPickerFindForward）")
            }
            retry(); return
        }
        if (cool > 1200) {
            autoTappedAt = System.currentTimeMillis()
            qqWaitNick = target.nick
            qqSwitchTries = 0
            resetQqFind()
            autoPickedText = target.nick
            IO.log(ctx, TAG, "点开目标 QQ「${target.nick}(${target.no})」的身份「$qqTargetIdent」")
            status("登录 QQ「${target.nick}」的身份「$qqTargetIdent」…")
            BotService.tap(row.box.centerX(), row.box.centerY())
        }
        retry()
    }

    /** 身份列表：清点阶段抓全身份后回列表；登录阶段选目标身份并同意 */
    private fun onQqIdentity(
        ctx: Context,
        acc: Account,
        bmp: android.graphics.Bitmap,
        w: Int,
        h: Int,
        lines: List<Ocr.ScanLine>,
        cool: Long,
        status: (String) -> Unit,
        retry: () -> Unit,
        fail: (String) -> Unit,
    ) {
        // 已经点过同意：这一页正在切到授权页二，等它自己走
        if (qqAgreedAt > 0L) { retry(); return }

        val owner = qqWaitNick
        if (owner == null) {
            // 刚进 QQ 窗口时停在「上次登录的号」上：先回切号列表从头清点，绝不在这一页点同意
            if (cool > 1200) {
                clickSwitch(ctx, "进入 QQ 窗口先清点")
                status("打开 QQ 账号列表…")
            }
            retry(); return
        }

        val rows = parseIdentityRows(lines, w, h)
        if (rows.isEmpty()) { retry(); return }

        // 核对这一页确实是刚点的那个 QQ（原始身份名就是 QQ 昵称）
        val orig = rows.firstOrNull { it.original }?.name?.let(::plainText)
        if (qqSwitchTries < 2 && !orig.isNullOrEmpty() && orig != plainText(owner)) {
            qqSwitchTries++
            IO.log(ctx, TAG, "身份页属于「$orig」而不是「$owner」，回列表重选（第 $qqSwitchTries 次）")
            if (cool > 1200) clickSwitch(ctx, "页面不对")
            retry(); return
        }
        qqSwitchTries = 0

        if (qqMode == QqMode.SCAN) {
            if (!scanIdentityPage(ctx, rows, cool, status)) { retry(); return }
            val ids = identityNames()
            qqCatalog[owner] = ids
            IO.log(ctx, TAG, "QQ「$owner」下 ${ids.size} 个身份：$ids")
            status("已记录 QQ「$owner」的 ${ids.size} 个身份")
            qqWaitNick = null
            resetIdentScan()
            if (cool > 900) clickSwitch(ctx, "清点下一个 QQ")
            retry(); return
        }

        pickTargetIdentity(ctx, bmp, w, h, lines, rows, cool, status, retry, fail)
    }

    private fun clickSwitch(ctx: Context, why: String) {
        autoTappedAt = System.currentTimeMillis()
        val hit = Nodes.clickByTextAllWindows(Ui.SWITCH_ACCOUNT.keys)
        IO.log(ctx, TAG, "点击 切换账号（$why）-> ${hit ?: "未命中"}")
    }

    /** 翻动切号列表。只用无障碍滚动：手势会被 QQ 当成拖拽，把授权弹窗拖掉 */
    private fun scrollPicker(lines: List<Ocr.ScanLine>, h: Int, forward: Boolean) {
        val title = lines.firstOrNull { Ui.matches(it.text, Ui.PICKER_TITLE) }
        val top = title?.box?.bottom ?: (h * 0.5f).toInt()
        Nodes.scrollAllWindows(forward, top, h)
    }

    /** 扫当前身份页：返回 true 表示已翻到底（或翻不动），可以收工 */
    private fun scanIdentityPage(
        ctx: Context,
        rows: List<IdentityRow>,
        cool: Long,
        status: (String) -> Unit,
    ): Boolean {
        rows.firstOrNull { it.original }?.let { if (identOrig.isBlank()) identOrig = it.name }
        val before = identSeen.size
        rows.forEach { identSeen.add(it.name) }
        val visible = rows.map { it.name }
        if (identPending) {
            identPending = false
            if (visible.toSet() == identLastVisible.toSet() && identSeen.size == before) identIdle++
            else if (identSeen.size > before) identIdle = 0
        }
        identLastVisible = visible

        if (identIdle >= 2 || identScrolls >= IDENTITY_MAX_SCROLLS) return true
        if (cool > 900) {
            autoTappedAt = System.currentTimeMillis()
            identScrolls++
            identPending = true
            Nodes.scrollAllWindows(true, rows.first().box.top, rows.last().box.bottom)
            status("正在查看该 QQ 的全部登录身份…（已发现 ${identSeen.size} 个）")
        }
        return false
    }

    /** 已扫到的身份，原始身份固定排第一（和账号卡片的下拉顺序一致） */
    private fun identityNames(): List<String> {
        val out = ArrayList<String>(identSeen.size + 1)
        if (identOrig.isNotBlank()) out += identOrig
        identSeen.filterTo(out) { plainText(it) != plainText(identOrig) }
        return out
    }

    /** 登录阶段：选中目标身份 → 核对对勾 → 同意 */
    private fun pickTargetIdentity(
        ctx: Context,
        bmp: android.graphics.Bitmap,
        w: Int,
        h: Int,
        lines: List<Ocr.ScanLine>,
        rows: List<IdentityRow>,
        cool: Long,
        status: (String) -> Unit,
        retry: () -> Unit,
        fail: (String) -> Unit,
    ) {
        val want = plainText(qqTargetIdent)
        val target = rows.firstOrNull { plainText(it.name) == want }
        if (target == null) {
            // 目标身份被挤出小窗：上下翻页找，两个方向都翻不动才算真的没有
            if (cool > 900 && qqFindScrolls < IDENTITY_MAX_SCROLLS + IDENTITY_MAX_UP_SCROLLS) {
                val visible = rows.map { it.name }
                if (qqFindLastVisible.isNotEmpty() && visible.toSet() == qqFindLastVisible.toSet()) {
                    qqFindSame++
                } else {
                    qqFindSame = 0
                }
                qqFindLastVisible = visible
                if (qqFindSame >= 2) {
                    qqFindSame = 0
                    qqFindForward = !qqFindForward
                    qqFindFlips++
                    if (qqFindFlips >= 2) {
                        fail("QQ「${qqWaitNick ?: "?"}」下没有登录身份「$qqTargetIdent」")
                        return
                    }
                }
                qqFindScrolls++
                autoTappedAt = System.currentTimeMillis()
                Nodes.scrollAllWindows(qqFindForward, rows.first().box.top, rows.last().box.bottom)
                status("在 QQ 里查找身份「$qqTargetIdent」…")
            }
            retry(); return
        }

        val agree = Ui.line(lines, Ui.AGREE_BTN)
        val panelRight = agree?.box?.right ?: (w * 0.92f).toInt()
        val tick = rows.firstOrNull { tickOnRow(bmp, it, panelRight) }

        if (!qqPickedIdentity) {
            if (tick?.name == target.name) {
                qqPickedIdentity = true
                IO.log(ctx, TAG, "身份「${target.name}」已是当前选中项")
            } else if (cool > 1200) {
                autoTappedAt = System.currentTimeMillis()
                qqPickedIdentity = true
                qqIdentityRetaps = 0
                IO.log(ctx, TAG, "点击 身份「${target.name}」(${target.box.centerX()},${target.box.centerY()})")
                BotService.tap(target.box.centerX(), target.box.centerY())
                status("已选择身份「${target.name}」…")
            }
            retry(); return
        }

        if (qqIdentityRetaps < 2 && tick != null && tick.name != target.name && cool > 1500) {
            qqIdentityRetaps++
            autoTappedAt = System.currentTimeMillis()
            IO.log(ctx, TAG, "对勾在「${tick.name}」而非「${target.name}」，重新点选（第 $qqIdentityRetaps 次）")
            BotService.tap(target.box.centerX(), target.box.centerY())
            retry(); return
        }

        if (cool > 2500) {
            autoTappedAt = System.currentTimeMillis()
            var hit = Nodes.clickByTextAllWindows(Ui.AGREE_ANY.keys)
            if (hit == null && agree != null) {
                BotService.tap(agree.box.centerX(), agree.box.centerY())
                hit = "coord"
            }
            IO.log(ctx, TAG, "点击 同意QQ授权（身份「${target.name}」）-> ${hit ?: "未命中"}")
            if (hit != null) {
                qqAgreedAt = System.currentTimeMillis()
                status("已同意 QQ 授权，正在登录「$qqTargetIdent」…")
            } else {
                status("没找到「同意」按钮，请检查 QQ 授权窗口")
            }
        }
        retry()
    }

    /** 按清点目录定目标 QQ 与身份：优先目标身份名 → 其次没被其他账号占用的身份 */
    private fun resolveQqTarget(ctx: Context, acc: Account): Boolean {
        val want = plainText(acc.name)
        val wantNick = plainText(acc.qqNick)
        if (want.isNotEmpty()) {
            val owners = qqSlots.filter { s ->
                qqCatalog[s.nick]?.any { plainText(it) == want } == true
            }
            if (owners.isEmpty()) {
                IO.log(ctx, TAG, "目录里没有身份「$want」：$qqCatalog")
                return false
            }
            val slot = owners.firstOrNull { wantNick.isNotEmpty() && plainText(it.nick) == wantNick }
                ?: owners.first()
            qqTarget = slot
            qqTargetIdent = qqCatalog[slot.nick]!!.first { plainText(it) == want }
            autoPickedText = slot.nick
            IO.log(ctx, TAG, "目标身份「$want」→ QQ「${slot.nick}(${slot.no})」 目录=$qqCatalog")
            return true
        }
        for (s in qqSlots) {
            val free = qqCatalog[s.nick]?.firstOrNull { plainText(it) !in excludeQq } ?: continue
            qqTarget = s
            qqTargetIdent = free
            autoPickedText = s.nick
            IO.log(ctx, TAG, "新账号 → QQ「${s.nick}(${s.no})」的身份「$free」 排除=$excludeQq")
            return true
        }
        return false
    }

    /**
     * 清点目录里除目标 QQ 外的其它号 → 每个号一张账号卡（身份放 identities，右侧下拉切换）。
     * 只上报一次：上层合并卡片写盘，并把这些号追加进执行队列。
     */
    private fun notifyDiscovered(ctx: Context) {
        val cb = discoveredCb ?: return
        discoveredCb = null
        val targetNick = qqTarget?.nick.orEmpty()
        val found = ArrayList<Account>()
        for (s in qqSlots) {
            if (s.nick == targetNick) continue
            val ids = qqCatalog[s.nick]?.takeIf { it.isNotEmpty() } ?: continue
            // 默认登录身份：优先没被其它账号占用的那个，原始身份（第一个）兜底
            val pick = ids.firstOrNull { plainText(it) !in excludeQq } ?: ids.first()
            found += Account(
                name = pick,
                qqNick = s.nick,
                qqNo = s.no,
                loginType = LoginType.QQ,
                identities = ids,
            )
        }
        if (found.isEmpty()) return
        IO.log(
            ctx, TAG,
            "清点到的其它 QQ 账号=${found.joinToString { "${it.qqNick}->${it.identities}" }}"
        )
        cb(found)
    }

    private val QQNO_RE = Regex("^\\d{5,12}$")
    /** 「昵称 12345678」这种昵称与 QQ 号被 OCR 拼成一行的情况 */
    private val QQNO_TAIL_RE = Regex("^(.+?)[\\s·:：]+(\\d{5,12})$")

    /** 解析「选择要登录的账号」列表：QQ 昵称 + QQ 号（小窗里是上下两行） */
    private fun parsePickerRows(lines: List<Ocr.ScanLine>, w: Int, h: Int): List<QqSlot> {
        fun inBounds(l: Ocr.ScanLine) =
            l.box.left >= 0 && l.box.top >= 0 && l.box.right <= w && l.box.bottom <= h
        val page = lines.filter { inBounds(it) }
        val title = page.firstOrNull { Ui.matches(it.text, Ui.PICKER_TITLE) } ?: return emptyList()
        val area = page.filter { it.box.centerY() > title.box.bottom }.sortedBy { it.box.top }

        // a) 昵称和 QQ 号在同一行
        val inline = ArrayList<QqSlot>()
        for (l in area) {
            val m = QQNO_TAIL_RE.find(plainText(l.text)) ?: continue
            val nick = m.groupValues[1].trim()
            if (nick.isNotEmpty() && !isPickerJunk(nick)) inline += QqSlot(nick, m.groupValues[2], l.box)
        }
        if (inline.isNotEmpty()) return inline

        // b) 昵称一行、QQ 号一行（QQ 小窗的真实结构）
        val out = ArrayList<QqSlot>()
        for (n in area.filter { QQNO_RE.matches(plainText(it.text)) }) {
            val no = plainText(n.text)
            val nick = area
                .filter { it !== n && !QQNO_RE.matches(plainText(it.text)) }
                .filter { !isPickerJunk(plainText(it.text)) }
                .filter { overlapX(it.box, n.box) }
                .filter { it.box.bottom <= n.box.top + n.box.height() / 2 }
                .maxByOrNull { it.box.bottom }
                ?: continue
            out += QqSlot(plainText(nick.text), no, nick.box)
        }
        return out
    }

    private fun mergePickerRows(rows: List<QqSlot>) {
        for (r in rows) {
            val same = qqSlots.firstOrNull {
                if (r.no.isNotEmpty() && it.no.isNotEmpty()) it.no == r.no else it.nick == r.nick
            }
            if (same == null) {
                qqSlots += r
            } else if (same.no.isEmpty() && r.no.isNotEmpty()) {
                qqSlots[qqSlots.indexOf(same)] = same.copy(no = r.no, box = r.box)
            }
        }
    }

    /** 切号列表里不可能是账号名的行（标题、按钮、状态栏时间等） */
    private fun isPickerJunk(s: String): Boolean {
        if (s.isBlank() || s.length > 24) return true
        val junk = listOf(
            "选择要登录", "添加账号", "切换账号", "切换帐号", "取消", "删除", "返回", "关闭",
            "王者荣耀", "同意", "拒绝", "申请使用", "登录", "「", "…", "×",
        )
        return junk.any { s.contains(it) } || NOISE_RE.containsMatchIn(s)
    }

    private val WS = Regex("\\s+")
    /** QQ 原始身份行独有的副标题；OCR 常把 QQ 认成 0Q/OQ、昵称认成呢称 */
    private val SUBTITLE_RE = Regex("(QQ|qq|0Q|OQ|oq)\\s*头像\\s*[、,，/]?\\s*(昵称|呢称|昵称)")
    /**
     * 绝不可能是身份昵称的行：状态栏时间/日期/星期、纯数字（电量/秒数）、纯标点。
     * 之前扫列表时把「20:36」「11月16日周一」「6」当成身份写回账号，就是缺这层过滤。
     */
    private val NOISE_RE = Regex(
        "^[\\d\\s%:.：/\\\\-]+$" +                      // 纯数字/时间/电量
            "|\\d{1,2}\\s*[:：]\\s*\\d{2}" +              // 12:34
            "|\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日" +        // 11月16日
            "|周[一二三四五六日天]"
    )

    /** 授权页上一行身份：昵称 + 是否 QQ 原始身份 + 昵称文字框 */
    private data class IdentityRow(val name: String, val original: Boolean, val box: Rect)

    private fun plainText(t: String) = t.replace(WS, "")

    private fun overlapX(a: Rect, b: Rect): Boolean {
        val lo = maxOf(a.left, b.left)
        val hi = minOf(a.right, b.right)
        return hi - lo > minOf(a.width(), b.width()) / 2
    }

    /**
     * 解析身份列表：以「同意」为列表下边界、提示行「你可以使用不同的头像/昵称登录」为上边界，
     * 原始身份 = 带「QQ头像昵称」副标题的那行（昵称在副标题正上方，或同一行被 OCR 拼起来）。
     * 其余落在这个区域里的文字行就是创建出来的分身。原始身份固定排第一，和 UI 下拉一致。
     */
    private fun parseIdentityRows(lines: List<Ocr.ScanLine>, w: Int, h: Int): List<IdentityRow> {
        fun inBounds(l: Ocr.ScanLine) =
            l.box.left >= 0 && l.box.top >= 0 && l.box.right <= w && l.box.bottom <= h
        val page = lines.filter { inBounds(it) }
        // 「选择要登录的账号」是切 QQ 号那一页，里面的行是 QQ 账号不是游戏身份
        if (page.any { Ui.matches(it.text, Ui.PICKER_TITLE) }) return emptyList()
        // 列表上下边界只能靠提示行和「同意」定；提示行没读到时宁可这一帧不解析，
        // 否则会把状态栏（时间/日期/电量）当成身份昵称写进账号
        val hint = Ui.line(page, Ui.IDENTITY_PAGE) ?: return emptyList()
        val agree = page.filter { Ui.matches(it.text, Ui.AGREE_BTN) }.maxByOrNull { it.box.top }
        val top = hint.box.bottom + 1
        val bottom = agree?.box?.top ?: h
        if (bottom - top < 20) return emptyList()
        val area = page.filter { it.box.centerY() in top until bottom }

        // 页面标题/按钮/版权行/系统状态栏，绝不可能是身份昵称
        val junk = listOf(
            "申请使用", "和朋友关系", "使用不同", "创建", "同意", "切换账", "拒绝", "取消",
            "王者荣耀", "腾讯", "批准文号", "ISBN", "粤网文", "隐私", "协议", "登录", "头像",
        )
        fun bad(t: String) = t.isEmpty() || junk.any { t.contains(it) } || NOISE_RE.containsMatchIn(t)

        // 标题行「你的QQ头像、昵称和朋友关系」也含副标题字样，必须排除
        val subs = area.filter {
            val t = plainText(it.text)
            SUBTITLE_RE.containsMatchIn(t) && !t.contains("和朋友关系") && !t.contains("申请使用")
        }
        var original: IdentityRow? = null
        var originLine: Ocr.ScanLine? = null
        for (s in subs) {
            val inline = plainText(s.text).replace(SUBTITLE_RE, "")
                .trim('、', '·', '-', '|', '：', ':', ',', '，')
            if (inline.isNotEmpty() && !bad(inline)) {
                original = IdentityRow(inline, true, s.box)
                break
            }
            // 昵称在副标题正上方，水平方向与副标题重叠
            val maxGap = h * 0.06f
            val above = area.asSequence()
                .filter { it !== s }
                .filter { !bad(plainText(it.text)) && !SUBTITLE_RE.containsMatchIn(plainText(it.text)) }
                .filter { overlapX(it.box, s.box) }
                .filter { it.box.bottom <= s.box.top + 6 && s.box.top - it.box.bottom <= maxGap }
                .maxByOrNull { it.box.bottom }
            if (above != null) {
                original = IdentityRow(plainText(above.text), true, above.box)
                originLine = above
                break
            }
        }

        val rows = ArrayList<IdentityRow>()
        original?.let { rows += it }
        area.asSequence()
            .filter { it !== originLine }
            .filter { l ->
                val t = plainText(l.text)
                t.length <= 16 && !bad(t) && !SUBTITLE_RE.containsMatchIn(t) && l.box.height() >= 10
            }
            .sortedBy { it.box.top }
            .forEach { l ->
                val t = plainText(l.text)
                if (rows.none { it.name == t }) rows += IdentityRow(t, false, l.box)
            }
        return rows
    }

    /**
     * 行右侧是否有一个蓝色对勾（QQ 蓝 ≈ #12B7F5）。对勾是图形，OCR 读不到，只能数像素。
     * 只在昵称右侧到面板右边缘之间扫，避开头像；取不到就返回 false（不阻塞流程）。
     */
    private fun tickOnRow(bmp: android.graphics.Bitmap, row: IdentityRow, panelRight: Int): Boolean {
        val rh = row.box.height().coerceAtLeast(20)
        val left = (row.box.right + rh / 2).coerceIn(0, bmp.width - 1)
        val right = panelRight.coerceIn(left + 1, bmp.width)
        if (right - left < 12) return false
        val top = (row.box.top - rh / 2).coerceIn(0, bmp.height - 1)
        val bottom = (row.box.bottom + rh / 2).coerceIn(top + 1, bmp.height)
        var blue = 0
        var x = left
        while (x < right) {
            var y = top
            while (y < bottom) {
                val c = bmp.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (b > 140 && b - r > 40 && b - g > 10) blue++
                y += 2
            }
            x += 2
        }
        return blue >= 20
    }

    /**
     * 公告弹窗右上角的 ✕ 是白色图形，OCR 读不到。在标题行同一水平带、标题右侧扫白色像素簇，
     * 取簇重心作为点击点。找不到（或像素太少）返回 null，由调用方按比例兜底。
     */
    private fun findWhiteCluster(
        bmp: android.graphics.Bitmap,
        w: Int,
        h: Int,
        title: Rect,
    ): Pair<Int, Int>? {
        val cy = title.centerY()
        val half = (title.height() * 0.9f).coerceAtLeast(h * 0.02f)
        val top = (cy - half).toInt().coerceAtLeast(0)
        val bottom = (cy + half).toInt().coerceAtMost(h - 1)
        val left = (title.right + w * 0.01f).toInt().coerceIn(0, w - 1)
        val right = (w * 0.97f).toInt().coerceAtLeast(left + 1)
        var sx = 0L
        var sy = 0L
        var n = 0
        for (y in top..bottom) {
            for (x in left until right) {
                val c = bmp.getPixel(x, y)
                if (((c shr 16) and 0xFF) > 190 && ((c shr 8) and 0xFF) > 190 && (c and 0xFF) > 190) {
                    sx += x
                    sy += y
                    n++
                }
            }
        }
        if (n < 40) return null
        return (sx / n).toInt() to (sy / n).toInt()
    }

    private fun handleLobby(
        ctx: Context,
        acc: Account,
        bmp: android.graphics.Bitmap,
        w: Int,
        h: Int,
        lines: List<Ocr.ScanLine>,
        status: (String) -> Unit,
        done: (Account, String) -> Unit,
    ) {
        fun plain(l: Ocr.ScanLine) = l.text.replace(Regex("\\s+"), "")
        val serverLine = lines.firstOrNull { Regex(SERVER_RE).containsMatchIn(it.text) }

        AddAccountFlow.readNickname(ctx, bmp, lines, w, h) { rawName ->
            // 去掉悬浮窗/OCR 混入的引号和零散符号（昵称被悬浮窗遮挡时会拼出多余字符）
            val name = rawName.replace(Regex("[「」\"'`·,，。]"), "").trim()
            IO.log(ctx, TAG, "大厅昵称=[$rawName] 清洗后=[$name] 目标=[${acc.name}] 区服行=${serverLine?.text}")
            // 先看当前会话到底是微信还是 QQ：区服行前缀（微信xxx区 / 手Qxxx区）就是当前登录方式。
            // 目标 QQ 却登在微信区时，昵称再怎么「对不上」也说不清 —— 必须退出去点 QQ，
            // 否则会把微信的昵称和区服直接灌进 QQ 账号，等于凭空多出一个微信号。
            val serverText = serverLine?.text?.replace(Regex("\\s+"), "").orEmpty()
            val currentWx = serverText.contains("微信")
            val currentQq = serverText.contains("手Q") || serverText.contains("QQ")
            val wantWx = acc.loginType == LoginType.WECHAT
            val typeOk = when {
                currentWx -> wantWx
                currentQq -> !wantWx
                else -> true // 读不到区服前缀时退回只比昵称
            }
            // 账号名还空着（新建的 QQ 账号还没登录过）时只能靠登录方式判断，
            // 不能因为「目标名为空」就当成匹配 —— 那正是把微信会话写进 QQ 账号的入口。
            // 同理：新建账号遇到大厅是别的账号已占用的身份（如「OK」）时也不算匹配，
            // 否则会把上一个账号的身份当成自己登录成功（已有名字的账号不受影响）。
            val takenName = acc.name.isBlank() && excludeQq.contains(name.replace(WS, ""))
            val matched = typeOk && !takenName &&
                (name.isBlank() || acc.name.isBlank() || name == acc.name)
            // 昵称/登录方式对不上就退出重登 —— 不分 QQ/微信：微信账号也可能在 QQ 会话下被启动，
            // 必须退出当前会话再走「与微信好友玩」才算真正切到该账号
            val needSwitch = !matched && logoutAttempts < 3
            val targetLabel = acc.name.ifBlank { if (wantWx) "微信账号" else "QQ 账号" }

            if (needSwitch) {
                logoutAttempts++
                // 重置 QQ 清点状态：下次授权重新扫切号列表、重新选号，避免停在上一账号反复点同意
                // （已知 QQ 号 + 身份时保留目标，退出重登后直接选号，不再清点一遍用户名）
                resetQqFlow(keepTarget = qqMode == QqMode.TARGET)
                autoPickedText = ""
                val exit = Ui.line(lines, Ui.EXIT)
                // 平板 21:9 上下黑边会让比例坐标掉进黑边，而且 OCR 常把「昵称 退出」拼成一行，
                // 整行相等匹配不上：这种情况退出按钮就在这一行最右端
                val mergedExit = lines.firstOrNull {
                    val t = it.text.replace(WS, "")
                    t != "退出" && t.endsWith("退出")
                }
                when {
                    exit != null -> tapLine(ctx, exit, "退出「$name」")
                    mergedExit != null -> {
                        val d = mergedExit.box.height().coerceAtLeast(24)
                        val x = (mergedExit.box.right - d).coerceIn(0, w - 1)
                        val y = mergedExit.box.centerY()
                        IO.log(ctx, TAG, "点击 退出(合并行) ($x,$y) :: ${mergedExit.text}")
                        BotService.tap(x, y)
                    }
                    else -> {
                        IO.log(ctx, TAG, "退出按钮 OCR 未命中，按比例点击 ($EXIT_RX,$EXIT_RY)")
                        BotService.tap((w * EXIT_RX).toInt(), (h * EXIT_RY).toInt())
                    }
                }
                status(
                    if (!typeOk) "当前是${if (currentWx) "微信" else "QQ"}登录，退出后改用${if (wantWx) "微信" else "QQ"}登录…"
                    else "当前登录「$name」，退出后切换到「$targetLabel」…"
                )
                // 回到主循环：协议弹窗 → 登录页 → QQ 切号，都由已有分支处理
                pollAuto(ctx, acc, 0, status, done)
                return@readNickname
            }

            // 重试用尽仍登不成目标：当前大厅其实是别的身份/别的登录方式，
            // 它的区服绝不能写进这个账号 —— 保留原数据直接结束
            if (!matched) {
                finishAuto(
                    ctx, acc,
                    if (!typeOk) "当前是${if (currentWx) "微信" else "QQ"}登录，与目标的${if (wantWx) "微信" else "QQ"}登录不符，已保留原账号数据"
                    else "登录得到「$name」与目标「${acc.name}」不符，已保留原账号数据",
                    done
                )
                return@readNickname
            }

            // 走到这里必然 matched（不匹配的已在上面拦截）
            // 大厅昵称读不出来（平板上游戏是 21:9 上下黑边，右上角位置会落在识别区外）时，
            // 用刚登录的游戏身份名兜底：登录的哪个身份，进游戏就是哪个昵称
            val finalName = name.ifBlank { qqTargetIdent.ifBlank { acc.name } }
            val detected = when {
                serverLine?.text?.contains("微信") == true -> LoginType.WECHAT
                serverLine != null -> LoginType.QQ
                else -> acc.loginType
            }
            // 登录成功后学习映射：切号列表点中的 QQ 昵称就是目录里的 QQ 号，
            // 同时把该 QQ 号下清点到的全部身份存回账号（身份下拉框的数据源）
            val owner = qqTarget?.nick?.takeIf { it.isNotBlank() }
            val learnedNick = owner ?: if (
                autoPickedText.isNotBlank() && autoPickedText != finalName && autoPickedText != acc.qqNick
            ) autoPickedText else acc.qqNick
            val identities = qqCatalog[owner]?.takeIf { it.isNotEmpty() } ?: acc.identities
            val base = acc.copy(
                name = finalName,
                qqNick = learnedNick,
                // 切号时顺手抓到的 QQ 号，卡里还没有就补上
                qqNo = acc.qqNo.ifBlank { qqTarget?.no.orEmpty() },
                identities = identities,
                // 登录类型以大厅区服前缀为准（如账号标了 QQ 但登进了微信区）
                loginType = if (matched || acc.loginType == LoginType.WECHAT) detected else acc.loginType,
            )

            // 日常执行只负责登录：区服信息由「同步区服」按钮维护好，这里不再开换区面板抓一遍
            if (!autoSyncFarms) {
                finishAuto(ctx, base, "已登录「$finalName」", done)
                return@readNickname
            }

            // 不能把自己的悬浮提示「查找换区入口..」当成换区按钮：提示文字里也含「换区」，
            // 大厅首帧渲染慢时它会排在真实按钮前面，点上去等于什么也没点
            val change = lines.firstOrNull {
                Ui.matches(it.text, Ui.CHANGE_AREA) &&
                    !it.text.contains("…") && !it.text.contains("..") && !it.text.contains("查找")
            }
            if (change != null) {
                tapLine(ctx, change, "换区")
                status("登录成功：$finalName，正在同步区服…")
                pollFarms(ctx, base, 0, emptyList(), status, done)
            } else if (lobbyVisits < 6) {
                // 「换区」字号小，大厅首帧 OCR 常漏：多等几轮再找
                lobbyVisits++
                IO.log(ctx, TAG, "未找到换区入口，第 $lobbyVisits 次重试")
                status("查找换区入口…")
                pollAuto(ctx, base, 0, status, done)
            } else {
                finishAuto(ctx, base, "已登录「$finalName」（未找到换区入口，保留原有区服）", done)
            }
        }
    }

    /** 「换区」面板抓「我的服务器」：两轮数量一致才算同步完，然后点返回箭头回大厅 */
    private fun pollFarms(
        ctx: Context,
        acc: Account,
        attempt: Int,
        best: List<Farm>,
        status: (String) -> Unit,
        done: (Account, String) -> Unit,
    ) {
        if (!FlowControl.alive(autoToken)) {
            finishAuto(ctx, acc, "已强制停止", done)
            return
        }
        if (attempt >= 14) {
            val keep = if (best.isNotEmpty()) best else acc.farms
            // 超时也要把面板关掉：抓一帧拿屏幕尺寸后点返回
            BotService.capture { b ->
                if (b != null) tapBack(ctx, b.width, b.height, null, "同步超时")
            }
            finishAuto(ctx, acc.copy(farms = keep), "已登录「${acc.name}」· ${keep.size} 个区服", done)
            return
        }
        main.postDelayed({
            BotService.capture { bmp ->
                if (bmp == null) {
                    pollFarms(ctx, acc, attempt + 1, best, status, done)
                    return@capture
                }
                Ocr.recognize(bmp) { lines ->
                    val farms = Ocr.toFarms(lines).toMutableList()
                    AddAccountFlow.recoverAll(ctx, bmp, Ocr.orphans(lines), farms) { all ->
                        val merged = if (all.size > best.size) all else best
                        if (merged.isNotEmpty() && merged.size == best.size) {
                            IO.log(ctx, TAG, "区服同步完成：${merged.size} 个（连续两轮一致）")
                            tapBack(ctx, bmp.width, bmp.height, lines, "区服同步完成")
                            finishAuto(ctx, acc.copy(farms = merged), "已登录「${acc.name}」· ${merged.size} 个区服", done)
                        } else {
                            if (attempt == 3) {
                                // 面板可能没弹出来，再点一次「换区」
                                Ui.line(lines, Ui.CHANGE_AREA)?.let {
                                    tapLine(ctx, it, "换区(重试)")
                                }
                            }
                            pollFarms(ctx, acc, attempt + 1, merged, status, done)
                        }
                    }
                }
            }
        }, 1500)
    }

    /** 面板左上角返回箭头：手机/平板同一比例位置（AddAccountFlow 同款） */
    /**
     * 从换区面板回大厅。返回箭头是图片没有文字，只能点坐标：
     * x 用比例（面板宽度随屏幕等比缩放），y 跟着「选择服务器」标题走 ——
     * 平板上游戏是上下黑边的 21:9，整块 UI 下移，写死 0.067h 会点进黑边。
     * 游戏不吃系统返回键（实测 KEYCODE_BACK 无效），所以不能改成 back()。
     */
    private fun tapBack(
        ctx: Context,
        w: Int,
        h: Int,
        lines: List<Ocr.ScanLine>?,
        why: String,
    ) {
        val x = (w * BACK_RX).toInt()
        // 面板标题 OCR 常把「服务器」读错一个字（如「选择服脹务器」），只认前三个字
        val title = lines?.firstOrNull { it.text.contains("选择服") }
        // 标题也没读到时，用左侧「我的服务器 / 推荐服务器」第一行估算标题行（往上约 0.072H）；
        // 平板 21:9 上下黑边会把 0.067H 的兜底坐标打进黑边，点了等于没点
        val sidebar = lines
            ?.filter {
                (it.text.contains("我的服") || it.text.contains("推荐服")) &&
                    it.box.left < w * 0.4f
            }
            ?.minByOrNull { it.box.top }
        val y = when {
            title != null -> title.box.centerY()
            sidebar != null -> (sidebar.box.top - h * 0.072f).toInt().coerceAtLeast(0)
            else -> (h * BACK_RY).toInt()
        }
        IO.log(ctx, TAG, "点击 返回大厅 ($why) ($x,$y)")
        BotService.tap(x, y)
    }
}
