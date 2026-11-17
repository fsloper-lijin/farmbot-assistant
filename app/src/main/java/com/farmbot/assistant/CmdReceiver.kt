package com.farmbot.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.farmbot.assistant.bot.BotService
import com.farmbot.assistant.core.IO
import com.farmbot.assistant.core.Img
import com.farmbot.assistant.ocr.Ocr
import java.io.File

/**
 * 开发期远程指令通道（仅调试用）。
 * 游戏保持在前台，通过 adb broadcast 触发截图 / 探测 / 手势，避免来回切 App。
 *
 * adb shell am broadcast -n com.farmbot.assistant/.CmdReceiver -a com.farmbot.assistant.CMD_CAPTURE --es t fbot-9f3a
 */
class CmdReceiver : BroadcastReceiver() {

    companion object {
        private const val TOKEN = "fbot-9f3a"
        private const val TAG = "Cmd"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.getStringExtra("t") != TOKEN) {
            IO.log(ctx, TAG, "拒绝：token 不匹配 action=${intent.action}")
            return
        }
        val pending = goAsync()
        try {
            when (intent.action) {
                "com.farmbot.assistant.CMD_CAPTURE" -> shoot(ctx) { bmp, f ->
                    IO.log(ctx, TAG, "capture ${bmp.width}x${bmp.height} -> ${f.absolutePath}")
                }

                "com.farmbot.assistant.CMD_GRID" -> shoot(ctx, prefix = "grid") { bmp, f ->
                    val cols = intent.getIntExtra("cols", 12)
                    val rows = intent.getIntExtra("rows", 24)
                    val txt = File(f.parentFile, f.nameWithoutExtension + ".txt")
                    txt.writeText(Img.probeGrid(bmp, cols, rows))
                    IO.log(ctx, TAG, "grid ${cols}x${rows} -> ${txt.absolutePath}")
                }

                "com.farmbot.assistant.CMD_TAP" -> {
                    val x = intent.getIntExtra("x", -1)
                    val y = intent.getIntExtra("y", -1)
                    val d = intent.getLongExtra("d", 60L)
                    if (x >= 0 && y >= 0) {
                        BotService.tap(x, y, d) { ok -> IO.log(ctx, TAG, "tap($x,$y) ok=$ok") }
                    }
                }

                "com.farmbot.assistant.CMD_SWIPE" -> {
                    BotService.swipe(
                        intent.getIntExtra("x1", 0), intent.getIntExtra("y1", 0),
                        intent.getIntExtra("x2", 0), intent.getIntExtra("y2", 0),
                        intent.getLongExtra("d", 300L)
                    ) { ok -> IO.log(ctx, TAG, "swipe ok=$ok") }
                }

                "com.farmbot.assistant.CMD_BACK" -> BotService.back()

                "com.farmbot.assistant.CMD_PING" -> IO.log(ctx, TAG, "pong ready=${BotService.isReady}")

                "com.farmbot.assistant.CMD_NODES" -> {
                    val txt = File(IO.dir(ctx, "debug"), "nodes.txt")
                    txt.writeText(com.farmbot.assistant.bot.Nodes.allWindowsDump())
                    IO.log(ctx, TAG, "nodes -> ${txt.absolutePath}")
                }

                "com.farmbot.assistant.CMD_SCAN" -> {
                    BotService.capture { bmp ->
                        if (bmp == null) {
                            IO.log(ctx, TAG, "scan: 截屏失败")
                            return@capture
                        }
                        val shot = File(IO.dir(ctx, "shots"), "scan_${System.currentTimeMillis()}.png")
                        IO.savePng(bmp, shot)
                        Ocr.recognize(bmp) { lines ->
                            lines.forEach { IO.log(ctx, TAG, "ocr ${it.box.left},${it.box.top} :: ${it.text}") }
                            val farms = Ocr.toFarms(lines)
                            val arr = org.json.JSONArray()
                            farms.forEach { f ->
                                arr.put(org.json.JSONObject().apply {
                                    put("server", f.server)
                                    put("role", f.role)
                                    put("level", f.level)
                                })
                            }
                            File(IO.dir(ctx, "debug"), "last_scan.json").writeText(arr.toString(2))
                            IO.log(ctx, TAG, "scan -> ${farms.size} 个区服")
                            farms.forEach { IO.log(ctx, TAG, "  farm: ${it.server} / ${it.role} / Lv.${it.level}") }
                        }
                    }
                }

                else -> IO.log(ctx, TAG, "未知指令 ${intent.action}")
            }
        } finally {
            pending.finish()
        }
    }

    private fun shoot(ctx: Context, prefix: String = "cap", then: (android.graphics.Bitmap, File) -> Unit) {
        BotService.capture { bmp ->
            if (bmp == null) {
                IO.log(ctx, TAG, "截屏失败：服务未连接或系统限流")
                return@capture
            }
            val f = File(IO.dir(ctx, "shots"), "${prefix}_${System.currentTimeMillis()}.png")
            IO.savePng(bmp, f)
            then(bmp, f)
        }
    }
}
