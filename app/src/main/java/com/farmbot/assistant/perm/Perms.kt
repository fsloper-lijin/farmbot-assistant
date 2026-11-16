package com.farmbot.assistant.perm

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.farmbot.assistant.R
import com.farmbot.assistant.bot.KeepAliveTile

/**
 * 权限探测与跳转。
 * 前三项有可靠的系统 API 可查；MIUI 的「后台弹出界面 / 自启动」没有公开 API，
 * 只能跳到它自己的权限页，由用户手动确认后打勾，状态存在 SharedPreferences。
 */
object Perms {

    private const val PREFS = "perm"
    private const val KEY_MANUAL = "manual_bg_ok"
    private const val KEY_AUTOSTART = "manual_autostart_ok"
    private const val KEY_LOCK = "manual_lock_ok"
    private const val KEY_TILE = "tile_present"

    /**
     * 无障碍是否开启。
     *
     * 系统对该设置有两种等价写法，必须都认：
     *   完整写法 com.farmbot.assistant/com.farmbot.assistant.bot.BotService
     *   缩写写法 com.farmbot.assistant/.bot.BotService
     * 缩写写法里的前导点代表包名，要先展开成完整类名再比较，
     * 否则会把「已开启」误判成「未开启」（曾导致从游戏切回来就跳权限页的 bug）。
     */
    fun accessibilityEnabled(ctx: Context): Boolean {
        val pkg = ctx.packageName
        val serviceClass = com.farmbot.assistant.bot.BotService::class.java.name
        val raw = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return raw.split(':').any { entry -> expand(entry.trim(), pkg) == serviceClass }
    }

    private fun expand(entry: String, pkg: String): String {
        if (entry.isEmpty()) return ""
        val p = entry.substringBefore('/', "")
        val c = entry.substringAfter('/', "")
        if (p != pkg || c.isEmpty()) return ""
        return if (c.startsWith(".")) pkg + c else c
    }

    fun notificationsEnabled(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .areNotificationsEnabled()
    }.getOrDefault(false)

    fun overlayEnabled(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun manualConfirmed(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_MANUAL, false)

    fun setManualConfirmed(ctx: Context, ok: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_MANUAL, ok).apply()
    }

    fun autoStartConfirmed(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTOSTART, false)

    fun setAutoStartConfirmed(ctx: Context, ok: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTOSTART, ok).apply()
    }

    fun lockConfirmed(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_LOCK, false)

    fun setLockConfirmed(ctx: Context, ok: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_LOCK, ok).apply()
    }

    // ------------------------------------------------------------------
    // 可选增强权限：不开启也能进入助手，只影响"到点自动跑"的成功率
    // ------------------------------------------------------------------

    /**
     * 精确闹钟。菜到收获/浇水的时间点需要 AlarmManager.setExact 提醒用户，
     * 没有这个权限时只能用不精确的闹钟，可能延迟几分钟甚至被系统合并掉。
     * API 31 起是独立开关，31 以下没有这个概念，直接视为可用。
     */
    fun canScheduleExactAlarms(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return runCatching {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        }.getOrDefault(false)
    }

    fun openExactAlarm(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return tryAll(
            ctx,
            listOf(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${ctx.packageName}")
                ),
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM),
            )
        )
    }

    /**
     * MIUI/HyperOS「自启动」尽力探测：明确允许/禁止返回 true/false，探测不到返回 null。
     *
     * 自启动是小米私有能力，没有公开 API；MIUI 内部把它做成了一个私有 AppOps，
     * op 的名字和编号各版本都不一样，所以这里逐个试常见写法，全都查不到就返回 null，
     * 交给用户在界面上手动确认（不猜、不假装已授权）。
     */
    fun autoStartState(ctx: Context): Boolean? {
        val pkg = ctx.packageName
        val uid = ctx.applicationInfo.uid
        val aom = runCatching {
            ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        }.getOrNull() ?: return null
        for (op in listOf("auto_start", "AUTO_START", "miui_auto_start")) {
            val mode = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    aom.unsafeCheckOpNoThrow(op, uid, pkg)
                } else {
                    aom.checkOpNoThrow(op, uid, pkg)
                }
            }.getOrNull() ?: continue
            when (mode) {
                AppOpsManager.MODE_ALLOWED -> return true
                AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> return false
            }
        }
        return null
    }

    /** 打开 MIUI 自启动管理页；各版本 Activity 名不同，逐个尝试 */
    fun openAutoStart(ctx: Context): Boolean {
        val candidates = mutableListOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            ),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
                )
                putExtra("extra_pkgname", ctx.packageName)
            },
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${ctx.packageName}")
            ),
        )
        return tryAll(ctx, candidates)
    }

    /** 锁后台没有可跳转的独立页面（要用户在最近任务里长按卡片），这里退一步打开省电策略页 */
    fun openBatteryPolicy(ctx: Context): Boolean = tryAll(
        ctx,
        listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
        )
    )

    /**
     * 快捷开关磁贴是否已加入下拉面板。
     *
     * 首选官方回调：TileService.onTileAdded() 会在用户添加磁贴时被调用，
     * onTileRemoved() 在移除时调用，把结果写进 SharedPreferences 最可靠。
     *
     * 兜底再试读 Settings.Secure 的 sysui_qs_tiles（形如
     * "wifi,bt,custom(com.farmbot.assistant/.bot.KeepAliveTile)"），
     * 部分 ROM（实测 MIUI/HyperOS）不允许第三方读这个键，读到 null 就跳过。
     */
    fun tileAdded(ctx: Context): Boolean {
        if (prefs(ctx).getBoolean(KEY_TILE, false)) return true
        val full = KeepAliveTile::class.java.name
        val short = "${ctx.packageName}/.bot.KeepAliveTile"
        for (key in listOf("sysui_qs_tiles", "quick_settings_tiles")) {
            val raw = runCatching {
                Settings.Secure.getString(ctx.contentResolver, key)
            }.getOrNull() ?: continue
            if (raw.contains(full, ignoreCase = true)) return true
            if (raw.contains(short, ignoreCase = true)) return true
        }
        return false
    }

    fun setTilePresent(ctx: Context, present: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_TILE, present).apply()
    }

    /** 请求把「农场保活」磁贴加进快捷面板；API 33+ 会弹系统确认框，更低版本只能手动拖 */
    fun requestAddTile(ctx: Context, onResult: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onResult("当前系统不支持自动添加，请手动在快捷开关编辑页里添加「农场保活」")
            return
        }
        addTileApi33(ctx, onResult)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun addTileApi33(ctx: Context, onResult: (String) -> Unit) {
        val sbm = runCatching { ctx.getSystemService(StatusBarManager::class.java) }.getOrNull()
        if (sbm == null) {
            onResult("当前系统不支持自动添加磁贴，请手动添加")
            return
        }
        val comp = ComponentName(ctx.packageName, KeepAliveTile::class.java.name)
        val ok = runCatching {
            sbm.requestAddTileService(
                comp,
                "农场保活",
                Icon.createWithResource(ctx, R.drawable.ic_tile),
                ctx.mainExecutor
            ) { result ->
                // 0 = 已添加，1 = 本来就在，2 = 用户拒绝（常量是 SystemApi，这里用字面量）
                if (result == 0 || result == 1) setTilePresent(ctx, true)
                onResult(
                    when (result) {
                        0 -> "磁贴已添加，下拉面板里可以找到「农场保活」"
                        1 -> "磁贴已经在快捷面板里了"
                        2 -> "磁贴未添加，可稍后在编辑页里手动添加"
                        else -> "磁贴添加结果未知（$result）"
                    }
                )
            }
        }.isSuccess
        if (!ok) onResult("当前系统不支持自动添加磁贴，请手动添加")
    }

    fun openAccessibility(ctx: Context): Boolean =
        tryAll(ctx, listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))

    /**
     * 打开通知设置。不同 ROM 支持的入口不同，逐个尝试，全失败再退回应用详情页。
     * 注意：权限被「永久拒绝」时 requestPermissions 会静默失败，只能走这里。
     */
    fun openNotification(ctx: Context): Boolean {
        val list = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            list += Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            list += Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra("app_package", ctx.packageName)
                .putExtra("app_uid", ctx.applicationInfo.uid)
        }
        list += Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${ctx.packageName}")
        )
        return tryAll(ctx, list)
    }

    fun openOverlay(ctx: Context): Boolean = tryAll(
        ctx,
        listOf(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${ctx.packageName}")
            ),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${ctx.packageName}")
            ),
        )
    )

    /** 打开 MIUI 安全中心的权限编辑页；失败则退回系统应用详情页 */
    fun openMiuiPermissions(ctx: Context): Boolean {
        val candidates = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_pkgname", ctx.packageName)
            },
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
                )
                putExtra("extra_pkgname", ctx.packageName)
            },
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${ctx.packageName}")
            ),
        )
        return tryAll(ctx, candidates)
    }

    private fun tryAll(ctx: Context, intents: List<Intent>): Boolean {
        for (i in intents) {
            if (runCatching { launch(ctx, i) }.isSuccess) return true
        }
        return false
    }

    private fun launch(ctx: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }
}
