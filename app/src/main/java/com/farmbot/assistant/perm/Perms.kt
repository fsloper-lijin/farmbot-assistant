package com.farmbot.assistant.perm

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 权限探测与跳转。
 * 前三项有可靠的系统 API 可查；MIUI 的「后台弹出界面 / 自启动」没有公开 API，
 * 只能跳到它自己的权限页，由用户手动确认后打勾，状态存在 SharedPreferences。
 */
object Perms {

    private const val PREFS = "perm"
    private const val KEY_MANUAL = "manual_bg_ok"

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
