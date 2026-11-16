package com.farmbot.assistant.alarm

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 往系统时钟里加真实闹钟（而不是只在 App 内挂个 AlarmManager）。
 *
 * 用 `AlarmClock.ACTION_SET_ALARM` + `EXTRA_SKIP_UI=true`，实测小米 HyperOS 的时钟会静默
 * 在闹钟列表里建好一条，到点由系统闹钟准时响铃，不受 App 被后台限制/杀掉影响。
 *
 * 代价（实测）：
 *   1. 系统闹钟只有分钟粒度，只能落在整分上；
 *   2. MIUI 时钟完全忽略 `ACTION_DISMISS_ALARM`，App 无法取消，只能让用户去时钟里关掉；
 *      用 `deleteAfterUse` 的一次性闹钟响完会自动消失，不会越积越多。
 */
object SystemAlarm {

    private const val PREFS = "sys_alarm"
    private const val KEY_AT = "at"
    /** 是否在系统「下一条闹钟」里亲眼确认过这条闹钟——没确认前不轻易判它被删了 */
    private const val KEY_VERIFIED = "verified"

    /** 闹钟标签，用户和我们在时钟列表里都能一眼认出来 */
    const val LABEL = "农场助手测试"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun nextAlarmAt(ctx: Context): Long? = runCatching {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock?.triggerTime
    }.getOrNull()

    /**
     * 还没到点、且闹钟仍然存在的触发时间戳；否则返回 null。
     *
     * 时钟 App 的数据库第三方读不到，但 `AlarmManager.getNextAlarmClock()` 能拿到
     * 「下一条系统闹钟」的时间：
     *   - 等于我们设的时间 → 还在；
     *   - 已经晚于我们设的时间（或压根没有）→ 用户已在时钟里删掉。
     * 刚创建后的几百毫秒内系统还没登记，此时不能据此清状态，所以先用 KEY_VERIFIED 标记「确认过」，
     * 只有确认过之后的不匹配才判定为删除。
     */
    fun pendingAt(ctx: Context): Long? {
        val at = prefs(ctx).getLong(KEY_AT, 0L)
        if (at <= System.currentTimeMillis()) return null
        val next = nextAlarmAt(ctx)
        if (next == at) {
            prefs(ctx).edit().putBoolean(KEY_VERIFIED, true).apply()
            return at
        }
        if (!prefs(ctx).getBoolean(KEY_VERIFIED, false)) return at
        prefs(ctx).edit().remove(KEY_AT).remove(KEY_VERIFIED).apply()
        return null
    }

    /**
     * 在系统时钟里建一个至少 seconds 秒后的一次性闹钟（向上对齐到整分），
     * 返回实际触发时间戳；设备上没有可响应该 Intent 的时钟 App 时返回 null。
     */
    fun setAfter(ctx: Context, seconds: Long = 60L, label: String = LABEL): Long? {
        val target = System.currentTimeMillis() + seconds * 1000
        val cal = Calendar.getInstance().apply {
            timeInMillis = target
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis < target) cal.add(Calendar.MINUTE, 1)
        val at = cal.timeInMillis

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, cal.get(Calendar.HOUR_OF_DAY))
            putExtra(AlarmClock.EXTRA_MINUTES, cal.get(Calendar.MINUTE))
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // 不能用 resolveActivity 预判：Android 11+ 包可见性默认看不到时钟 App，
        // 会误判成「不支持」；直接 startActivity，没有接收方时抛 ActivityNotFoundException。
        return runCatching {
            ctx.startActivity(intent)
            prefs(ctx).edit().putLong(KEY_AT, at).putBoolean(KEY_VERIFIED, false).apply()
            verifyLater(ctx.applicationContext, at)
            at
        }.getOrNull()
    }

    /** 时钟 App 把闹钟登记成「下一条闹钟」有延迟，隔一会儿补确认两次 */
    private fun verifyLater(ctx: Context, at: Long) {
        val handler = Handler(Looper.getMainLooper())
        for (delay in longArrayOf(1200L, 3000L, 6000L)) {
            handler.postDelayed({
                val p = prefs(ctx)
                if (p.getLong(KEY_AT, 0L) != at) return@postDelayed
                if (nextAlarmAt(ctx) == at) p.edit().putBoolean(KEY_VERIFIED, true).apply()
            }, delay)
        }
    }

    /** 打开系统时钟的闹钟页，方便用户查看/关掉刚才创建的测试闹钟 */
    fun openClock(ctx: Context): Boolean = runCatching {
        ctx.startActivity(
            Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    fun hhmm(at: Long): String =
        SimpleDateFormat("HH:mm", Locale.CHINA).format(at)
}
