package com.farmbot.assistant.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.farmbot.assistant.MainActivity
import com.farmbot.assistant.bot.BotService
import com.farmbot.assistant.bot.FlowControl
import com.farmbot.assistant.bot.GameLauncher
import com.farmbot.assistant.core.IO
import com.farmbot.assistant.data.Farm
import com.farmbot.assistant.ocr.Ocr
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 扫描「选择服务器 → 我的服务器」列表。
 *
 * 流程：启动王者荣耀 → 轮询确认游戏已到前台 → 无障碍截屏 → ML Kit OCR → 解析。
 * 前置条件：用户已在游戏内打开换区面板（该面板切后台不会消失）。
 */
object Scanner {

    private const val GAME = "com.tencent.tmgp.sgame"
    private const val CHANNEL = "scan"
    private const val NOTIFY_ID = 1001
    private const val TAG = "Scan"

    private val main = Handler(Looper.getMainLooper())
    /** 本次扫描的取消令牌 */
    private var token = 0

    private fun file(ctx: Context): File = File(ctx.filesDir, "last_scan.json")

    fun scan(ctx: Context, accountId: String?, onDone: (List<Farm>) -> Unit) {
        if (FlowControl.running.value) {
            IO.log(ctx, TAG, "有自动化正在执行，本次扫描跳过")
            onDone(emptyList())
            return
        }
        val app = ctx.applicationContext
        token = FlowControl.begin("扫描区服")
        GameLauncher.launch(ctx)
        awaitGameAndCapture(app, 0, accountId, onDone)
    }

    /** 每 500ms 检查一次前台包名，确认游戏真的起来了再截图 */
    private fun awaitGameAndCapture(
        ctx: Context,
        attempt: Int,
        accountId: String?,
        onDone: (List<Farm>) -> Unit,
    ) {
        if (!FlowControl.alive(token)) {
            FlowControl.end(token)
            return
        }
        if (attempt > 24) {
            IO.log(ctx, TAG, "等待游戏前台超时，放弃")
            FlowControl.end(token)
            onDone(emptyList())
            return
        }
        main.postDelayed({
            val fg = BotService.foregroundPackage()
            if (fg != GAME) {
                if (attempt % 4 == 0) IO.log(ctx, TAG, "等待中 fg=$fg attempt=$attempt")
                awaitGameAndCapture(ctx, attempt + 1, accountId, onDone)
                return@postDelayed
            }
            main.postDelayed({ captureAndOcr(ctx, accountId, onDone) }, 1200)
        }, 500)
    }

    private fun captureAndOcr(
        ctx: Context,
        accountId: String?,
        onDone: (List<Farm>) -> Unit,
    ) {
        if (!FlowControl.alive(token)) {
            FlowControl.end(token)
            return
        }
        BotService.capture { bmp ->
            if (bmp == null) {
                IO.log(ctx, TAG, "截屏失败")
                FlowControl.end(token)
                onDone(emptyList())
                return@capture
            }
            IO.savePng(bmp, File(IO.dir(ctx, "shots"), "scan_${System.currentTimeMillis()}.png"))
            Ocr.recognize(bmp) { lines ->
                val farms = Ocr.toFarms(lines).toMutableList()
                val orphans = Ocr.orphans(lines)
                IO.log(ctx, TAG, "整屏识别 lines=${lines.size} farms=${farms.size} 待补=${orphans.size}")
                lines.take(30).forEach { IO.log(ctx, TAG, "  线 ${it.box.left},${it.box.top} :: ${it.text}") }
                recoverAll(ctx, bmp, orphans, farms) { all ->
                    save(ctx, accountId, all)
                    notify(ctx, all.size)
                    FlowControl.end(token)
                    onDone(all)
                }
            }
        }
    }

    private fun recoverAll(
        ctx: Context,
        bmp: android.graphics.Bitmap,
        pending: List<Ocr.ScanLine>,
        acc: MutableList<Farm>,
        done: (List<Farm>) -> Unit,
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

    /** 只对缺名称的格子做局部裁剪 + 放大识别，避免整屏放大导致字符误识 */
    private fun recoverOne(
        ctx: Context,
        bmp: android.graphics.Bitmap,
        orphan: Ocr.ScanLine,
        done: (Farm?) -> Unit,
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
                done(Farm(server = name, role = parsed.second, level = parsed.first))
            }
        }
    }

    fun save(ctx: Context, accountId: String?, farms: List<Farm>) {
        runCatching {
            val arr = JSONArray()
            farms.forEach { f ->
                arr.put(JSONObject().apply {
                    put("server", f.server)
                    put("role", f.role)
                    put("level", f.level)
                })
            }
            file(ctx).writeText(
                JSONObject().apply {
                    put("accountId", accountId ?: "")
                    put("farms", arr)
                }.toString()
            )
        }
    }

    fun load(ctx: Context): Pair<String?, List<Farm>> = runCatching {
        val f = file(ctx)
        if (!f.exists()) return@runCatching (null to emptyList<Farm>())
        val o = JSONObject(f.readText())
        val arr = o.optJSONArray("farms") ?: JSONArray()
        val list = (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            Farm(
                server = e.optString("server"),
                role = e.optString("role"),
                level = e.optInt("level"),
            )
        }
        o.optString("accountId").takeIf { it.isNotEmpty() } to list
    }.getOrElse { null to emptyList() }

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }

    private fun notify(ctx: Context, count: Int) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "扫描结果", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val pi = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n: Notification = Notification.Builder(ctx, CHANNEL)
                .setContentTitle(
                    if (count > 0) "扫描完成：$count 个区服" else "扫描完成：未识别到区服"
                )
                .setContentText(
                    if (count > 0) "点击回到助手查看并加入账号"
                    else "请确认游戏已停在「选择服务器」面板"
                )
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFY_ID, n)
        }
    }
}
