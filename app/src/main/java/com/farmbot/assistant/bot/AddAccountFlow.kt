package com.farmbot.assistant.bot

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.farmbot.assistant.core.IO
import com.farmbot.assistant.data.Account
import com.farmbot.assistant.data.LoginType
import com.farmbot.assistant.ocr.Ocr
import com.farmbot.assistant.perm.Perms

/**
 * 一个账号都没有时的「开始执行」引导流程：
 *
 *   启动游戏 → 实时识别当前阶段（协议 / 登录页 / 扫码页 / 已登录大厅）
 *   → 登录成功后从右上角读昵称、从「手Qxxx区 / 微信xxx区」推断登录类型
 *   → 自动点「换区」，同步「我的服务器」里的全部区服
 *   → 返回一个可保存的 Account
 *
 * 游戏是 Unity 自绘，节点树没有文字，全程靠截屏 + ML Kit OCR。
 * 用户随时可以自己点登录方式（QQ / 微信 / 扫码），这里只负责识别与推进。
 */
object AddAccountFlow {

    private const val TAG = "AddAccount"
    private const val MAX_ATTEMPTS = 140        // 1.5s × 140 ≈ 3.5 分钟
    private const val SERVER_ATTEMPTS = 14      // 换区面板最多等 ~21s
    private const val GAME = GameLauncher.PACKAGE

    private val main = Handler(Looper.getMainLooper())
    /** 大厅顶部当前区服提示行。抢先服没有区号（如「微信王者抢先服」），必须单独放行 */
    private const val SERVER_RE = "(手Q|微信)\\s*(王者)?\\s*(抢先服|\\d+\\s*区)"

    @Volatile
    private var running = false
    /** 本次引导流程的取消令牌 */
    private var token = 0

    /** 本次流程里出现过的扫码页 → 用来把账号登录方式记成「扫码」 */
    private var qrSeen: LoginType? = null

    /** 上次点击协议「同意」的时间，避免在弹窗动画期间连着点好几次 */
    private var lastAgreeAt = 0L

    /** 上次点击「确定 / 允许」的时间，避免连着点 */
    private var lastConfirmAt = 0L

    fun start(
        ctx: Context,
        status: (String) -> Unit,
        done: (Account) -> Unit,
        fail: (String) -> Unit,
    ) {
        if (running) {
            fail("上一条引导还没结束")
            return
        }
        if (FlowControl.running.value) {
            fail("有自动化正在执行，请先点「停止」")
            return
        }
        if (!BotService.isReady) {
            fail("${Perms.accessibilityReason(ctx)}，无法自动识别")
            return
        }
        running = true
        token = FlowControl.begin("引导加账号")
        qrSeen = null
        IO.log(ctx, TAG, "开始：0 账号引导登录")

        status("正在启动王者荣耀…")
        if (BotService.foregroundPackage() != GAME) {
            if (!GameLauncher.launch(ctx)) {
                abort(fail, "无法启动王者荣耀，请确认已安装")
                return
            }
        }
        poll(ctx, 0, status, done, fail)
    }

    private fun abort(fail: (String) -> Unit, msg: String) {
        if (!running) return
        running = false
        FlowControl.end(token)
        fail(msg)
    }

    private fun succeed(done: (Account) -> Unit, acc: Account) {
        if (!running) return
        running = false
        FlowControl.end(token)
        done(acc)
    }

    private fun poll(
        ctx: Context,
        attempt: Int,
        status: (String) -> Unit,
        done: (Account) -> Unit,
        fail: (String) -> Unit,
    ) {
        if (!FlowControl.alive(token)) {
            abort(fail, "已强制停止")
            return
        }
        if (attempt >= MAX_ATTEMPTS) {
            abort(fail, "登录等待超时，请重新点「开始执行」")
            return
        }
        main.postDelayed({
            if (BotService.foregroundPackage() != GAME) {
                if (attempt % 8 == 0) status("等待游戏回到前台…")
                poll(ctx, attempt + 1, status, done, fail)
                return@postDelayed
            }
            BotService.capture { bmp ->
                if (bmp == null) {
                    poll(ctx, attempt + 1, status, done, fail)
                    return@capture
                }
                Ocr.recognize(bmp) { lines ->
                    onLines(ctx, bmp, bmp.width, bmp.height, lines, attempt + 1, status, done, fail)
                }
            }
        }, 1500)
    }

    private fun onLines(
        ctx: Context,
        bmp: android.graphics.Bitmap,
        w: Int,
        h: Int,
        lines: List<Ocr.ScanLine>,
        next: Int,
        status: (String) -> Unit,
        done: (Account) -> Unit,
        fail: (String) -> Unit,
    ) {
        if (!FlowControl.alive(token)) {
            abort(fail, "已强制停止")
            return
        }
        fun hit(kw: String) = lines.firstOrNull {
            it.text.replace(Regex("\\s+"), "").contains(kw)
        }
        fun retry() = poll(ctx, next, status, done, fail)

        if (next % 6 == 1) {
            IO.log(ctx, TAG, "OCR(${lines.size}) ${lines.take(14).joinToString(" | ") { it.text }}")
        }

        // 1) 游戏协议 / 隐私授权弹窗，首次运行会出现两种：
        //    「腾讯游戏用户协议和隐私政策」
        //    「游戏许可及服务协议和王者荣耀隐私保护指引」
        //    共同特征：底部同时有「拒绝」「同意」两个短按钮 → 直接点「同意」。
        //    注意：正文里也有「…当您点击同意并开始使用…」，不能用 contains 命中长句，
        //    只认文字完全等于「同意」的短行。
        val agreeShort = shortLine(lines, "同意") ?: shortLine(lines, "我同意")
        val looksAgreement = hit("拒绝") != null ||
            hit("许可及服务协议") != null || hit("隐私政策") != null || hit("隐私保护指引") != null
        val isAgreement = agreeShort != null && looksAgreement &&
            hit("想要打开") == null && hit("启动应用") == null
        if (isAgreement) {
            val now = System.currentTimeMillis()
            if (now - lastAgreeAt > 4000) {
                val agreeBtn = lines.asSequence()
                    .filter {
                        val t = it.text.replace(Regex("\\s+"), "")
                        t == "同意" || t == "我同意" || t == "同意并继续" || t == "同意并进入"
                    }
                    .filter { it.box.width() > 0 && it.box.height() > 0 }
                    // 只接受完全落在截图画布内的坐标，旋转过程中 OCR 坐标会越界
                    .filter {
                        it.box.left >= 0 && it.box.top >= 0 &&
                            it.box.right <= w && it.box.bottom <= h
                    }
                    .maxByOrNull { it.box.centerY() }
                lastAgreeAt = now
                when {
                    agreeBtn != null -> {
                        tapLine(ctx, agreeBtn, "同意协议")
                        status("协议已确认，等待进入登录…")
                    }
                    // 没读到按钮文字（按钮可能是图形化）→ 按弹窗布局点右下角的「同意」
                    w > h -> {
                        tapRatio(ctx, w, h, 0.563f, 0.679f, "同意协议(按钮位置)")
                        status("协议已确认，等待进入登录…")
                    }
                    else -> status("请手动点一下「同意」")
                }
            }
            retry()
            return
        }

        // 1.5) 游戏自己的「权限列表」弹窗（定位/存储/日历/麦克风/相机）→ 点「确定」
        if (hit("权限列表") != null || hit("为保证正常体验") != null) {
            val ok = shortLine(lines, "确定")
            if (ok != null && inBounds(ok, w, h) && System.currentTimeMillis() - lastConfirmAt > 2500) {
                lastConfirmAt = System.currentTimeMillis()
                tapLine(ctx, ok, "确定游戏权限列表")
                status("已确认游戏权限，等待登录…")
            }
            retry()
            return
        }

        // 1.6) 系统弹窗：
        //      「王者荣耀 想要打开 QQ，是否允许？」→ 本次允许 / 始终允许
        //      「是否允许访问…」→ 仅在使用中允许 / 始终允许
        val systemDialog = hit("想要打开") != null || hit("启动应用") != null || hit("是否允许") != null
        if (systemDialog) {
            val allow = lines.asSequence()
                .filter {
                    val t = it.text.replace(Regex("\\s+"), "")
                    t.contains("允许") && t.length <= 8 && it.text.contains("不允许").not()
                }
                .filter { inBounds(it, w, h) }
                .minByOrNull { it.box.top }   // 从上往下第一个：本次允许 / 仅在使用中允许
            if (allow != null && System.currentTimeMillis() - lastConfirmAt > 2000) {
                lastConfirmAt = System.currentTimeMillis()
                tapLine(ctx, allow, "允许系统弹窗")
            } else if (allow == null && System.currentTimeMillis() - lastConfirmAt > 2000) {
                // 偶尔 OCR 读不出按钮，按弹窗布局点右侧第一个按钮
                lastConfirmAt = System.currentTimeMillis()
                tapRatio(ctx, w, h, 0.66f, 0.48f, "允许系统弹窗(按钮位置)")
            }
            retry()
            return
        }

        // 2) 扫码页（另一种设备扫）
        if (hit("使用QQ手机版扫码") != null) {
            qrSeen = LoginType.QQ_QR
            status("请用另一台手机的 QQ 扫码授权")
            retry()
            return
        }
        if (hit("使用微信扫码") != null || hit("使用微信手机版扫码") != null) {
            qrSeen = LoginType.WECHAT_QR
            status("请用另一台手机的微信扫码授权")
            retry()
            return
        }

        // 3) 登录选择页
        val loginQQ = hit("QQ好友玩")
        val loginWX = hit("微信好友玩")
        if (loginQQ != null || loginWX != null) {
            status("请选择 QQ / 微信登录（右上角可扫码）")
            retry()
            return
        }

        // 4) 已登录大厅：右上角昵称 + 当前区服
        val lobby = hit("开始游戏") ?: hit("换区")
        val serverLine = lines.firstOrNull { Regex(SERVER_RE).containsMatchIn(it.text) }
        if (lobby != null && serverLine != null) {
            val type = qrSeen ?: if (serverLine.text.contains("微信")) LoginType.WECHAT else LoginType.QQ
            readNickname(ctx, bmp, lines, w, h) { name ->
                IO.log(ctx, TAG, "已登录 昵称=$name 区服=${serverLine.text} 类型=${type.name}")
                val change = hit("换区")
                if (change != null) {
                    tapLine(ctx, change, "换区")
                    status("登录成功：$name，正在同步区服…")
                    pollServers(ctx, name, type, 0, emptyList(), status, done, fail)
                } else {
                    // 找不到换区按钮就先把账号交出去，区服稍后手动扫
                    succeed(done, Account(name = name, loginType = type))
                    status("登录成功：$name（未找到换区入口，区服可稍后手动扫描）")
                }
            }
            return
        }

        if (next % 6 == 0) status("识别中：等待登录完成…")
        retry()
    }

    /** 「换区」打开的就是「选择服务器 → 我的服务器」，直接复用 Scanner 的 OCR 解析 */
    private fun pollServers(
        ctx: Context,
        name: String,
        type: LoginType,
        attempt: Int,
        best: List<com.farmbot.assistant.data.Farm>,
        status: (String) -> Unit,
        done: (Account) -> Unit,
        fail: (String) -> Unit,
    ) {
        if (!FlowControl.alive(token)) {
            abort(fail, "已强制停止")
            return
        }
        if (attempt >= SERVER_ATTEMPTS) {
            if (best.isEmpty()) {
                succeed(done, Account(name = name, loginType = type))
                status("登录成功：$name（没识别到区服，可稍后手动扫描）")
            } else {
                succeed(done, Account(name = name, loginType = type, farms = best))
                status("完成：$name · ${best.size} 个区服")
            }
            return
        }
        main.postDelayed({
            BotService.capture { bmp ->
                if (bmp == null) {
                    pollServers(ctx, name, type, attempt + 1, best, status, done, fail)
                    return@capture
                }
                Ocr.recognize(bmp) { lines ->
                    val farms = Ocr.toFarms(lines).toMutableList()
                    // 有些格子 OCR 只读到等级行、丢了区服名，做一次局部放大补识别
                    recoverAll(ctx, bmp, Ocr.orphans(lines), farms) { all ->
                        // 面板刚弹出时可能只渲染出一部分，多抓一轮、取更完整的那次
                        val merged = if (all.size > best.size) all else best
                        if (merged.isNotEmpty() && merged.size == best.size) {
                            IO.log(ctx, TAG, "同步到 ${merged.size} 个区服（连续两轮一致）")
                            tapRatio(ctx, bmp.width, bmp.height, 0.10f, 0.067f, "返回大厅")
                            succeed(done, Account(name = name, loginType = type, farms = merged))
                            status("完成：$name · ${merged.size} 个区服")
                        } else {
                            lines.firstOrNull { it.text.contains("换区") }?.let {
                                if (attempt == 3) tapLine(ctx, it, "换区")
                            }
                            pollServers(ctx, name, type, attempt + 1, merged, status, done, fail)
                        }
                    }
                }
            }
        }, 1500)
    }

    /**
     * 右上角昵称：先把右上角裁出来放大 3 倍再 OCR，比整屏识别稳；
     * 识别不到再退回整屏里的候选文本。
     */
    /** 供 LoginFlow 复用：登录成功后读右上角昵称 */
    fun readNickname(
        ctx: Context,
        bmp: android.graphics.Bitmap,
        lines: List<Ocr.ScanLine>,
        w: Int,
        h: Int,
        cb: (String) -> Unit,
    ) {
        val fallback = pickNickname(lines, w, h, region = true)
        val left = (w * 0.70f).toInt()
        val bottom = (h * 0.16f).toInt()
        if (w - left < 40 || bottom < 20) {
            cb(fallback)
            return
        }
        val crop = runCatching {
            android.graphics.Bitmap.createBitmap(bmp, left, 0, w - left, bottom)
        }.getOrNull()
        if (crop == null) {
            cb(fallback)
            return
        }
        Ocr.recognize(crop, 5f) { cropLines ->
            crop.recycle()
            val direct = pickNickname(cropLines, crop.width, crop.height, region = false)
            val name = direct.ifEmpty { fallback }
            IO.log(ctx, TAG, "昵称候选=${cropLines.map { it.text }} -> $name")
            cb(name)
        }
    }

    /** 右上角昵称：排除「退出」「12+」这类按钮文字 */
    private fun pickNickname(
        lines: List<Ocr.ScanLine>,
        w: Int,
        h: Int,
        region: Boolean,
    ): String {
        return lines.asSequence()
            .filter { !region || (it.box.left > w * 0.72f && it.box.top < h * 0.14f) }
            .map { it.text.replace("退出", "").trim() }
            .filter { t ->
                t.length in 2..16 &&
                    !t.contains("12+") &&
                    !t.matches(Regex("[0-9+\\-—·.]+"))
            }
            .maxByOrNull { it.length }
            ?.replace(Regex("\\s+"), " ")
            .orEmpty()
    }

    private fun tapRatio(ctx: Context, w: Int, h: Int, rx: Float, ry: Float, what: String) {
        val x = (w * rx).toInt()
        val y = (h * ry).toInt()
        IO.log(ctx, TAG, "点击 $what ($x,$y)")
        BotService.tap(x, y)
    }

    /** 供 LoginFlow 复用：对缺名称的格子做局部裁剪 + 放大识别（与 Scanner 同思路） */
    fun recoverAll(
        ctx: Context,
        bmp: android.graphics.Bitmap,
        pending: List<Ocr.ScanLine>,
        acc: MutableList<com.farmbot.assistant.data.Farm>,
        done: (List<com.farmbot.assistant.data.Farm>) -> Unit,
    ) {
        if (pending.isEmpty()) {
            done(acc)
            return
        }
        recoverOne(ctx, bmp, pending.first()) { farm ->
            if (farm != null && acc.none { it.server == farm.server }) acc.add(farm)
            recoverAll(ctx, bmp, pending.drop(1), acc, done)
        }
    }

    private fun recoverOne(
        ctx: Context,
        bmp: android.graphics.Bitmap,
        orphan: Ocr.ScanLine,
        done: (com.farmbot.assistant.data.Farm?) -> Unit,
    ) {
        val parsed = Ocr.parseLevelRole(orphan.text) ?: return done(null)
        val b = orphan.box
        val left = maxOf(0, b.left - 30)
        val top = maxOf(0, b.top - 135)
        val right = minOf(bmp.width, b.right + 260)
        val bottom = minOf(bmp.height, b.top + 8)
        if (right - left < 40 || bottom - top < 30) return done(null)
        val crop = android.graphics.Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
        Ocr.recognize(crop, 5f) { lines ->
            val name = lines.asReversed().mapNotNull { Ocr.nameOf(it.text) }
                .firstOrNull { it.length in 4..24 }
            crop.recycle()
            if (name == null) {
                IO.log(ctx, TAG, "补识别失败: ${orphan.text}")
                done(null)
            } else {
                IO.log(ctx, TAG, "补识别成功: $name")
                done(com.farmbot.assistant.data.Farm(server = name, role = parsed.second, level = parsed.first))
            }
        }
    }

    /** 精确匹配按钮文字（去空格后完全相等），避免命中正文里的同名词语 */
    private fun shortLine(lines: List<Ocr.ScanLine>, text: String): Ocr.ScanLine? =
        lines.firstOrNull { it.text.replace(Regex("\\s+"), "") == text }

    private fun inBounds(line: Ocr.ScanLine, w: Int, h: Int): Boolean =
        line.box.left >= 0 && line.box.top >= 0 && line.box.right <= w && line.box.bottom <= h

    private fun tapLine(ctx: Context, line: Ocr.ScanLine, what: String) {
        val x = line.box.centerX()
        val y = line.box.centerY()
        IO.log(ctx, TAG, "点击 $what ($x,$y) :: ${line.text}")
        BotService.tap(x, y)
    }
}
