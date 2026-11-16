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
 * 单个账号「试登录」：把王者荣耀带到该账号登录方式对应的入口。
 *
 * 本期只做到「发起」这一步：
 *  - QQ / 微信：点「与QQ好友玩 / 与微信好友玩」，之后由 QQ/微信 自己弹授权，用户确认；
 *  - QQ/微信 扫码：点右上角二维码图标 → 选「扫码授权」，把二维码留在屏幕上，
 *    用户用另一台设备扫码；扫码成功后游戏会自己进大厅，后续自动化再迭代。
 *
 * 游戏是 Unity 自绘，无障碍节点树拿不到文字，只能靠截图 OCR 定位。
 */
object LoginFlow {

    private const val TAG = "Login"
    private const val MAX_POLLS = 40          // 每 1.2s 一轮，约 48s
    /** 身份列表最多向下翻几页 / 找目标时最多向上翻几页 */
    private const val IDENTITY_MAX_SCROLLS = 8
    private const val IDENTITY_MAX_UP_SCROLLS = 10
    private const val QR_ICON_X = 0.913f      // 登录页右上角二维码图标的相对位置（2400x1080 实测）
    private const val QR_ICON_Y = 0.20f

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false
    /** 试登录这一轮的取消令牌 */
    private var runToken = 0

    fun start(ctx: Context, acc: Account, done: (String) -> Unit) {
        if (running) {
            done("上一次试登录还没结束")
            return
        }
        if (FlowControl.running.value) {
            done("有自动化正在执行，请先点「停止」")
            return
        }
        if (!BotService.isReady) {
            done("${Perms.accessibilityReason(ctx)}，无法自动登录")
            return
        }
        running = true
        runToken = FlowControl.begin("试登录")
        IO.log(ctx, TAG, "试登录 账号=${acc.name.ifEmpty { acc.id }} 方式=${acc.loginType.name}")

        if (BotService.foregroundPackage() == GameLauncher.PACKAGE) {
            poll(ctx, acc, 0) { msg -> finish(ctx, msg, done) }
        } else {
            if (!GameLauncher.launch(ctx)) {
                finish(ctx, "无法启动王者荣耀", done)
                return
            }
            done("正在启动王者荣耀，稍后按「${acc.loginType.label}」登录…")
            waitGame(ctx, acc, 0) { msg -> finish(ctx, msg, done) }
        }
    }

    private fun finish(ctx: Context, msg: String, done: (String) -> Unit) {
        if (!running) return
        running = false
        FlowControl.end(runToken)
        IO.log(ctx, TAG, "结束：$msg")
        done(msg)
    }

    private fun waitGame(ctx: Context, acc: Account, attempt: Int, done: (String) -> Unit) {
        if (!FlowControl.alive(runToken)) {
            finish(ctx, "已强制停止", done)
            return
        }
        if (attempt > 40) {
            done("等不到游戏切到前台，请手动打开王者荣耀后再试")
            return
        }
        main.postDelayed({
            if (BotService.foregroundPackage() == GameLauncher.PACKAGE) {
                poll(ctx, acc, 0, done)
            } else {
                waitGame(ctx, acc, attempt + 1, done)
            }
        }, 1000)
    }

    private fun poll(ctx: Context, acc: Account, attempt: Int, done: (String) -> Unit) {
        if (!FlowControl.alive(runToken)) {
            finish(ctx, "已强制停止", done)
            return
        }
        if (attempt >= MAX_POLLS) {
            done("没有在游戏里找到登录入口，请手动登录")
            return
        }
        main.postDelayed({
            if (BotService.foregroundPackage() != GameLauncher.PACKAGE) {
                poll(ctx, acc, attempt + 1, done)
                return@postDelayed
            }
            BotService.capture { bmp ->
                if (bmp == null) {
                    poll(ctx, acc, attempt + 1, done)
                    return@capture
                }
                Ocr.recognize(bmp) { lines ->
                    onLines(ctx, acc, bmp.width, bmp.height, lines, attempt + 1, done)
                }
            }
        }, 1200)
    }

    private fun onLines(
        ctx: Context,
        acc: Account,
        w: Int,
        h: Int,
        lines: List<Ocr.ScanLine>,
        next: Int,
        done: (String) -> Unit,
    ) {
        fun hit(kw: String) = lines.firstOrNull {
            it.text.replace(Regex("\\s+"), "").contains(kw)
        }

        if (!FlowControl.alive(runToken)) {
            finish(ctx, "已强制停止", done)
            return
        }

        // 首次启动/协议更新会挡一个「同意」弹窗
        val agree = hit("同意")
        val hasAgreement = lines.any {
            it.text.contains("协议") || it.text.contains("许可") || it.text.contains("隐私")
        }
        if (agree != null && hasAgreement) {
            tapLine(ctx, agree, "同意协议")
            poll(ctx, acc, next, done)
            return
        }

        val qqLogin = hit("QQ好友玩")
        val wxLogin = hit("微信好友玩")
        val qrQQ = hit("QQ扫码授权")
        val qrWX = hit("微信扫码授权")
        val qrPage = hit("扫码授权登录") ?: hit("使用QQ手机版扫码") ?: hit("使用微信扫码")
        val lobby = hit("开始游戏") ?: hit("换区")

        // 已经登录进大厅了，不用再点登录
        if (lobby != null && qqLogin == null && wxLogin == null && qrPage == null) {
            done("当前已是登录状态，无需重复登录")
            return
        }

        when (acc.loginType) {
            LoginType.QQ -> if (qqLogin != null) {
                tapLine(ctx, qqLogin, "与QQ好友玩")
                done("已点击「与QQ好友玩」，请在 QQ 弹窗里确认授权")
                return
            }

            LoginType.WECHAT -> if (wxLogin != null) {
                tapLine(ctx, wxLogin, "与微信好友玩")
                done("已点击「与微信好友玩」，请在微信弹窗里确认授权")
                return
            }

            LoginType.QQ_QR -> when {
                qrPage != null -> {
                    done("二维码已显示，请用另一台手机的 QQ 扫码授权")
                    return
                }
                qrQQ != null -> {
                    tapLine(ctx, qrQQ, "QQ扫码授权")
                    poll(ctx, acc, next, done)
                    return
                }
                qqLogin != null || wxLogin != null -> {
                    BotService.tap((w * QR_ICON_X).toInt(), (h * QR_ICON_Y).toInt())
                    IO.log(ctx, TAG, "打开扫码菜单")
                    poll(ctx, acc, next, done)
                    return
                }
            }

            LoginType.WECHAT_QR -> when {
                qrPage != null -> {
                    done("二维码已显示，请用另一台手机的微信扫码授权")
                    return
                }
                qrWX != null -> {
                    tapLine(ctx, qrWX, "微信扫码授权")
                    poll(ctx, acc, next, done)
                    return
                }
                qqLogin != null || wxLogin != null -> {
                    BotService.tap((w * QR_ICON_X).toInt(), (h * QR_ICON_Y).toInt())
                    IO.log(ctx, TAG, "打开扫码菜单")
                    poll(ctx, acc, next, done)
                    return
                }
            }
        }

        poll(ctx, acc, next, done)
    }

    private fun tapLine(ctx: Context, line: Ocr.ScanLine, what: String) {
        val x = line.box.centerX()
        val y = line.box.centerY()
        IO.log(ctx, TAG, "点击 $what ($x,$y) :: ${line.text}")
        BotService.tap(x, y)
    }

    // ------------------------------------------------------------------
    // 已有账号时的全自动登录
    //   登录页 → 与QQ好友玩 → 系统「想要打开QQ」→ 允许
    //   → QQ 小窗：切换账号 → 选到账号昵称 → 登录
    //   → 游戏回到大厅
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
    private var autoSwitched = false
    private var autoPicked = false
    private var autoTappedAt = 0L
    /** 授权页一上是否已点过目标游戏身份行（大号/小号这类创建的身份） */
    private var autoIdentityPicked = false
    /** 切号列表里实际点中的那行文字（QQ 昵称），登录成功后用于学习 qqNick 映射 */
    private var autoPickedText = ""
    /** 身份区放大补识别连续失败的轮数；超过 3 轮放弃选身份，直接同意走自愈 */
    private var identityZoomFails = 0
    /** 对勾没落到目标身份行时重新点选的次数（最多 2 次，防止无限点） */
    private var identityRetaps = 0
    /** 授权页身份列表：已扫到的昵称（跨轮次累加，原始身份固定排第一） */
    private val identitySeen = LinkedHashSet<String>()
    /** 已确认扫到底（一屏只放得下 2 个身份，3~5 个必须向下翻） */
    private var identityScanDone = false
    /** 连续向下翻了却没有新昵称的轮数，达到 2 轮即认为到底 */
    private var identityIdleScrolls = 0
    /** 向下翻页次数（最多 IDENTITY_MAX_SCROLLS 轮） */
    private var identityScrolls = 0
    /** 找目标身份时向上翻回的次数 */
    private var identityUpScrolls = 0
    /** QQ 原始身份昵称（副标题「QQ头像昵称」那行） */
    private var identityOrigName = ""
    /** 上一帧可见的身份行（判断翻页有没有真的生效） */
    private var identityLastVisible: List<String> = emptyList()
    /** 上一轮发过翻页，等这一帧核对效果 */
    private var identityScrollPending = false
    /** 上一轮翻页方向：true=向上找目标 */
    private var identityScrollingUp = false
    /** 已确认回到列表顶部（向上翻页也没新内容） */
    private var identityUpDone = false

    /** 每次重新登录都从第一屏重新扫身份列表 */
    private fun resetIdentityScan() {
        identitySeen.clear()
        identityScanDone = false
        identityIdleScrolls = 0
        identityScrolls = 0
        identityUpScrolls = 0
        identityOrigName = ""
        identityLastVisible = emptyList()
        identityScrollPending = false
        identityScrollingUp = false
        identityUpDone = false
    }
    /** 已尝试「退出重登」的次数，防止昵称一直对不上时无限循环 */
    private var logoutAttempts = 0
    /** 大厅帧里没找到「换区」入口时的重试次数（换区字号小，OCR 首帧常漏） */
    private var lobbyVisits = 0
    /** 已被其他 QQ 账号占用的 QQ 昵称：切号列表里要跳过它们 */
    private var excludeQq: Set<String> = emptySet()

    fun startAuto(
        ctx: Context,
        acc: Account,
        status: (String) -> Unit,
        done: (Account, String) -> Unit,
        excludeQq: Set<String> = emptySet(),
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
            done(acc, "扫码账号无法自动登录，请用「试登录」")
            return
        }
        this.excludeQq = excludeQq.map { it.replace(WS, "") }.filter { it.isNotBlank() }.toSet()
        if (this.excludeQq.isNotEmpty()) IO.log(ctx, TAG, "切号时排除 QQ 账号=$excludeQq")
        autoRunning = true
        autoToken = FlowControl.begin("自动登录")
        autoSwitched = false
        autoPicked = false
        autoIdentityPicked = false
        autoPickedText = ""
        identityZoomFails = 0
        identityRetaps = 0
        resetIdentityScan()
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
        fun hit(kw: String) = lines.firstOrNull {
            it.text.replace(Regex("\\s+"), "").contains(kw)
        }
        fun short(t: String) = lines.firstOrNull {
            it.text.replace(Regex("\\s+"), "") == t
        }
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
        if (hit("想要打开") != null || hit("启动应用") != null || hit("是否允许") != null) {
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

        // 1.5) 版本更新公告等冷启动弹窗：只有右上角一个 ✕ 图钉按钮（OCR 读不到），
        //      实测（平板 2880x1800）✕ 位于屏幕比例 (0.854, 0.173)，先按比例点掉
        if (fg == GameLauncher.PACKAGE &&
            (hit("更新公告") != null || hit("亲爱的召唤师") != null) && short("同意") == null
        ) {
            if (System.currentTimeMillis() - autoTappedAt > 3000) {
                autoTappedAt = System.currentTimeMillis()
                BotService.tap((w * 0.854f).toInt(), (h * 0.173f).toInt())
                IO.log(ctx, TAG, "关闭公告弹窗 (0.854,0.173)")
                status("关闭公告弹窗…")
            }
            retry()
            return
        }

        // 2) 游戏协议弹窗（只在游戏界面，避免误点 QQ/微信 授权页的「同意」）
        val agreeShort = short("同意") ?: short("我同意")
        if (fg == GameLauncher.PACKAGE && agreeShort != null &&
            (hit("拒绝") != null || hit("许可及服务协议") != null || hit("隐私政策") != null)
        ) {
            tapLine(ctx, agreeShort, "同意协议")
            retry()
            return
        }

        // 3) 游戏登录页（必须限定游戏前台：QQ 小窗模式下小窗后面的登录页也会被 OCR 读到，
        //    不加限定会在这里空转、永远轮不到下面的 QQ 弹窗分支）
        val qqLogin = hit("QQ好友玩")
        val wxLogin = hit("微信好友玩")
        if (fg == GameLauncher.PACKAGE && (qqLogin != null || wxLogin != null)) {
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

        // 4) QQ 授权小窗（MIUI 自由窗口）。两层选择：
        //      a. 切号列表「选择要登录的账号」：行显示 QQ 昵称+QQ号（QQ昵称≠游戏昵称，
        //         如「主号」的 QQ 号下建了游戏身份「小号」）→ 按 qqNick 优先匹配账号行；
        //      b. 授权页一「王者荣耀 申请使用 你的QQ头像、昵称和朋友关系」：
        //         列出该 QQ 账号下的游戏登录身份（如 主号 / 小号）→ 点目标身份行选中；
        //      c. 同意（第一次：同意登录账号）；
        //      d. 授权页二「QQ 申请使用 你在王者荣耀的以下信息」→ 同意（第二次：读取社交信息）；
        //      e. 回游戏大厅。
        if (fg == QQ_PKG) {
            // QQ 授权小窗是标准原生控件（文本/边界都能精确读到）→ 优先走控件树。
            // 比 OCR 强：整帧 OCR 常漏掉小窗里的身份行（如「小号」「大号」），
            // 且 performAction 点击不受 MIUI 后台手势注入限制。
            val winText = Nodes.allWindowsText()
            val cool = System.currentTimeMillis() - autoTappedAt

            if (winText.isNotBlank()) {
                // 0) 授权页一「你可以使用不同的头像/昵称登录」：整页身份列表
                //    抓全量昵称回写账号 → 点目标身份行 → 对勾核验 → 同意
                if (winText.contains("使用不同") || winText.contains("和朋友关系") ||
                    winText.contains("QQ头像昵称")
                ) {
                    val nodeLines = Nodes.allWindowsLines().map { Ocr.ScanLine(it.first, it.second) }
                    val step = handleIdentityPage(ctx, acc, bmp, w, h, nodeLines, status) { row ->
                        if (Nodes.clickByTextAllWindows(listOf(row.name)) == null) {
                            BotService.tap(row.box.centerX(), row.box.centerY())
                        }
                    }
                    if (step.page) {
                        retry(step.acc)
                        return
                    }
                }

                // a) 切号列表页「选择要登录的账号」
                if (winText.contains("选择要登录的账号") || winText.contains("添加账号")) {
                    val key = acc.qqNick.ifBlank { acc.name }
                    if (cool > 1500) {
                        var hitText: String? = null
                        if (key.isNotBlank()) hitText = Nodes.clickByTextAllWindows(listOf(key))
                        if (hitText == null) {
                            // 新账号不知道 QQ 昵称 / 昵称对不上时：跳过已被其他账号占用的 QQ 号，
                            // 在切号列表里选第一个没用过的（只认列表标题下方、弹窗宽度内的行）
                            val all = Nodes.allWindowsLines()
                            val title = all.firstOrNull {
                                it.first.replace(WS, "").contains("选择要登录")
                            }?.second
                            val row = all
                                .filter { (t, r) ->
                                    isPickerRow(t) && !usedQq(t) &&
                                        r.centerY() > (title?.bottom ?: (h * 0.2f).toInt()) &&
                                        r.centerX() in (w * 0.1f).toInt()..(w * 0.9f).toInt()
                                }
                                .minByOrNull { it.second.top }
                            if (row != null) {
                                hitText = Nodes.clickByTextAllWindows(listOf(row.first))
                                    ?: run {
                                        BotService.tap(row.second.centerX(), row.second.centerY())
                                        row.first
                                    }
                            }
                            IO.log(ctx, TAG, "切号列表按排除集选行=${row?.first} 排除=$excludeQq")
                            if (row == null) status("没有可用的 QQ 账号，请先在 QQ 里添加新账号")
                        }
                        if (hitText != null) {
                            autoTappedAt = System.currentTimeMillis()
                            autoPickedText = hitText.replace(WS, "")
                            autoSwitched = true
                            autoPicked = true
                            IO.log(ctx, TAG, "节点点击 QQ账号「${hitText.trim()}」")
                            status("已选择 QQ 账号「${hitText.trim()}」…")
                        }
                    }
                    retry(); return
                }

                // b) 授权页：当前 QQ 账号的身份列表里有目标游戏昵称 → 先选中它
                if (!autoIdentityPicked && acc.name.isNotBlank() && cool > 1200 &&
                    winText.contains(acc.name)
                ) {
                    val hitText = Nodes.clickByTextAllWindows(listOf(acc.name))
                    if (hitText != null) {
                        autoTappedAt = System.currentTimeMillis()
                        autoIdentityPicked = true
                        IO.log(ctx, TAG, "节点点击 身份「${hitText.trim()}」")
                        status("已选择身份「${acc.name}」…")
                        retry(); return
                    }
                }

                // c) 目标身份不在当前 QQ 账号下 → 切换账号
                if (!autoPicked && acc.name.isNotBlank() && cool > 1500) {
                    val hitText = Nodes.clickByTextAllWindows(listOf("切换账号", "切换帐号"))
                    if (hitText != null) {
                        autoTappedAt = System.currentTimeMillis()
                        IO.log(ctx, TAG, "节点点击 切换账号")
                        status("切换到账号「${acc.name}」…")
                        retry(); return
                    }
                }

                // d)+e) 授权页一/二都只有「同意」；2.5s 冷却防页面切换动画期间连点
                if (cool > 2500) {
                    val hitText = Nodes.clickByTextAllWindows(listOf("同意"))
                    if (hitText != null) {
                        autoTappedAt = System.currentTimeMillis()
                        IO.log(ctx, TAG, "节点点击 同意QQ授权")
                        status("已同意 QQ 授权，等待下一次确认…")
                        retry(); return
                    }
                }
                retry(); return
            }

            // ---- 控件树拿不到（权限/窗口不可见）时的 OCR 兜底 ----
            fun inBounds(l: Ocr.ScanLine) =
                l.box.left >= 0 && l.box.top >= 0 && l.box.right <= w && l.box.bottom <= h

            // 悬浮窗提示（正在登录「xx」…/切换到账号「xx」…）会飘在 QQ 小窗上被 OCR 读到，
            // 这些行绝不可能是真实按钮/列表行，全部排除
            fun isOverlayJunk(t: String) =
                t.contains("切换") || t.contains("登录") || t.contains("同步") ||
                    t.contains("…") || t.contains("..") || t.contains("「")

            // 切号列表页：只看标题/「添加账号」按钮。
            // 不能用「行内有没有 9 位以上数字」判断 —— 授权页底部的版权行
            // 「批准文号...ISBN...粤网文20176138」里就有长数字，会误判成列表页
            val pickerPage = hit("选择要登录的账号") != null || hit("添加账号") != null

            if (pickerPage) {
                // a) 按 QQ 昵称优先、其次游戏昵称匹配账号行
                val key = if (acc.qqNick.isNotBlank()) acc.qqNick else acc.name
                val target = lines.asSequence()
                    .filter { key.isNotBlank() && it.text.replace(Regex("\\s+"), "").contains(key) }
                    .filter { inBounds(it) && !isOverlayJunk(it.text.replace(Regex("\\s+"), "")) }
                    .filter { !it.text.contains("添加") && !it.text.contains("取消") }
                    .maxByOrNull { it.box.centerY() }
                // b) 新账号还不知道 QQ 昵称：跳过已被其他账号占用的号，选列表里第一个没用过的
                val fallback = if (target == null && key.isBlank() && excludeQq.isNotEmpty()) {
                    val title = lines.firstOrNull {
                        it.text.replace(Regex("\\s+"), "").contains("选择要登录")
                    }
                    lines.asSequence()
                        .filter { isPickerRow(it.text) && !usedQq(it.text) && inBounds(it) }
                        .filter { l ->
                            (title == null || l.box.centerY() > title.box.bottom) &&
                                l.box.centerX() in (w * 0.1f).toInt()..(w * 0.9f).toInt()
                        }
                        .minByOrNull { it.box.top }
                } else null
                val picked = target ?: fallback
                if (picked != null && System.currentTimeMillis() - autoTappedAt > 1200) {
                    autoTappedAt = System.currentTimeMillis()
                    autoPickedText = picked.text.replace(Regex("\\s+"), "")
                    tapLine(
                        ctx, picked,
                        if (target != null) "选择QQ账号 ${acc.qqNick.ifBlank { acc.name }}"
                        else "选择下一个 QQ账号（跳过 $excludeQq）"
                    )
                    autoSwitched = true
                    autoPicked = true
                    status("已选择 QQ 账号「${picked.text.trim()}」，等待授权页…")
                    retry()
                    return
                }
                retry()
                return
            }

            val switchBtn = short("切换账号") ?: short("切换帐号")
            val reject = short("拒绝")
            val agree = short("同意") ?: short("我同意")

            // b) 授权页一「你可以使用不同的头像/昵称登录」：整页身份列表
            //    （抓全量昵称、点目标行、核验对勾），识别不到列表再走下面的老逻辑
            val step = handleIdentityPage(ctx, acc, bmp, w, h, lines, status) { row ->
                tapLine(ctx, Ocr.ScanLine(row.name, row.box), "选择身份 ${row.name}")
            }
            if (step.page) {
                retry(step.acc)
                return
            }

            // c) 授权页一（有「切换账号」无「拒绝」）：先点目标游戏身份行选中
            if (switchBtn != null && reject == null && !autoIdentityPicked && acc.name.isNotBlank()) {
                fun identityTextOk(p: String) = p == acc.name || (
                    p.contains(acc.name) && p != "同意" && p != "取消" && p != "创建" &&
                        !isOverlayJunk(p) && !p.contains("申请") && !p.contains("头像") &&
                        !p.contains("添加")
                    )
                val identity = lines.asSequence()
                    .filter { identityTextOk(it.text.replace(Regex("\\s+"), "")) }
                    .filter { inBounds(it) }
                    .firstOrNull()
                if (identity != null && System.currentTimeMillis() - autoTappedAt > 1200) {
                    autoTappedAt = System.currentTimeMillis()
                    autoIdentityPicked = true
                    tapLine(ctx, identity, "选择身份 ${acc.name}")
                    status("已选择身份「${acc.name}」…")
                    retry()
                    return
                }
                if (identity == null) {
                    // 整帧 OCR 常漏掉小窗里的身份行（如「小号」）：把提示行与「同意」
                    // 按钮之间的身份列表区域裁出来放大补识别
                    zoomFindIdentity(ctx, bmp, w, h, lines, acc) { pt ->
                        if (pt != null && System.currentTimeMillis() - autoTappedAt > 1200) {
                            autoTappedAt = System.currentTimeMillis()
                            autoIdentityPicked = true
                            identityZoomFails = 0
                            IO.log(ctx, TAG, "点击 身份(补识别) ${acc.name} (${pt.first},${pt.second})")
                            BotService.tap(pt.first, pt.second)
                            status("已选择身份「${acc.name}」…")
                        } else if (pt == null) {
                            identityZoomFails++
                            if (identityZoomFails >= 3) {
                                IO.log(ctx, TAG, "身份行补识别连续 $identityZoomFails 轮未命中，放弃选身份直接同意")
                                autoIdentityPicked = true
                            }
                        }
                        retry()
                    }
                    return
                }
            }

            // 授权页一带「切换账号」且身份行没匹配到 → 目标身份可能属于另一个 QQ 账号，
            // 先切换账号（列表页由上面的 pickerPage 分支处理）
            if (switchBtn != null && !autoPicked && acc.name.isNotBlank()) {
                if (System.currentTimeMillis() - autoTappedAt > 1500) {
                    autoTappedAt = System.currentTimeMillis()
                    tapLine(ctx, switchBtn, "切换账号")
                    status("切换到账号「${acc.name}」…")
                }
                retry()
                return
            }

            // c)+d) 授权页一/二的按钮都是「同意」，直接点；
            //       靠 2.5s 冷却防止页面切换动画期间连点两次。
            if (agree != null && inBounds(agree) && System.currentTimeMillis() - autoTappedAt > 2500) {
                autoTappedAt = System.currentTimeMillis()
                tapLine(ctx, agree, "同意QQ授权")
                status("已同意 QQ 授权，等待下一次确认…")
                retry()
                return
            }
            retry()
            return
        }

        // 5) 微信授权页：确认/允许
        if (fg == WX_PKG) {
            val allow = short("允许") ?: short("确认登录") ?: short("登录")
            if (allow != null && System.currentTimeMillis() - autoTappedAt > 2000) {
                autoTappedAt = System.currentTimeMillis()
                tapLine(ctx, allow, "微信授权")
                status("已确认微信授权…")
            }
            retry()
            return
        }

        // 6) 已回到游戏大厅：读昵称 → 必要时退出切换账号 → 同步区服 → 回写账号
        val lobby = hit("开始游戏") ?: hit("换区")
        if (fg == GameLauncher.PACKAGE && lobby != null) {
            handleLobby(ctx, acc, bmp, w, h, lines, status, done)
            return
        }

        retry()
    }

    /**
     * 大厅收尾：
     *  - 从右上角读真实昵称（微信/新账号第一次同步时回写 name，修掉「平板微信」这类占位名）；
     *  - **先比登录方式，再比昵称**：大厅区服行前缀（微信xxx区 / 手Qxxx区）体现当前会话类型，
     *    目标 QQ 却停在微信大厅时一律「退出」重登（微信客户端只能同时登一个号，
     *    绝不能把微信的昵称和区服直接灌进 QQ 账号）；
     *  - 账号名还空着的新账号只按登录方式判断，不因为「目标名为空」就当成匹配；
     *  - 点「换区」同步「我的服务器」全部区服，连同昵称一起回给调用方持久化。
     */
    /**
     * 授权页一身份列表的局部放大补识别：MIUI 自由小窗里身份行字号小，整帧 OCR
     * 常漏掉目标行。裁「你可以使用不同的头像/昵称登录」提示行与「同意」按钮之间
     * 的区域放大 3 倍再识别，命中则返回屏幕坐标。
     */
    private fun zoomFindIdentity(
        ctx: Context,
        bmp: android.graphics.Bitmap,
        w: Int,
        h: Int,
        lines: List<Ocr.ScanLine>,
        acc: Account,
        cb: (Pair<Int, Int>?) -> Unit,
    ) {
        fun plain(l: Ocr.ScanLine) = l.text.replace(Regex("\\s+"), "")
        val hint = lines.firstOrNull { plain(it).contains("使用不同") }
            ?: lines.firstOrNull { plain(it).contains("不同的头像") }
            ?: lines.firstOrNull { plain(it) == "创建" }
        val agree = lines.firstOrNull { plain(it) == "同意" }
        if (hint == null || agree == null || agree.box.top <= hint.box.bottom) {
            cb(null); return
        }
        val left = (hint.box.left - 40).coerceAtLeast(0)
        val top = (hint.box.bottom + 8).coerceIn(0, h - 1)
        val bottom = (agree.box.top - 8).coerceAtLeast(top + 30).coerceAtMost(h)
        val crop = runCatching {
            android.graphics.Bitmap.createBitmap(bmp, left, top, w - left, bottom - top)
        }.getOrNull()
        if (crop == null) { cb(null); return }
        val scale = 3f
        Ocr.recognize(crop, scale) { cropLines ->
            crop.recycle()
            IO.log(ctx, TAG, "身份区补识别=${cropLines.map { it.text }}")
            val target = cropLines.asSequence()
                .filter { it.text.replace(Regex("\\s+"), "").contains(acc.name) }
                .firstOrNull()
            if (target == null) { cb(null); return@recognize }
            val sx = (target.box.centerX() / scale + left).toInt()
            val sy = (target.box.centerY() / scale + top).toInt()
            cb(sx to sy)
        }
    }

    // ------------------------------------------------------------------
    // QQ 授权页一「你可以使用不同的头像/昵称登录」：平铺的身份列表
    //   第 1 行是 QQ 原始身份（下方带「QQ头像昵称」副标题），后面是「创建」的分身，
    //   当前被提交的那行右侧有一个蓝色对勾。老逻辑只 OCR 默认显示的分身当账号名，
    //   所以这里要把整页列表抓全并写回账号（identities + qqNick）。
    // ------------------------------------------------------------------

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

    private data class IdentityStep(
        /** 是否停在授权页一（true 时调用方必须 retry 并跳过后续分支） */
        val page: Boolean,
        /** 可能已回写 identities / qqNick 的账号 */
        val acc: Account,
    )

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
    /** 切号列表页的账号行：排除标题/按钮/悬浮窗提示，避免把「添加账号」之类当号点掉 */
    private fun isPickerRow(raw: String): Boolean {
        val s = raw.replace(WS, "")
        if (s.isEmpty() || s.length > 24) return false
        val junk = listOf(
            "选择要登录", "添加账号", "切换账号", "切换帐号", "取消", "返回", "关闭",
            "王者荣耀", "同意", "拒绝", "申请使用", "登录", "「", "…",
        )
        return junk.none { s.contains(it) } && !NOISE_RE.containsMatchIn(s)
    }

    /** 这一行是不是已被其他账号用掉的 QQ 号 */
    private fun usedQq(raw: String): Boolean {
        val s = raw.replace(WS, "")
        return excludeQq.any { e -> s.contains(e) }
    }

    private fun parseIdentityRows(lines: List<Ocr.ScanLine>, w: Int, h: Int): List<IdentityRow> {
        fun inBounds(l: Ocr.ScanLine) =
            l.box.left >= 0 && l.box.top >= 0 && l.box.right <= w && l.box.bottom <= h
        val page = lines.filter { inBounds(it) }
        // 「选择要登录的账号」是切 QQ 号那一页，里面的行是 QQ 账号不是游戏身份
        if (page.any { plainText(it.text).contains("选择要登录") }) return emptyList()
        // 列表上下边界只能靠提示行和「同意」定；提示行没读到时宁可这一帧不解析，
        // 否则会把状态栏（时间/日期/电量）当成身份昵称写进账号
        val hint = page.firstOrNull { plainText(it.text).contains("使用不同") } ?: return emptyList()
        val agree = page.filter { plainText(it.text) == "同意" }.maxByOrNull { it.box.top }
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
     * 授权页一的完整处理（一轮只做一件事，做完由调用方 retry）：
     *  1. 抓全量身份列表写回 `acc`（identities + 原始身份写 qqNick）；
     *  2. 没选过 → 点目标身份行（`acc.name`；为空则点原始身份）；
     *  3. 选过 → 对勾还停在别的行就再点一次（最多 2 次）；
     *  4. 对勾落位或无法判断 → 点「同意」。
     */
    private fun handleIdentityPage(
        ctx: Context,
        acc: Account,
        bmp: android.graphics.Bitmap,
        w: Int,
        h: Int,
        lines: List<Ocr.ScanLine>,
        status: (String) -> Unit,
        clickRow: (IdentityRow) -> Unit,
    ): IdentityStep {
        // 切 QQ 号那一页（「选择要登录的账号」）交给老逻辑按 QQ 昵称点，不能当身份列表解析
        if (lines.any { plainText(it.text).contains("选择要登录") }) return IdentityStep(false, acc)
        val page = lines.any {
            val t = plainText(it.text)
            t.contains("使用不同") || t.contains("和朋友关系")
        } || lines.any { SUBTITLE_RE.containsMatchIn(plainText(it.text)) }
        if (!page) return IdentityStep(false, acc)

        val rows = parseIdentityRows(lines, w, h)
        if (rows.isEmpty()) {
            // 页面已确认但这一帧没读出行（加载/滚动动画）：等下一帧。
            // 不能掉回旧逻辑 —— 那里会因为没匹配到身份直接点「切换账号」，把授权页踢掉
            return IdentityStep(true, acc)
        }

        val agree = lines.firstOrNull { plainText(it.text) == "同意" }
            ?: lines.firstOrNull { plainText(it.text) == "我同意" }
        val switch = lines.firstOrNull { plainText(it.text) == "切换账号" }
            ?: lines.firstOrNull { plainText(it.text) == "切换帐号" }
        val hint = lines.firstOrNull { plainText(it.text).contains("使用不同") }
        val listTop = (hint?.box?.bottom ?: (h * 0.18f).toInt()).coerceIn(0, h - 1)
        val listBottom = (agree?.box?.top ?: (h * 0.78f).toInt()).coerceIn(listTop + 1, h)
        val panelRight = agree?.box?.right ?: (w * 0.92f).toInt()
        val tick = rows.firstOrNull { tickOnRow(bmp, it, panelRight) }
        val cool = System.currentTimeMillis() - autoTappedAt

        // 授权页可能还是上一个 QQ 号（QQ 默认沿用上次登录的账号）。
        // 判定：原始身份名是别的账号已占用的 QQ 昵称，且本账号的游戏昵称/QQ昵称都不在列表里
        // → 不抓身份、不写回，直接点「切换账号」换到下一个号，避免把别人的身份写进这个账号。
        val orig = rows.firstOrNull { it.original }?.name
        val mineShown = (acc.name.isNotBlank() && rows.any { it.name == acc.name }) ||
            (acc.qqNick.isNotBlank() && orig != null &&
                acc.qqNick.replace(WS, "") == orig.replace(WS, ""))
        // 整页身份里只要有一行属于别的账号（包括该 QQ 号创建的分身，如「OK」），
        // 就认定当前 QQ 号已被占用 —— 只比原始身份不够，副标题 OCR 漏读时会漏判
        val takenRows = rows.map { it.name.replace(WS, "") }.filter { excludeQq.contains(it) }
        val wrongQq = (orig != null && excludeQq.contains(orig.replace(WS, ""))) || takenRows.isNotEmpty()
        if (wrongQq && !mineShown && !autoSwitched) {
            if (switch != null && cool > 900) {
                autoTappedAt = System.currentTimeMillis()
                tapLine(ctx, switch, "切换账号（当前 QQ「${orig ?: takenRows.first()}」已被占用）")
                status("切换到另一个 QQ 账号…")
            }
            return IdentityStep(true, acc)
        }

        // 累加本帧看到的身份；原始身份只认带副标题的那行，固定排第一
        rows.firstOrNull { it.original }?.let {
            if (identityOrigName.isBlank()) identityOrigName = it.name
        }
        val before = identitySeen.size
        rows.forEach { identitySeen.add(it.name) }
        // 上一轮翻页核对：可见行没变 = 已经到顶/到底（QQ 授权弹窗不能用手势，翻不动就是到头了）
        val visibleNow = rows.map { it.name }
        if (identityScrollPending) {
            identityScrollPending = false
            if (visibleNow.toSet() == identityLastVisible.toSet() && identitySeen.size == before &&
                identityScrollingUp
            ) {
                identityUpDone = true
                IO.log(ctx, TAG, "已回到身份列表顶部")
            }
        }
        identityLastVisible = visibleNow
        val allNames = ArrayList<String>(identitySeen.size + 1)
        if (identityOrigName.isNotBlank()) allNames += identityOrigName
        identitySeen.filterTo(allNames) { it != identityOrigName }
        val updated = acc.copy(
            identities = allNames,
            qqNick = identityOrigName.ifBlank { acc.qqNick },
        )
        if (updated.identities != acc.identities || updated.qqNick != acc.qqNick) {
            IO.log(ctx, TAG, "授权页身份列表=${allNames} 目标=${acc.name} 当前=${tick?.name}")
        }
        // 向下翻页却没出现新昵称 → 到底了
        if (identityScrolls > 0 && !identityScrollingUp && identitySeen.size == before) identityIdleScrolls++ else identityIdleScrolls = 0

        // 1) 先向下翻，把整张列表扫完（一屏只放得下 2 个身份，3~5 个必须翻页）
        if (!identityScanDone) {
            if (identityIdleScrolls >= 2 || identityScrolls >= IDENTITY_MAX_SCROLLS) {
                identityScanDone = true
                IO.log(ctx, TAG, "身份列表已扫到底，共 ${allNames.size} 个：$allNames")
            } else if (cool > 900) {
                autoTappedAt = System.currentTimeMillis()
                identityScrolls++
                swipeList(ctx, listTop, listBottom, revealMore = true)
                status("正在查看全部登录身份…（已发现 ${allNames.size} 个）")
                return IdentityStep(true, updated)
            } else {
                return IdentityStep(true, updated)
            }
        }

        // 2) 找目标身份行；目标在上面几屏时往上翻回去
        var target = rows.firstOrNull {
            if (acc.name.isNotBlank()) it.name == acc.name
            else it.original || (identityOrigName.isNotBlank() && it.name == identityOrigName)
        }
        // 原始身份的副标题 OCR 没读出来时，列表最上面那行就是原始身份（列表已回顶）
        if (target == null && acc.name.isBlank() && identityUpDone) target = rows.firstOrNull()
        if (target == null) {
            val maybeAbove = acc.name.isBlank() || identitySeen.contains(acc.name)
            if (maybeAbove && !identityUpDone && cool > 900) {
                if (identityUpScrolls < IDENTITY_MAX_UP_SCROLLS) {
                    identityUpScrolls++
                    autoTappedAt = System.currentTimeMillis()
                    swipeList(ctx, listTop, listBottom, revealMore = false)
                    status("回到列表上方找「${acc.name.ifBlank { identityOrigName }}」…")
                } else {
                    identityUpDone = true
                }
                return IdentityStep(true, updated)
            }
            // 整张列表都翻过了确实没有目标身份：可能属于另一个 QQ 账号 → 切号
            if (!maybeAbove && identityUpDone && switch != null && cool > 1500) {
                autoTappedAt = System.currentTimeMillis()
                tapLine(ctx, switch, "切换账号")
                status("切换到账号「${acc.name}」…")
            }
            return IdentityStep(true, updated)
        }

        if (!autoIdentityPicked) {
            if (tick?.name == target.name) {
                autoIdentityPicked = true
                identityRetaps = 0
                IO.log(ctx, TAG, "身份「${target.name}」已是当前项，无需重选")
                return IdentityStep(true, updated)
            }
            if (cool > 1200) {
                autoTappedAt = System.currentTimeMillis()
                autoIdentityPicked = true
                identityRetaps = 0
                clickRow(target)
                status("已选择身份「${target.name}」…")
            }
            return IdentityStep(true, updated)
        }

        if (identityRetaps < 2 && tick != null && tick.name != target.name && cool > 1500) {
            identityRetaps++
            autoTappedAt = System.currentTimeMillis()
            clickRow(target)
            IO.log(ctx, TAG, "对勾在「${tick.name}」而非目标「${target.name}」，重新点选（第 $identityRetaps 次）")
            return IdentityStep(true, updated)
        }

        if (agree != null && cool > 2500) {
            autoTappedAt = System.currentTimeMillis()
            tapLine(ctx, agree, "同意QQ授权")
            status("已同意 QQ 授权（身份「${target.name}」）…")
        }
        return IdentityStep(true, updated)
    }

    /**
     * 翻动身份列表。只用无障碍 ACTION_SCROLL —— QQ 授权弹窗里后台手势会被当成拖拽，
     * 直接把授权弹窗拖掉（实测日志「您取消了QQ授权」）。
     * 翻不动就当作已经到顶/到底，由调用方按「没有新昵称」结束扫描。
     * @param revealMore true=看下面更多身份；false=翻回上面
     */
    private fun swipeList(ctx: Context, top: Int, bottom: Int, revealMore: Boolean) {
        identityScrollingUp = !revealMore
        identityScrollPending = true
        val moved = Nodes.scrollAllWindows(revealMore, top, bottom)
        IO.log(
            ctx, TAG,
            "身份列表${if (revealMore) "下翻" else "上翻"}（无障碍滚动${if (moved) "" else "，没有可滚动节点"}）"
        )
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
                // 重置 QQ 窗口的选择状态：下次授权重新切 QQ 账号、重新选身份。
                // 不重置的话会一直停在上一账号的授权页反复点同意，登回同一个身份
                autoPicked = false
                autoIdentityPicked = false
                autoPickedText = ""
                identityZoomFails = 0
                identityRetaps = 0
                resetIdentityScan()
                val exit = lines.firstOrNull { plain(it) == "退出" }
                if (exit != null) tapLine(ctx, exit, "退出「$name」")
                else {
                    IO.log(ctx, TAG, "退出按钮 OCR 未命中，按比例点击 ($EXIT_RX,$EXIT_RY)")
                    BotService.tap((w * EXIT_RX).toInt(), (h * EXIT_RY).toInt())
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
            val finalName = name.ifBlank { acc.name }
            val detected = when {
                serverLine?.text?.contains("微信") == true -> LoginType.WECHAT
                serverLine != null -> LoginType.QQ
                else -> acc.loginType
            }
            // 登录成功后学习映射：切号列表点中的 QQ 昵称 ≠ 游戏昵称时回写 qqNick，
            // 下次切号列表就能按 QQ 昵称选对 QQ 账号
            val learnedNick =
                if (autoPickedText.isNotBlank() && autoPickedText != finalName && autoPickedText != acc.qqNick)
                    autoPickedText else acc.qqNick
            val base = acc.copy(
                name = finalName,
                qqNick = learnedNick,
                // 登录类型以大厅区服前缀为准（如账号标了 QQ 但登进了微信区）
                loginType = if (matched || acc.loginType == LoginType.WECHAT) detected else acc.loginType,
            )

            val change = lines.firstOrNull { plain(it).contains("换区") }
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
                if (b != null) tapBack(ctx, b.width, b.height, "同步超时")
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
                            tapBack(ctx, bmp.width, bmp.height, "区服同步完成")
                            finishAuto(ctx, acc.copy(farms = merged), "已登录「${acc.name}」· ${merged.size} 个区服", done)
                        } else {
                            if (attempt == 3) {
                                // 面板可能没弹出来，再点一次「换区」
                                lines.firstOrNull { it.text.replace(Regex("\\s+"), "").contains("换区") }?.let {
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
    private fun tapBack(ctx: Context, w: Int, h: Int, why: String) {
        val x = (w * BACK_RX).toInt()
        val y = (h * BACK_RY).toInt()
        IO.log(ctx, TAG, "点击 返回大厅 ($why) ($x,$y)")
        BotService.tap(x, y)
    }
}
