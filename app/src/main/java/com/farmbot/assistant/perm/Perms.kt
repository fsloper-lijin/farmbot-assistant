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
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.farmbot.assistant.R
import com.farmbot.assistant.bot.KeepAliveTile

/**
 * 权限探测与跳转。
 * 前三项有可靠的系统 API 可查；MIUI 的「自启动 / 后台弹出界面」是私有 AppOps，
 * 通过反射调用 framework 的 checkOpNoThrow(int, int, String) 读取真实状态。
 */
object Perms {

    private const val PREFS = "perm"
    private const val KEY_TILE = "tile_present"
    private const val KEY_ACC_BOUND = "acc_bound"

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

    /** 服务真正连上过（BotService.isReady 为 true）时落一个标记，用于后面区分卡死 */
    fun markAccessibilityBound(ctx: Context) {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_ACC_BOUND, false)) p.edit().putBoolean(KEY_ACC_BOUND, true).apply()
    }

    private fun accessibilityEverBound(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ACC_BOUND, false)

    /**
     * HyperOS 的「卡死」状态：设置里仍写着已开启，系统却再也不 bind 服务。
     * 实测由小爱同学（com.miui.voiceassist）在启动时调用 closeAccessibility 关闭，
     * 之后重开开关 / 重启应用都无效，只有重启设备能恢复。
     * 判断依赖「曾经连上过」的标记，避免刚冷启动还没连上时误报。
     */
    fun accessibilityStuck(ctx: Context, bound: Boolean): Boolean =
        !bound && accessibilityEverBound(ctx) && accessibilityEnabled(ctx)

    /**
     * 无障碍不可用时给用户的一句真实原因。
     * 卡死状态和「没开授权」的处置办法完全不同：前者只能重启设备。
     */
    fun accessibilityReason(ctx: Context): String =
        if (accessibilityStuck(ctx, bound = false))
            "无障碍服务被系统关掉了（设置里仍显示已开启），需重启设备后再试"
        else "无障碍服务未连接"

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

    /**
     * 读取 MIUI 私有 AppOps 的模式。小米把这些权限做成了私有 op 编号（如 10053、10021），
     * 公开 API 只认字符串 op 名，查不到，所以反射 framework 里的
     * `checkOpNoThrow(int op, int uid, String packageName)`（@UnsupportedAppUsage 仍在）。
     * 返回 null = 这台机器上取不到该 op，界面按未开启处理。
     */
    private fun miuiOpState(ctx: Context, vararg ops: Int): Boolean? {
        if (!Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) return null
        val aom = runCatching {
            ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        }.getOrNull() ?: return null
        val method = runCatching {
            AppOpsManager::class.java.getDeclaredMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            ).apply { isAccessible = true }
        }.getOrNull() ?: return null
        for (op in ops) {
            val mode = runCatching {
                method.invoke(aom, op, ctx.applicationInfo.uid, ctx.packageName) as Int
            }.getOrNull() ?: continue
            if (mode == AppOpsManager.MODE_DEFAULT) continue
            return mode == AppOpsManager.MODE_ALLOWED
        }
        return null
    }

    /**
     * 自启动。实测 HyperOS：安全中心里的自启动开关与 op 10053 同步（10008 一起变），
     * 两个都试，取到哪个算哪个。
     */
    fun autoStartState(ctx: Context): Boolean? = miuiOpState(ctx, 10053, 10008)

    /** 后台弹出界面（允许从后台把 Activity 顶到前台），实测对应 op 10021 */
    fun backgroundPopupState(ctx: Context): Boolean? = miuiOpState(ctx, 10021)

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

    /**
     * 省电策略是否「无限制」——对应系统的电池优化白名单，MIUI 的「无限制」就是它。
     * 最近任务里的长按锁定没有查询接口，这里只反映能查到的部分。
     */
    fun batteryUnrestricted(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)
    }.getOrDefault(false)

    /** 弹系统「是否允许后台耗电」对话框；用户选允许后即进入白名单 */
    fun openBatteryPolicy(ctx: Context): Boolean = tryAll(
        ctx,
        listOf(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${ctx.packageName}")
            ),
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
                // 各家 ROM 的 result code 不可信：HyperOS 实测添加成功后也回调 2（AOSP 里 2 = 未添加），
                // 而且磁贴落库比回调晚，所以等 600ms 后按下拉面板里的真实状态下结论。
                Handler(Looper.getMainLooper()).postDelayed({
                    val added = tileAdded(ctx)
                    if (added) setTilePresent(ctx, true)
                    onResult(
                        when {
                            added -> "磁贴已添加，下拉面板里可以找到「农场保活」"
                            result == 0 || result == 1 -> "系统已受理，稍后可在下拉面板里确认「农场保活」"
                            else -> "磁贴未添加，可在下拉面板的编辑页里手动添加"
                        }
                    )
                }, 600)
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
