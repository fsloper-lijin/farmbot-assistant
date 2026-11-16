package com.farmbot.assistant.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 定时提醒的闹钟调度。
 *
 * 农场作物到收获/浇水的时间点是明确的，到点必须把人叫醒（或至少叫到通知栏），
 * 所以这里用 `setExactAndAllowWhileIdle`，并且带上 `RTC_WAKEUP` 让设备从休眠中醒来。
 *
 * 没有精确闹钟权限时不做静默降级：退回到不精确闹钟并如实返回结果，
 * 由上层告诉用户"可能延迟"，避免出现"设了但没响"的玄学问题。
 */
object ReminderAlarm {

    private const val PREFS = "alarm"
    private const val KEY_AT = "test_at"
    private const val REQ_TEST = 1001

    const val ACTION_TEST = "com.farmbot.assistant.ALARM_TEST"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 还没触发的闹钟时间戳；已触发或没设过返回 null */
    fun pendingAt(ctx: Context): Long? {
        val at = prefs(ctx).getLong(KEY_AT, 0L)
        return if (at > System.currentTimeMillis()) at else null
    }

    /**
     * 设定一个 seconds 秒后的测试提醒，返回触发时间戳。
     * exact=false 表示没有精确闹钟权限，只能用不精确闹钟（可能延迟数分钟）。
     */
    fun scheduleTest(ctx: Context, seconds: Long = 60L): Pair<Long, Boolean> {
        val at = System.currentTimeMillis() + seconds * 1000
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        val pi = pendingIntent(ctx)
        // 同一个 requestCode，重新设一次就会覆盖之前那个
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        prefs(ctx).edit().putLong(KEY_AT, at).apply()
        return at to exact
    }

    fun cancelTest(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(ctx))
        clear(ctx)
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY_AT).apply()
    }

    private fun pendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_TEST)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, REQ_TEST, intent, flags)
    }
}
