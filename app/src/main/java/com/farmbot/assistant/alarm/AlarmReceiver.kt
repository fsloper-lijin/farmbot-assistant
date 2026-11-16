package com.farmbot.assistant.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.farmbot.assistant.MainActivity
import com.farmbot.assistant.R
import com.farmbot.assistant.core.IO
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 闹钟到点的接收端。
 *
 * 要做两件事，缺一不可：
 *   1. 发一条高优先级、带响铃与震动的通知 —— 锁屏也能看见，这是提醒的兜底；
 *   2. 尝试把助手拉到前台 —— 屏幕亮着且没锁屏时可以直接接着自动化。
 *
 * 第 2 步在 Android 10+ 属于"后台启动 Activity"，默认被拦；
 * 本应用持有悬浮窗权限（SYSTEM_ALERT_WINDOW），属于系统允许的豁免情形，所以能拉起来。
 * 拉不起来也没关系，通知还在，不会静默失败。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ReminderAlarm.ACTION_TEST) return
        ReminderAlarm.clear(ctx)

        val now = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
        IO.log(ctx, TAG, "alarm fired at $now")

        wakeScreen(ctx)
        notify(ctx, now)
        bringToFront(ctx)
    }

    /**
     * 点亮屏幕。
     * 实测 MIUI/HyperOS 会拦掉 full-screen intent，锁屏状态下闹钟响了屏幕也不亮，
     * 用户根本看不到提醒。这里直接抓一个 SCREEN_BRIGHT + ACQUIRE_CAUSES_WAKEUP 的
     * WakeLock 把屏幕点亮（闹钟类应用的常规做法），亮屏后通知就展示在锁屏上，
     * 用户解锁即可继续。10 秒后自动释放，不会长期占着电量。
     */
    private fun wakeScreen(ctx: Context) {
        runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "farmbot:alarm"
            )
            wl.acquire(10_000L)
            IO.log(ctx, TAG, "screen wake lock acquired")
        }.onFailure { IO.log(ctx, TAG, "wake failed: ${it.message}") }
    }

    private fun notify(ctx: Context, time: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(ctx)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            IO.log(ctx, TAG, "canUseFullScreenIntent=${nm.canUseFullScreenIntent()}")
        }
        val open = openIntent(ctx)

        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("定时提醒")
            .setContentText("闹钟已触发 · $time，点这里回到助手")
            .setStyle(NotificationCompat.BigTextStyle().bigText("闹钟已触发 · $time。农场作物到点了，解锁手机即可继续自动化。"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            // 全屏通知：系统级通道，不受「后台启动 Activity」限制，
            // 锁屏/桌面时可以直接把 Activity 顶上来（闹钟类应用的标准做法）
            .setFullScreenIntent(open, true)
            .build()
        runCatching { nm.notify(NOTIFY_ID, n) }
            .onFailure { IO.log(ctx, TAG, "notify failed: ${it.message}") }
    }

    /** 屏幕亮着且未锁屏时直接把助手拉到前台；被系统拦下就算了，不抛 */
    private fun bringToFront(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            )
            IO.log(ctx, TAG, "activity launch requested")
        }.onFailure { IO.log(ctx, TAG, "activity launch blocked: ${it.message}") }
    }

    private fun openIntent(ctx: Context): PendingIntent {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )
    }

    companion object {
        private const val TAG = "Alarm"
        const val CHANNEL_ID = "reminder"
        private const val NOTIFY_ID = 2001

        /** 通知渠道只需建一次，重复调用无副作用 */
        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val ch = NotificationChannel(CHANNEL_ID, "定时提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "农场作物到点时的提醒，会响铃并震动"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableLights(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            runCatching { nm.createNotificationChannel(ch) }
        }
    }
}
