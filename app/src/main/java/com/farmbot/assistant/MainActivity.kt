package com.farmbot.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.farmbot.assistant.bot.BotService
import com.farmbot.assistant.bot.GameLauncher
import com.farmbot.assistant.data.Account
import com.farmbot.assistant.data.AccountStore
import com.farmbot.assistant.data.Farm
import com.farmbot.assistant.overlay.FloatingOverlay
import com.farmbot.assistant.perm.Perms
import com.farmbot.assistant.scan.Scanner
import com.farmbot.assistant.ui.AccountScreen
import com.farmbot.assistant.ui.PermissionScreen
import com.farmbot.assistant.ui.theme.FarmBotTheme

data class PermState(
    val acc: Boolean = false,
    val notif: Boolean = false,
    val overlay: Boolean = false,
    val manual: Boolean = false,
    /** 可选增强项：以下都不参与放行判断，只影响「到点自动跑」的成功率 */
    val alarm: Boolean = false,
    val autoStart: Boolean = false,
    /** 自启动是否被系统真实探测到；false 表示探测不到，状态以用户手动确认为准 */
    val autoStartKnown: Boolean = false,
    val lock: Boolean = false,
    val tile: Boolean = false,
) {
    /** 三项系统可检测的权限齐了才放行 */
    val ready: Boolean get() = acc && notif && overlay
}

class MainActivity : ComponentActivity() {

    private val perms = mutableStateOf(PermState())
    private var guideStage = 0
    private val scanResult = mutableStateOf<List<Farm>?>(null)
    private var scanAccountId: String? = null
    private lateinit var store: AccountStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AccountStore(this)
        val initial = store.load()
        val (savedAccountId, savedFarms) = Scanner.load(this)
        scanAccountId = savedAccountId
        scanResult.value = savedFarms.takeIf { it.isNotEmpty() }
        refreshPerms()

        setContent {
            FarmBotTheme {
                var accounts by remember { mutableStateOf(initial) }
                // 必备权限齐了之后，仍可从账号页点「权限」回到这里查看/调整增强项
                var showPerms by remember { mutableStateOf(false) }

                fun update(next: List<Account>) {
                    accounts = next
                    store.save(next)
                }

                val p = perms.value
                if (!p.ready || showPerms) {
                    PermissionScreen(
                        accessibility = p.acc,
                        notifications = p.notif,
                        overlay = p.overlay,
                        manual = p.manual,
                        alarm = p.alarm,
                        autoStart = p.autoStart,
                        autoStartKnown = p.autoStartKnown,
                        lock = p.lock,
                        tile = p.tile,
                        onAutoGrant = { startAutoGrant() },
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
                        onManualOpen = {
                            if (!Perms.openMiuiPermissions(this)) {
                                toast("无法打开权限设置页")
                            }
                        },
                        onManualToggle = { ok ->
                            Perms.setManualConfirmed(this, ok)
                            refreshPerms()
                        },
                        onAlarm = {
                            if (!Perms.openExactAlarm(this)) {
                                toast("无法打开精确闹钟设置页，请到应用权限里手动开启「闹钟和提醒」")
                            }
                        },
                        onAutoStart = {
                            if (!Perms.openAutoStart(this)) {
                                toast("无法打开自启动设置页，请到安全中心手动开启")
                            }
                        },
                        onAutoStartToggle = { ok ->
                            Perms.setAutoStartConfirmed(this, ok)
                            refreshPerms()
                        },
                        onLock = {
                            toast("已打开省电策略，建议选「无限制」；锁后台请到最近任务里长按农场助手卡片")
                            if (!Perms.openBatteryPolicy(this)) {
                                toast("无法打开省电策略页")
                            }
                        },
                        onLockToggle = { ok ->
                            Perms.setLockConfirmed(this, ok)
                            refreshPerms()
                        },
                        onTile = {
                            Perms.requestAddTile(this) { msg ->
                                toast(msg)
                                // 系统弹窗关闭后 Activity 未必重新 resume，这里主动刷一次
                                window.decorView.postDelayed({ refreshPerms() }, 400)
                            }
                        },
                        onEnter = { showPerms = false }
                    )
                } else {
                    val targetName = accounts.firstOrNull { it.id == scanAccountId }?.let { a ->
                        a.name.ifEmpty { "账号 ${accounts.indexOf(a) + 1}" }
                    } ?: ""

                    AccountScreen(
                        accounts = accounts,
                        accessibilityOn = p.acc,
                        scanResult = scanResult.value,
                        scanTarget = targetName,
                        onAdd = { update(accounts + Account()) },
                        onChange = { changed ->
                            update(accounts.map { if (it.id == changed.id) changed else it })
                        },
                        onRemove = { id -> update(accounts.filterNot { it.id == id }) },
                        onOpenAccessibility = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
                                !GameLauncher.launch(this) -> {
                                    toast("无法启动王者荣耀，请确认已安装")
                                }
                                else -> {
                                    FloatingOverlay.showDelayed(this, 1200)
                                    // 系统可能插入「启动应用」确认框，若游戏迟迟不在前台就提示
                                    window.decorView.postDelayed({
                                        if (BotService.foregroundPackage() != GameLauncher.PACKAGE) {
                                            toast("若弹出「启动应用」，请选择「始终允许」")
                                        }
                                    }, 2500)
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun startAutoGrant() {
        if (!perms.value.acc) {
            toast("请先开启无障碍服务")
            Perms.openAccessibility(this)
            return
        }
        val p = perms.value
        when {
            !p.notif -> {
                guideStage = 1
                toast("请打开「允许通知」开关，返回后会自动进入下一步")
                Perms.openNotification(this)
            }
            !p.overlay -> {
                guideStage = 2
                toast("请在列表中点「农场助手」，再打开「允许显示在其他应用的上层」")
                Perms.openOverlay(this)
            }
            else -> toast("权限已齐全")
        }
    }

    private fun advanceGuide() {
        when (guideStage) {
            1 -> if (perms.value.notif) {
                if (!perms.value.overlay) {
                    guideStage = 2
                    toast("通知已开启；请在列表中点「农场助手」，再打开悬浮窗开关")
                    window.decorView.postDelayed({ Perms.openOverlay(this) }, 600)
                } else {
                    guideStage = 0
                }
            }
            2 -> if (perms.value.overlay) {
                guideStage = 0
                toast("权限已齐全，可以进入账号管理")
            }
        }
    }

    private fun refreshPerms() {
        // 自启动：系统能探测到就用真实值，探测不到用用户手动确认的值
        val autoStartProbe = Perms.autoStartState(this)
        perms.value = PermState(
            // 设置项与「服务是否真的连着」取或关系：任一成立即视为开启，避免误判把用户挡在门外
            acc = Perms.accessibilityEnabled(this) || BotService.isReady,
            notif = Perms.notificationsEnabled(this),
            overlay = Perms.overlayEnabled(this),
            manual = Perms.manualConfirmed(this),
            alarm = Perms.canScheduleExactAlarms(this),
            autoStart = autoStartProbe ?: Perms.autoStartConfirmed(this),
            autoStartKnown = autoStartProbe != null,
            lock = Perms.lockConfirmed(this),
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
        if (guideStage != 0) advanceGuide()
        // 悬浮窗只在回到助手时收起，不跟随前台应用变化自动隐藏
        FloatingOverlay.hide(this)
    }

    /** 从系统设置页返回时 onResume 不一定触发，这里再补一次刷新 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            refreshPerms()
            if (guideStage != 0) advanceGuide()
        }
    }
}
