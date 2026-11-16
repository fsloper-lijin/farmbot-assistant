package com.farmbot.assistant.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.farmbot.assistant.data.Farm

/**
 * 识别王者荣耀「选择服务器 → 我的服务器」列表。
 *
 * 列表每格渲染为两行：
 *   手Q549区 蔬乡物语
 *   ( Lv.8  妈小3)
 *
 * 配对策略不依赖固定坐标：以「以 手Q/微信 开头」的行作为区服锚点，
 * 再找其正下方、水平方向有重叠、且含 "Lv." 的最近一行作为角色信息。
 * 这样能自动排除右上角「上次登录」提示、左侧页签等干扰文本。
 */
object Ocr {

    private val client by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    data class ScanLine(val text: String, val box: Rect)

    private val SERVER = Regex("^(手Q|微信)")
    private val LEVEL = Regex("Lv\\.?\\s*(\\d+)")
    private val ROLE = Regex("Lv\\.?\\s*\\d+\\s*(.*)")

    fun recognize(bmp: Bitmap, scale: Float = 1f, cb: (List<ScanLine>) -> Unit) {
        val src = if (scale != 1f) {
            Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).toInt(),
                (bmp.height * scale).toInt(),
                true
            )
        } else bmp
        val image = InputImage.fromBitmap(src, 0)
        client.process(image)
            .addOnSuccessListener { result ->
                val lines = ArrayList<ScanLine>()
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        lines.add(ScanLine(line.text, box))
                    }
                }
                if (src !== bmp) src.recycle()
                cb(lines)
            }
            .addOnFailureListener {
                if (src !== bmp) src.recycle()
                cb(emptyList())
            }
    }

    fun toFarms(lines: List<ScanLine>): List<Farm> {
        val servers = lines.filter { SERVER.containsMatchIn(it.text.trim()) }
        val out = LinkedHashMap<String, Farm>()
        for (s in servers) {
            val below = partnerOf(s, lines) ?: continue
            val parsed = parseLevelRole(below.text) ?: continue
            val server = s.text.trim().replace(Regex("\\s+"), " ")
            out.putIfAbsent(
                server,
                Farm(server = server, role = parsed.second, level = parsed.first)
            )
        }
        return out.values.toList()
    }

    /** 有等级行、但上方找不到区服名称行的孤立项 —— 需要局部放大补识别 */
    fun orphans(lines: List<ScanLine>): List<ScanLine> {
        val servers = lines.filter { SERVER.containsMatchIn(it.text.trim()) }
        return lines.filter { lv ->
            parseLevelRole(lv.text) != null &&
                servers.none { s ->
                    s.box.bottom <= lv.box.top + 12 &&
                        lv.box.top - s.box.bottom < 90 &&
                        overlapX(s.box, lv.box)
                }
        }
    }

    fun parseLevelRole(text: String): Pair<Int, String>? {
        val lv = LEVEL.find(text) ?: return null
        val raw = ROLE.find(text)?.groupValues?.get(1)
            ?.replace("(", "")?.replace(")", "")
            ?.replace("（", "")?.replace("）", "")
            ?.trim()
            ?: ""
        // 等级与角色名之间夹着段位图标，OCR 常把图标误读成 1 个字符，去掉它
        val role = if (raw.length >= 3) raw.substring(1).trim() else raw
        return (lv.groupValues[1].toIntOrNull() ?: 0) to role
    }

    fun nameOf(line: String): String? {
        val t = line.trim()
        return if (SERVER.containsMatchIn(t)) t.replace(Regex("\\s+"), " ") else null
    }

    private fun partnerOf(s: ScanLine, lines: List<ScanLine>): ScanLine? =
        lines.asSequence()
            .filter { it !== s }
            .filter { it.box.top >= s.box.bottom - 12 }
            .filter { it.box.top - s.box.bottom < 90 }
            .filter { overlapX(it.box, s.box) }
            .minByOrNull { it.box.top }

    private fun overlapX(a: Rect, b: Rect): Boolean {
        val lo = maxOf(a.left, b.left)
        val hi = minOf(a.right, b.right)
        return hi - lo > minOf(a.width(), b.width()) / 2
    }
}
