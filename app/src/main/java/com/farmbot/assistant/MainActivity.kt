package com.farmbot.assistant

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.farmbot.assistant.alarm.SystemAlarm
import com.farmbot.assistant.bot.BotService
import com.farmbot.assistant.bot.ClosePopupFlow
import com.farmbot.assistant.bot.EnterFarmFlow
import com.farmbot.assistant.bot.EnterGameFlow
import com.farmbot.assistant.bot.ExitFarmFlow
import com.farmbot.assistant.bot.ReturnToSelectFlow
import com.farmbot.assistant.bot.FarmWaterFlow
import com.farmbot.assistant.bot.FarmWalkFlow
import com.farmbot.assistant.bot.HarvestSetupFlow
import com.farmbot.assistant.bot.LandScanFlow
import com.farmbot.assistant.bot.FlowControl
import com.farmbot.assistant.bot.LoginFlow
import com.farmbot.assistant.core.IO
import com.farmbot.assistant.data.Account
import com.farmbot.assistant.data.AccountStore
import com.farmbot.assistant.data.Farm
import com.farmbot.assistant.data.LoginType
import com.farmbot.assistant.data.mergeAccounts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.farmbot.assistant.overlay.FloatingOverlay
import com.farmbot.assistant.perm.Perms
import com.farmbot.assistant.ui.AccountScreen
import com.farmbot.assistant.ui.BottomNav
import com.farmbot.assistant.ui.LogsPage
import com.farmbot.assistant.ui.PermissionScreen
import com.farmbot.assistant.ui.RulesPage
import com.farmbot.assistant.ui.SettingsPage
import com.farmbot.assistant.ui.UpdateBanner
import com.farmbot.assistant.ui.theme.Bg
import com.farmbot.assistant.ui.theme.FarmBotTheme
import com.farmbot.assistant.update.Updater
import kotlinx.coroutines.delay

data class PermState(
    val acc: Boolean = false,
    /** 设置里写着已开启、但服务已被系统解除绑定（HyperOS 卡死态，只能重启设备恢复） */
    val accStuck: Boolean = false,
    val notif: Boolean = false,
    val overlay: Boolean = false,
    val bgPopup: Boolean = false,
    /** 可选增强项：以下都不参与放行判断，只影响「到点自动跑」的成功率 */
    val alarm: Boolean = false,
    val autoStart: Boolean = false,
    val battery: Boolean = false,
    val tile: Boolean = false,
)

class MainActivity : ComponentActivity() {

    private val perms = mutableStateOf(PermState())
    /** 已设定的测试闹钟触发时间；null = 没有在等待的闹钟 */
    private val alarmAt = mutableStateOf<Long?>(null)
    /** 距离闹钟触发的剩余秒数，仅用于界面倒计时 */
    private val alarmRemain = mutableStateOf(0L)
    /** 多账号串行队列是否在跑：账号与账号之间有空档，不能只看 FlowControl.running */
    private val queueRunning = mutableStateOf(false)
    /** 屏幕保护的纯黑遮罩是否正在显示 */
    private val saverBlack = mutableStateOf(false)
    /** 最后一次碰屏幕的时间：屏幕保护的 5 分钟空闲计时基准 */
    private var lastTouchAt = System.currentTimeMillis()
    private lateinit var store: AccountStore
    /** 「已进入过账号管理」标志，落盘后启动直达账号页 */
    private val prefs by lazy { getSharedPreferences("farmbot", MODE_PRIVATE) }

    private companion object {
        const val KEY_ENTERED = "entered_account_management"
        const val KEY_SAVER = "screen_saver"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 平板：放开清单里的竖屏锁定，跟随设备旋转满屏显示
        // （不放开时系统会把竖屏 App 塞进横屏中间的一条窄窗口，两侧留黑边）
        if (resources.configuration.smallestScreenWidthDp >= 600) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        store = AccountStore(this)
        // 首次打开：预置一张微信登录卡和一张 QQ 登录卡，用户直接填内容即可
        val initial = if (store.exists()) {
            store.load()
        } else {
            listOf(
                Account(loginType = LoginType.WECHAT),
                Account(loginType = LoginType.QQ),
            ).also { store.save(it) }
        }
        refreshPerms()
        syncAlarm()
        // 检测更新：进入检测一次，进程存活期间每小时轮询一次
        Updater.start(this)

        setContent {
            FarmBotTheme {
                var accounts by remember { mutableStateOf(initial) }
                // 点过一次「进入账号管理」后就不再自动回到权限页；只有无障碍服务掉了才强制显示
                var showPerms by remember { mutableStateOf(!prefs.getBoolean(KEY_ENTERED, false)) }

                // 屏幕保护：开关开着时强制不息屏；5 分钟没碰屏幕 → 盖纯黑（点任意处恢复）
                var saverOn by remember { mutableStateOf(prefs.getBoolean(KEY_SAVER, false)) }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(3000)
                        saverOn = prefs.getBoolean(KEY_SAVER, false)
                        saverBlack.value = saverOn &&
                            System.currentTimeMillis() - lastTouchAt >= 5 * 60_000L
                    }
                }
                LaunchedEffect(saverOn) {
                    if (saverOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                LaunchedEffect(saverBlack.value) {
                    val ctl = WindowCompat.getInsetsController(window, window.decorView)
                    if (saverBlack.value) {
                        // 纯黑要盖满整屏：藏系统栏 + 收掉执行中的悬浮窗状态字
                        FloatingOverlay.hide(this@MainActivity)
                        ctl.hide(WindowInsetsCompat.Type.systemBars())
                    } else {
                        ctl.show(WindowInsetsCompat.Type.systemBars())
                    }
                }

                fun update(next: List<Account>) {
                    accounts = next
                    store.save(next)
                }

                // 导出：系统文件选择器里选保存位置，写入账号 + 区服 + 勾选状态
                val exportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/json")
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    val ok = runCatching {
                        contentResolver.openOutputStream(uri)?.use {
                            it.write(store.toJson(accounts).toByteArray(Charsets.UTF_8))
                        } ?: error("无法写入")
                    }.isSuccess
                    toast(if (ok) "已导出 ${accounts.size} 个账号" else "导出失败，换个位置再试")
                }
                // 导入：选备份文件，按登录方式 + QQ 号/昵称合并，只补缺的账号和区服
                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    val text = runCatching {
                        contentResolver.openInputStream(uri)?.use {
                            it.readBytes().toString(Charsets.UTF_8)
                        }
                    }.getOrNull()
                    val list = text?.let { store.parseJson(it) }
                    if (list == null) {
                        toast("文件格式不对，未导入")
                    } else {
                        val merged = mergeAccounts(accounts, list)
                        if (merged.addedAccounts == 0 && merged.addedFarms == 0) {
                            toast("没有新的账号或区服")
                        } else {
                            update(merged.accounts)
                            toast("已导入 ${merged.addedAccounts} 个账号、${merged.addedFarms} 个区服")
                        }
                    }
                }

                /** 发现到的 QQ 号是否就是已有卡片：优先比 QQ 昵称，没学过昵称时用身份名交集兜底 */
                fun sameQq(a: Account, f: Account): Boolean {
                    if (a.qqNick.isNotBlank() && f.qqNick.isNotBlank()) return a.qqNick == f.qqNick
                    val names = setOf(a.name) + a.identities
                    return f.identities.any { it.isNotBlank() && it in names }
                }

                /**
                 * 「同步区服」：按列表顺序逐个登录，进「换区」面板抓区服并回写。
                 * 一个账号结束再跑下一个；这一轮清点出来的其它 QQ 号由 onDiscovered 补成卡片并追加到队尾。
                 */
                fun syncQueue(queue: MutableList<String>, index: Int, epoch: Int) {
                    // 点过「停止」：队列也要断，不能因为 done 回调继续跑下一个
                    if (FlowControl.cancelEpoch != epoch) {
                        queueRunning.value = false
                        FloatingOverlay.setText("已强制停止")
                        FloatingOverlay.hide(this@MainActivity)
                        return
                    }
                    if (index >= queue.size) {
                        queueRunning.value = false
                        FloatingOverlay.setText("区服同步完成")
                        toast("区服同步完成")
                        // 同步全程都在游戏里跑，结束后把农场助手拉回前台
                        bringAppToFront()
                        window.decorView.postDelayed(
                            { FloatingOverlay.hide(this@MainActivity) }, 3000
                        )
                        return
                    }
                    val cur = accounts.firstOrNull { it.id == queue[index] }
                    if (cur == null) {
                        syncQueue(queue, index + 1, epoch)
                        return
                    }
                    // 其它 QQ 账号已经占用的名字：QQ 昵称、游戏昵称、该 QQ 号下的全部身份
                    val takenQq = accounts
                        .filter { it.loginType == LoginType.QQ && it.id != cur.id }
                        .flatMap { listOf(it.qqNick, it.name) + it.identities }
                        .filter { it.isNotBlank() }
                        .toSet()
                    LoginFlow.startAuto(
                        this@MainActivity,
                        cur,
                        status = { FloatingOverlay.setText(it) },
                        done = { updated, msg ->
                            update(accounts.map { if (it.id == cur.id) updated else it })
                            FloatingOverlay.setText(msg)
                            toast(msg)
                            window.decorView.postDelayed({ syncQueue(queue, index + 1, epoch) }, 900)
                        },
                        excludeQq = takenQq,
                        syncFarms = true,
                        onDiscovered = { found ->
                            val next = accounts.toMutableList()
                            val added = ArrayList<Account>()
                            for (f in found) {
                                val sameIdx = next.indexOfFirst { a -> sameQq(a, f) }
                                if (sameIdx >= 0) {
                                    val a = next[sameIdx]
                                    val ids = if (f.identities.isNotEmpty()) f.identities else a.identities
                                    if (a.qqNick.isBlank() || a.identities != ids || a.qqNo.isBlank()) {
                                        next[sameIdx] = a.copy(
                                            qqNick = a.qqNick.ifBlank { f.qqNick },
                                            qqNo = a.qqNo.ifBlank { f.qqNo },
                                            identities = ids,
                                        )
                                    }
                                    continue
                                }
                                // 有空白卡就填进空白卡（先建卡再同步的场景）；
                                // 空白卡本来就在队列里，不用再追加
                                val blankIdx = next.indexOfFirst {
                                    it.id != cur.id && it.name.isBlank() && it.qqNick.isBlank() &&
                                        it.identities.isEmpty() && it.farms.isEmpty()
                                }
                                if (blankIdx >= 0) {
                                    next[blankIdx] = next[blankIdx].copy(
                                        name = f.name,
                                        qqNick = f.qqNick,
                                        qqNo = f.qqNo,
                                        loginType = LoginType.QQ,
                                        identities = f.identities,
                                    )
                                } else {
                                    next += f
                                    added += f
                                }
                            }
                            if (next != accounts) {
                                update(next)
                                queue.addAll(added.map { it.id })
                                val names = found.joinToString("、") { it.qqNick.ifBlank { it.name } }
                                toast("已补全识别到的 QQ 账号：$names")
                            }
                        },
                    )
                }

                /**
                 * 「开始执行」（当前模块）：登录第一个有勾选区服的账号 → EnterGameFlow
                 * 识别当前区 / 换区选中目标区 / 点开始游戏。多账号队列与进农场等后续模块接入后再扩展。
                 */
                fun runExec(cur: Account, epoch: Int) {
                    // 点过「停止」：链路要断，不能因为 done 回调继续往下走
                    if (FlowControl.cancelEpoch != epoch) {
                        queueRunning.value = false
                        return
                    }
                    // 其它 QQ 账号已占用的名字：登录切号时要排除
                    val takenQq = accounts
                        .filter { it.loginType == LoginType.QQ && it.id != cur.id }
                        .flatMap { listOf(it.qqNick, it.name) + it.identities }
                        .filter { it.isNotBlank() }
                        .toSet()
                    LoginFlow.startAuto(
                        this@MainActivity,
                        cur,
                        status = { FloatingOverlay.setText(it) },
                        done = { updated, msg ->
                            update(accounts.map { if (it.id == cur.id) updated else it })
                            if (FlowControl.cancelEpoch != epoch) {
                                queueRunning.value = false
                                return@startAuto
                            }
                            // 登录没成功就停在这里：区服没核实之前绝不能拿别的账号的会话去换区
                            if (!msg.startsWith("已登录")) {
                                queueRunning.value = false
                                FloatingOverlay.setText(msg)
                                toast(msg)
                                window.decorView.postDelayed(
                                    { FloatingOverlay.hide(this@MainActivity) }, 3000
                                )
                                return@startAuto
                            }
                            // 登录成功 → 逐个区服执行：进游戏 → 清弹窗 → 进农场 → 走到雕塑 → 设置 → 一键浇水
                            beginZoneLoop(updated, epoch)
                        },
                        excludeQq = takenQq,
                        // 日常执行只登录不抓区服：区服数据由「同步区服」维护
                        syncFarms = false,
                    )
                }

                // 倒计时：每秒刷新一次剩余时间，到点自动清空
                val pendingAt = alarmAt.value
                LaunchedEffect(pendingAt) {
                    if (pendingAt == null) {
                        alarmRemain.value = 0L
                        return@LaunchedEffect
                    }
                    while (true) {
                        val remain = ((pendingAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                        alarmRemain.value = remain
                        if (remain == 0L) {
                            alarmAt.value = null
                            break
                        }
                        delay(500)
                    }
                }

                val p = perms.value
                Box(Modifier.fillMaxSize()) {
                // 权限页只在两种情况下出现：用户主动点「权限」，或无障碍服务没开
                if (showPerms || !p.acc) {
                    PermissionScreen(
                        accessibility = p.acc,
                        accessibilityStuck = p.accStuck,
                        notifications = p.notif,
                        overlay = p.overlay,
                        bgPopup = p.bgPopup,
                        alarm = p.alarm,
                        autoStart = p.autoStart,
                        battery = p.battery,
                        tile = p.tile,
                        alarmRemain = alarmRemain.value,
                        onRefresh = {
                            refreshPerms()
                            // 顺带核对测试闹钟是否还在（用户可能已经在时钟里删掉）
                            syncAlarm()
                            toast("已重新检查各权限状态")
                        },
                        onAccessibility = {
                            if (!Perms.openAccessibility(this@MainActivity)) {
                                toast("无法打开无障碍设置页")
                            }
                        },
                        onNotification = { requestNotification() },
                        onOverlay = {
                            if (!Perms.openOverlay(this@MainActivity)) {
                                toast("无法打开悬浮窗设置页")
                            }
                        },
                        onBgPopup = {
                            if (!Perms.openMiuiPermissions(this@MainActivity)) {
                                toast("无法打开权限设置页")
                            }
                        },
                        onAlarm = {
                            if (!Perms.openExactAlarm(this@MainActivity)) {
                                toast("无法打开精确闹钟设置页，请到应用权限里手动开启「闹钟和提醒」")
                            }
                        },
                        onAlarmTest = {
                            if (!Perms.canScheduleExactAlarms(this@MainActivity)) {
                                toast("还没有精确闹钟权限，先开启再试")
                                Perms.openExactAlarm(this@MainActivity)
                            } else {
                                val at = SystemAlarm.setAfter(this@MainActivity, 60)
                                if (at == null) {
                                    toast("没能在系统时钟里创建闹钟，请确认手机时钟可用")
                                } else {
                                    alarmAt.value = at
                                    syncAlarm()
                                    toast("已在系统时钟里加好闹钟：${SystemAlarm.hhmm(at)}，打开时钟即可看到，到点由系统闹钟响铃")
                                }
                            }
                        },
                        onAlarmOpen = {
                            if (!SystemAlarm.openClock(this@MainActivity)) toast("无法打开时钟，请手动打开")
                        },
                        onAutoStart = {
                            if (!Perms.openAutoStart(this@MainActivity)) {
                                toast("无法打开自启动设置页，请到安全中心手动开启")
                            }
                        },
                        onBattery = {
                            if (!Perms.openBatteryPolicy(this@MainActivity)) {
                                toast("无法打开省电策略页")
                            }
                        },
                        onTile = {
                            Perms.requestAddTile(this@MainActivity) { msg ->
                                toast(msg)
                                // 回调时磁贴状态已经复查过；再补两次刷新，等 SystemUI 落库
                                refreshPerms()
                                window.decorView.postDelayed({ refreshPerms() }, 600)
                            }
                        },
                        onEnter = {
                            showPerms = false
                            prefs.edit().putBoolean(KEY_ENTERED, true).apply()
                        }
                    )
                } else {
                    var tab by remember { mutableStateOf(0) }
                    // 返回手势：非农场页先回农场页；农场页要连按两次才退出，避免误触杀掉正在跑的任务
                    var lastBackAt by remember { mutableLongStateOf(0L) }
                    BackHandler {
                        when {
                            tab != 0 -> tab = 0
                            System.currentTimeMillis() - lastBackAt < 2000L -> finish()
                            else -> {
                                lastBackAt = System.currentTimeMillis()
                                toast("再按一次返回退出")
                            }
                        }
                    }
                    // 刚新建的账号卡 id：只让它播放入场动画，动画结束就清掉，切 Tab 回来不会再弹一次
                    var justAddedId by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(justAddedId) {
                        if (justAddedId != null) {
                            delay(800)
                            justAddedId = null
                        }
                    }
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(Bg)
                    ) {
                        val tabSlide = tween<IntOffset>(220)
                        val tabFade = tween<Float>(200)
                        Box(Modifier.weight(1f)) {
                            // 切 Tab 时轻微左右滑动 + 淡入淡出：往右切新页从右边来，往左切反过来
                            AnimatedContent(
                                targetState = tab,
                                modifier = Modifier.fillMaxSize(),
                                transitionSpec = {
                                    if (targetState > initialState) {
                                        (slideInHorizontally(tabSlide) { it / 8 } + fadeIn(tabFade)) togetherWith
                                            (slideOutHorizontally(tabSlide) { -it / 8 } + fadeOut(tabFade))
                                    } else {
                                        (slideInHorizontally(tabSlide) { -it / 8 } + fadeIn(tabFade)) togetherWith
                                            (slideOutHorizontally(tabSlide) { it / 8 } + fadeOut(tabFade))
                                    }
                                },
                                label = "tab"
                            ) { curTab ->
                            when (curTab) {
                                0 -> AccountScreen(
                        accounts = accounts,
                        // 新账号插到最前面：加完就在第一屏，不用滑到列表底部去填信息
                        onAdd = {
                            val blank = Account()
                            update(listOf(blank) + accounts)
                            justAddedId = blank.id
                        },
                        onChange = { changed ->
                            update(accounts.map { if (it.id == changed.id) changed else it })
                        },
                        onRemove = { id -> update(accounts.filterNot { it.id == id }) },
                        onMove = { id, delta ->
                            val from = accounts.indexOfFirst { it.id == id }
                            val to = from + delta
                            if (from >= 0 && to in accounts.indices) {
                                val list = accounts.toMutableList()
                                list.add(to, list.removeAt(from))
                                update(list)
                            }
                        },
                        justAddedId = justAddedId,
                        // 开始执行：登录第一个有勾选区服的账号 → 识别/换区 → 点开始游戏
                        // （多账号串行、进农场、关弹窗等后续模块接入后再扩展）
                        onStart = {
                            when {
                                !Perms.overlayEnabled(this@MainActivity) -> {
                                    toast("需要悬浮窗权限才能显示执行状态，请先开启")
                                    Perms.openOverlay(this@MainActivity)
                                }
                                queueRunning.value || FlowControl.running.value ->
                                    toast("正在执行，请等待或先点「停止」")
                                else -> {
                                    val target = accounts.firstOrNull {
                                        it.enabled && !it.loginType.qr && it.activeFarms.isNotEmpty()
                                    }
                                    if (target == null) {
                                        toast("没有已勾选区服的账号，请先在账号卡里勾选要处理的区服")
                                    } else {
                                        if (!FloatingOverlay.show(
                                                this@MainActivity,
                                                "开始执行：登录「${target.name.ifBlank { "账号" }}」…"
                                            )
                                        ) {
                                            toast("悬浮窗未显示，请检查悬浮窗权限")
                                        }
                                        queueRunning.value = true
                                        runExec(target, FlowControl.cancelEpoch)
                                    }
                                }
                            }
                        },
                        // 同步区服：把本地已保存的账号逐个登录一遍，刷新各自的区服列表
                        onSync = {
                            when {
                                !Perms.overlayEnabled(this@MainActivity) -> {
                                    toast("需要悬浮窗权限才能显示同步状态，请先开启")
                                    Perms.openOverlay(this@MainActivity)
                                }
                                queueRunning.value || FlowControl.running.value ->
                                    toast("正在执行，请等待或先点「停止」")
                                else -> {
                                    // 先微信、后 QQ；扫码登录的账号无法自动登录，不参与同步
                                    val wx = accounts.filter { it.loginType == LoginType.WECHAT }
                                    val qq = accounts.filter { it.loginType == LoginType.QQ }
                                    val queue = (wx + qq).map { it.id }.toMutableList()
                                    if (queue.isEmpty()) {
                                        toast("没有可自动同步的账号（扫码登录的账号需要手动同步）")
                                    } else {
                                        if (!FloatingOverlay.show(
                                                this@MainActivity,
                                                "开始同步 ${queue.size} 个账号的区服…"
                                            )
                                        ) {
                                            toast("悬浮窗未显示，请检查悬浮窗权限")
                                        }
                                        queueRunning.value = true
                                        syncQueue(queue, 0, FlowControl.cancelEpoch)
                                    }
                                }
                            }
                        },
                        running = FlowControl.running.value || queueRunning.value,
                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                        onExport = {
                            val name = "farmbot-accounts-" +
                                java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                                    .format(java.util.Date()) + ".json"
                            exportLauncher.launch(name)
                        },
                        onStop = {
                            // 强制停止：作废所有在跑循环的 token，收起悬浮窗状态层
                            IO.log(this@MainActivity, "Stop", "用户强制停止自动化${FlowControl.label.value.let { if (it.isEmpty()) "" else "（$it）" }}")
                            FlowControl.cancel()
                            queueRunning.value = false
                            FloatingOverlay.setText("已强制停止")
                            FloatingOverlay.hide(this@MainActivity)
                            toast("已强制停止自动化")
                        }
                    )
                                1 -> RulesPage()
                                2 -> LogsPage()
                                else -> SettingsPage(
                                    onOpenPerms = {
                                        showPerms = true
                                        refreshPerms()
                                    }
                                )
                            }
                            }
                        }
                        // 底部导航固定在最下方；「开始执行」在农场页里，天然位于它上方
                        BottomNav(selected = tab, onSelect = { tab = it }, badge = Updater.latestTag != null)
                    }
                }
                // 新版本提示：盖在最上沿的非模态小横幅，不打断、不遮挡执行中的任务
                Box(Modifier.fillMaxWidth().align(Alignment.TopCenter)) { UpdateBanner() }
                // 屏幕保护：纯黑盖住一切，碰一下屏幕（dispatchTouchEvent）就恢复
                if (saverBlack.value) {
                    Box(Modifier.fillMaxSize().background(Color.Black))
                }
                }
            }
        }
    }

    /** 自动化跑完把农场助手拉回前台；悬浮窗权限已具备，后台启动自己的 Activity 不会被系统拦 */
    private fun bringAppToFront() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        )
    }

    /** 与系统时钟里那条闹钟对齐（从后台回来时刷新倒计时） */
    private fun syncAlarm() {
        alarmAt.value = SystemAlarm.pendingAt(this)
    }

    /** 本轮要处理的区服（串行）；第一个区从当前开始页进，后面每个区都冷重启回开始页再换区 */
    private val zoneQueue = mutableListOf<Farm>()
    private var zoneAcc: Account? = null
    /** 当前执行中的区；失败恢复回选服时要用 */
    private var zoneFarm: Farm? = null
    /** 每个区的失败恢复次数，超限才真正终止报错 */
    private var zoneTries = 0

    private fun beginZoneLoop(acc: Account, epoch: Int) {
        zoneAcc = acc
        zoneQueue.clear()
        zoneQueue.addAll(acc.activeFarms)
        IO.log(this, "Zone", "本账号勾选了 ${zoneQueue.size} 个区服")
        nextZone(epoch, first = true)
    }

    private fun nextZone(epoch: Int, first: Boolean) {
        if (!zoneAlive(epoch)) return
        val acc = zoneAcc
        val farm = zoneQueue.removeFirstOrNull()
        if (acc == null || farm == null) {
            // 全处理完 → 退出农场回大厅收工
            exitAndFinish(epoch)
            return
        }
        zoneFarm = farm
        zoneTries = 0
        IO.log(this, "Zone", "处理区服「${farm.title}」剩余 ${zoneQueue.size} 个")
        if (first) {
            enterZone(acc, farm, epoch)
        } else {
            switchZone(acc, farm, epoch)
        }
    }

    /** 队列收尾：退出农场回大厅；失败走恢复重试，不直接终止 */
    private fun exitAndFinish(epoch: Int) {
        ExitFarmFlow.start(
            this,
            status = { FloatingOverlay.setText(it) },
            done = { m ->
                if (!zoneAlive(epoch)) return@start
                if (m.startsWith("已退出")) endChain("全部区服已处理完")
                else zoneRetry(m) { exitAndFinish(epoch) }
            },
        )
    }

    /** 换区：先退农场回大厅，再「齿轮 → 退出登录」回到选区页，不杀游戏；哪步失败重试哪步 */
    private fun switchZone(acc: Account, farm: Farm, epoch: Int) {
        ExitFarmFlow.start(
            this,
            status = { FloatingOverlay.setText(it) },
            done = { m ->
                if (!zoneAlive(epoch)) return@start
                if (!m.startsWith("已退出")) {
                    zoneRetry(m) { switchZone(acc, farm, epoch) }
                    return@start
                }
                returnToSelect(acc, farm, epoch)
            },
        )
    }

    private fun returnToSelect(acc: Account, farm: Farm, epoch: Int) {
        ReturnToSelectFlow.start(
            this,
            status = { FloatingOverlay.setText(it) },
            done = { m ->
                if (!zoneAlive(epoch)) return@start
                if (m.startsWith("已回到选区")) enterZone(acc, farm, epoch)
                else zoneRetry(m) { returnToSelect(acc, farm, epoch) }
            },
        )
    }

    private fun enterZone(acc: Account, farm: Farm, epoch: Int) {
        EnterGameFlow.start(
            this,
            acc,
            farm,
            status = { FloatingOverlay.setText(it) },
            done = { m ->
                if (!zoneAlive(epoch)) return@start
                if (!m.startsWith("已进入")) {
                    recoverToSelect(epoch, m)
                    return@start
                }
                ClosePopupFlow.start(
                    this,
                    status = { FloatingOverlay.setText(it) },
                    done = { m2 ->
                        if (!zoneAlive(epoch)) return@start
                        if (!m2.startsWith("游戏内")) {
                            recoverToSelect(epoch, m2)
                            return@start
                        }
                        enterFarmAndWork(epoch)
                    },
                )
            },
        )
    }

    private fun enterFarmAndWork(epoch: Int) {
        EnterFarmFlow.start(
            this,
            status = { FloatingOverlay.setText(it) },
            done = { m ->
                if (!zoneAlive(epoch)) return@start
                if (!m.startsWith("已进入")) {
                    recoverToSelect(epoch, m)
                    return@start
                }
                // 先走到雕塑 → 再往前走看左侧植物详情、记录土地状态 → 回到雕塑点齿轮
                FarmWalkFlow.start(
                    this,
                    status = { FloatingOverlay.setText(it) },
                    done = { m2 ->
                        if (!zoneAlive(epoch)) return@start
                        if (!m2.startsWith("已走到")) {
                            recoverRedoFarm(epoch, m2)
                            return@start
                        }
                        LandScanFlow.start(
                            this,
                            status = { FloatingOverlay.setText(it) },
                            done = { m3 ->
                                if (!zoneAlive(epoch)) return@start
                                if (!m3.startsWith("已记录")) {
                                    recoverRedoFarm(epoch, m3)
                                    return@start
                                }
                                FarmWalkFlow.start(
                                    this,
                                    status = { FloatingOverlay.setText(it) },
                                    done = { m4 ->
                                        if (!zoneAlive(epoch)) return@start
                                        if (!m4.startsWith("已打开")) {
                                            recoverRedoFarm(epoch, m4)
                                            return@start
                                        }
                                        HarvestSetupFlow.start(
                                            this,
                                            status = { FloatingOverlay.setText(it) },
                                            done = { m5 ->
                                                if (!zoneAlive(epoch)) return@start
                                                if (!m5.startsWith("一键务农已") && !m5.startsWith("没有需要")) {
                                                    recoverRedoFarm(epoch, m5)
                                                    return@start
                                                }
                                                FarmWaterFlow.start(
                                                    this,
                                                    status = { FloatingOverlay.setText(it) },
                                                    done = { m6 ->
                                                        if (!zoneAlive(epoch)) return@start
                                                        if (!m6.startsWith("浇水完成")) {
                                                            recoverRedoFarm(epoch, m6)
                                                            return@start
                                                        }
                                                        IO.log(this, "Zone", "本区完成：$m6")
                                                        nextZone(epoch, first = false)
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                    openSettings = false,
                )
            },
        )
    }

    // —— 失败恢复：链上失败不再直接终止，按所处场景自动重跑（每区最多恢复 2 次） ——

    /** 恢复统一入口：超限才真正终止报错；恢复流程自己再失败会回到这里递归重试 */
    private fun zoneRetry(err: String, again: () -> Unit) {
        if (++zoneTries > 2) {
            endChain("$err（已恢复 ${zoneTries - 1} 次仍失败，已终止）")
            return
        }
        IO.log(this, "Zone", "失败恢复第 $zoneTries 次：$err")
        FloatingOverlay.setText("正在恢复重跑…")
        again()
    }

    /** 终止发生在大厅（进游戏 / 清弹窗 / 进农场失败）→ 返回选服界面，对当前区重新执行 */
    private fun recoverToSelect(epoch: Int, err: String) {
        val acc = zoneAcc
        val farm = zoneFarm
        if (acc == null || farm == null) {
            endChain(err)
            return
        }
        zoneRetry(err) {
            ReturnToSelectFlow.start(
                this,
                status = { FloatingOverlay.setText(it) },
                done = { m ->
                    if (!zoneAlive(epoch)) return@start
                    if (m.startsWith("已回到选区")) enterZone(acc, farm, epoch)
                    else recoverToSelect(epoch, m)
                },
            )
        }
    }

    /** 终止发生在农场（走路 / 记录土地 / 设置 / 浇水失败）→ 退出农场，重新进入执行务农 */
    private fun recoverRedoFarm(epoch: Int, err: String) {
        zoneRetry(err) {
            ExitFarmFlow.start(
                this,
                status = { FloatingOverlay.setText(it) },
                done = { m ->
                    if (!zoneAlive(epoch)) return@start
                    if (m.startsWith("已退出")) enterFarmAndWork(epoch)
                    else recoverRedoFarm(epoch, m)
                },
            )
        }
    }

    private fun zoneAlive(epoch: Int): Boolean {
        if (FlowControl.cancelEpoch != epoch) {
            queueRunning.value = false
            return false
        }
        return true
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /** 结束执行链：收起队列状态，悬浮窗显示结果后自动隐藏 */
    private fun endChain(msg: String) {
        queueRunning.value = false
        // 终止原因（含恢复超限）必须落日志，否则真机排查只剩悬浮窗 4 秒一闪而过
        IO.log(this, "Zone", "执行链结束：$msg")
        FloatingOverlay.setText(msg)
        toast(msg)
        window.decorView.postDelayed({ FloatingOverlay.hide(this) }, 4000)
    }

    private fun refreshPerms() {
        val bound = BotService.isReady
        if (bound) Perms.markAccessibilityBound(this)
        perms.value = PermState(
            // 设置项与「服务是否真的连着」取或关系：任一成立即视为开启，避免误判把用户挡在门外
            acc = Perms.accessibilityEnabled(this) || bound,
            // 曾经连上过、现在设置还在却不绑定：系统侧卡死，重开开关没用，只能重启设备
            accStuck = Perms.accessibilityStuck(this, bound),
            notif = Perms.notificationsEnabled(this),
            overlay = Perms.overlayEnabled(this),
            bgPopup = Perms.backgroundPopupState(this) == true,
            alarm = Perms.canScheduleExactAlarms(this@MainActivity),
            autoStart = Perms.autoStartState(this) == true,
            battery = Perms.batteryUnrestricted(this),
            tile = Perms.tileAdded(this),
        )
    }

    /**
     * 通知权限直接跳系统设置页，不调 requestPermissions。
     * 原因：权限一旦被用户勾选「不再询问」(USER_FIXED)，requestPermissions 会静默返回拒绝、
     * 不弹任何框，用户感受到的就是「点了没反应」。而 USER_FIXED 没有公开查询接口，
     * 无法区分「还没问过」和「问过被永久拒绝」。设置页是唯一保证有反馈的入口。
     */
    private fun requestNotification() {
        if (!Perms.openNotification(this)) {
            Toast.makeText(this, "无法打开设置页，请到系统设置里手动开启通知", Toast.LENGTH_LONG)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从后台回来不算「闲置了 5 分钟」，重新起算
        lastTouchAt = System.currentTimeMillis()
        refreshPerms()
        refreshPermsLater()
        syncAlarm()
        // 悬浮窗只在回到助手时收起，不跟随前台应用变化自动隐藏
        FloatingOverlay.hide(this)
    }

    /** 屏幕保护的计时基准；黑屏时碰一下立刻恢复界面 */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.actionMasked == MotionEvent.ACTION_DOWN) {
            lastTouchAt = System.currentTimeMillis()
            saverBlack.value = false
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 从系统设置页返回时 onResume 不一定触发，这里再补一次刷新 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            refreshPerms()
            refreshPermsLater()
        }
    }

    /**
     * 部分开关（尤其 MIUI 的电量详情 → 无限制）由系统异步落库，
     * onResume 当下读到的可能还是旧值，补两次延时刷新兜底。
     */
    private fun refreshPermsLater() {
        val decor = window.decorView
        decor.postDelayed({ refreshPerms() }, 700)
        decor.postDelayed({ refreshPerms() }, 2000)
    }
}
