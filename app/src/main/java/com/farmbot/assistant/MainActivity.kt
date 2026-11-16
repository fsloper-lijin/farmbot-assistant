package com.farmbot.assistant

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.farmbot.assistant.alarm.SystemAlarm
import com.farmbot.assistant.bot.BotService
import com.farmbot.assistant.bot.AddAccountFlow
import com.farmbot.assistant.bot.FlowControl
import com.farmbot.assistant.bot.GameLauncher
import com.farmbot.assistant.bot.LoginFlow
import com.farmbot.assistant.core.IO
import com.farmbot.assistant.data.Account
import com.farmbot.assistant.data.AccountStore
import com.farmbot.assistant.data.Farm
import com.farmbot.assistant.data.LoginType
import com.farmbot.assistant.overlay.FloatingOverlay
import com.farmbot.assistant.perm.Perms
import com.farmbot.assistant.scan.Scanner
import com.farmbot.assistant.ui.AccountScreen
import com.farmbot.assistant.ui.PermissionScreen
import com.farmbot.assistant.ui.theme.FarmBotTheme
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
    private val scanResult = mutableStateOf<List<Farm>?>(null)
    private var scanAccountId: String? = null
    private lateinit var store: AccountStore
    /** 「已进入过账号管理」标志，落盘后启动直达账号页 */
    private val prefs by lazy { getSharedPreferences("farmbot", MODE_PRIVATE) }

    private companion object {
        const val KEY_ENTERED = "entered_account_management"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 平板：放开清单里的竖屏锁定，跟随设备旋转满屏显示
        // （不放开时系统会把竖屏 App 塞进横屏中间的一条窄窗口，两侧留黑边）
        if (resources.configuration.smallestScreenWidthDp >= 600) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        store = AccountStore(this)
        val initial = store.load()
        val (savedAccountId, savedFarms) = Scanner.load(this)
        scanAccountId = savedAccountId
        scanResult.value = savedFarms.takeIf { it.isNotEmpty() }
        refreshPerms()
        syncAlarm()

        setContent {
            FarmBotTheme {
                var accounts by remember { mutableStateOf(initial) }
                // 点过一次「进入账号管理」后就不再自动回到权限页；只有无障碍服务掉了才强制显示
                var showPerms by remember { mutableStateOf(!prefs.getBoolean(KEY_ENTERED, false)) }

                fun update(next: List<Account>) {
                    accounts = next
                    store.save(next)
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
                            if (!Perms.openAccessibility(this)) {
                                toast("无法打开无障碍设置页")
                            }
                        },
                        onNotification = { requestNotification() },
                        onOverlay = {
                            if (!Perms.openOverlay(this)) {
                                toast("无法打开悬浮窗设置页")
                            }
                        },
                        onBgPopup = {
                            if (!Perms.openMiuiPermissions(this)) {
                                toast("无法打开权限设置页")
                            }
                        },
                        onAlarm = {
                            if (!Perms.openExactAlarm(this)) {
                                toast("无法打开精确闹钟设置页，请到应用权限里手动开启「闹钟和提醒」")
                            }
                        },
                        onAlarmTest = {
                            if (!Perms.canScheduleExactAlarms(this)) {
                                toast("还没有精确闹钟权限，先开启再试")
                                Perms.openExactAlarm(this)
                            } else {
                                val at = SystemAlarm.setAfter(this, 60)
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
                            if (!SystemAlarm.openClock(this)) toast("无法打开时钟，请手动打开")
                        },
                        onAutoStart = {
                            if (!Perms.openAutoStart(this)) {
                                toast("无法打开自启动设置页，请到安全中心手动开启")
                            }
                        },
                        onBattery = {
                            if (!Perms.openBatteryPolicy(this)) {
                                toast("无法打开省电策略页")
                            }
                        },
                        onTile = {
                            Perms.requestAddTile(this) { msg ->
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
                    val targetName = accounts.firstOrNull { it.id == scanAccountId }?.let { a ->
                        a.name.ifEmpty { "账号 ${accounts.indexOf(a) + 1}" }
                    } ?: ""

                    AccountScreen(
                        accounts = accounts,
                        scanResult = scanResult.value,
                        scanTarget = targetName,
                        onAdd = { update(accounts + Account()) },
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
                        onOpenPerms = {
                            showPerms = true
                            refreshPerms()
                        },
                        onScan = { acc ->
                            scanAccountId = acc.id
                            scanResult.value = null
                            ensureNotificationPermission()
                            Scanner.scan(this, acc.id) { farms ->
                                scanResult.value = farms
                            }
                        },
                        onTestLogin = { acc ->
                            LoginFlow.start(this, acc) { msg -> toast(msg) }
                        },
                        onApplyScan = {
                            val id = scanAccountId
                            val found = scanResult.value
                            if (id != null && !found.isNullOrEmpty()) {
                                update(
                                    accounts.map { a ->
                                        if (a.id != id) a
                                        else a.copy(farms = mergeFarms(a.farms, found))
                                    }
                                )
                            }
                            scanResult.value = null
                            scanAccountId = null
                            Scanner.clear(this)
                        },
                        onDismissScan = {
                            scanResult.value = null
                            scanAccountId = null
                            Scanner.clear(this)
                        },
                        onStart = {
                            when {
                                !Perms.overlayEnabled(this) -> {
                                    toast("需要悬浮窗权限才能显示运行状态，请先开启")
                                    Perms.openOverlay(this)
                                }
                                accounts.isEmpty() -> {
                                    // 还没有账号：带用户走一遍登录 → 抓昵称/区服 → 自动建账号
                                    if (!FloatingOverlay.show(this, "正在启动游戏…")) {
                                        toast("悬浮窗未显示，请检查悬浮窗权限")
                                    }
                                    AddAccountFlow.start(
                                        this,
                                        status = { FloatingOverlay.setText(it) },
                                        done = { acc ->
                                            val filled = if (acc.name.isBlank())
                                                acc.copy(name = "账号 ${accounts.size + 1}") else acc
                                            update(accounts + filled)
                                            toast(
                                                if (filled.farms.isEmpty())
                                                    "已添加账号「${filled.name}」，没识别到区服，可稍后手动扫描"
                                                else
                                                    "已添加账号「${filled.name}」，同步 ${filled.farms.size} 个区服"
                                            )
                                            window.decorView.postDelayed(
                                                { FloatingOverlay.hide(this) }, 4000
                                            )
                                        },
                                        fail = { msg ->
                                            FloatingOverlay.setText(msg)
                                            toast(msg)
                                        }
                                    )
                                }
                                !GameLauncher.launch(this) -> {
                                    toast("无法启动王者荣耀，请确认已安装")
                                }
                                else -> {
                                    // 已有账号：按第一个启用账号的登录方式自动登录
                                    val target = accounts.firstOrNull { it.enabled } ?: accounts.first()
                                    // 其他 QQ 账号已经占用的名字：QQ 昵称、游戏昵称、该 QQ 号下的全部身份
                                    // 都要排除，否则新建账号会把已有的身份（如「OK」）当成新账号再登一遍
                                    val takenQq = accounts
                                        .filter { it.loginType == LoginType.QQ && it.id != target.id }
                                        .flatMap { listOf(it.qqNick, it.name) + it.identities }
                                        .filter { it.isNotBlank() }
                                        .toSet()
                                    if (!FloatingOverlay.show(this, "正在登录「${target.name}」…")) {
                                        toast("悬浮窗未显示，请检查悬浮窗权限")
                                    }
                                    LoginFlow.startAuto(
                                        this,
                                        target,
                                        status = { FloatingOverlay.setText(it) },
                                        done = { updated, msg ->
                                            // 登录后回写真实昵称与区服列表（账号↔区服绑定持久化）
                                            update(accounts.map { if (it.id == target.id) updated else it })
                                            FloatingOverlay.setText(msg)
                                            toast(msg)
                                            window.decorView.postDelayed(
                                                { FloatingOverlay.hide(this) }, 3000
                                            )
                                        },
                                        excludeQq = takenQq,
                                    )
                                }
                            }
                        },
                        running = FlowControl.running.value,
                        onStop = {
                            // 强制停止：作废所有在跑循环的 token，收起悬浮窗状态层
                            IO.log(this, "Stop", "用户强制停止自动化${FlowControl.label.value.let { if (it.isEmpty()) "" else "（$it）" }}")
                            FlowControl.cancel()
                            FloatingOverlay.setText("已强制停止")
                            FloatingOverlay.hide(this)
                            toast("已强制停止自动化")
                        }
                    )
                }
            }
        }
    }

    /** 与系统时钟里那条闹钟对齐（从后台回来时刷新倒计时） */
    private fun syncAlarm() {
        alarmAt.value = SystemAlarm.pendingAt(this)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
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
            alarm = Perms.canScheduleExactAlarms(this),
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

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) refreshPerms()
    }

    /** 扫描结果并入已有农场：区服名（忽略空白差异）已存在的跳过 */
    private fun mergeFarms(existing: List<Farm>, found: List<Farm>): List<Farm> {
        val norm = { s: String -> s.replace(Regex("\\s+"), "") }
        val have = existing.map { norm(it.server) }.toSet()
        return existing + found.filterNot { norm(it.server) in have }
    }

    override fun onResume() {
        super.onResume()
        refreshPerms()
        refreshPermsLater()
        syncAlarm()
        // 悬浮窗只在回到助手时收起，不跟随前台应用变化自动隐藏
        FloatingOverlay.hide(this)
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
