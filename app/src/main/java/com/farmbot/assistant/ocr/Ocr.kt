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
/**
 * 屏幕区域，单位是比例（0~1），不写死像素 —— 手机 2400×1080 与平板 2880×1800 通用。
 * 只给「识别范围」用；点击坐标仍然取自 OCR 文字框中心。
 */
data class Region(val l: Float, val t: Float, val r: Float, val b: Float) {
    fun rectOf(w: Int, h: Int): Rect = Rect(
        (w * l).toInt().coerceIn(0, w),
        (h * t).toInt().coerceIn(0, h),
        (w * r).toInt().coerceIn(0, w),
        (h * b).toInt().coerceIn(0, h),
    )
}

object Ocr {

    private val client by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    data class ScanLine(val text: String, val box: Rect)

    /**
     * 区服行锚点：行内出现「手Q/微信」即视为区服行，**不锚定行首**。
     * 「我的服务器」里上次登录的区服前面带 ◆ 标记（如「◆微信王者抢先服」），
     * 锚定 ^(手Q|微信) 会把这一行整行丢掉 —— 抢先服没有区号，正是靠这条匹配。
     */
    private val SERVER = Regex("(手Q|微信)")
    /** 行首装饰符号（◆ 上次登录标记、项目符号等），入库前去掉 */
    private val LEADING_JUNK = Regex("^[◆◇•·▪◦★☆＊*\\-—~\\s]+")
    private val LEVEL = Regex("Lv\\.?\\s*(\\d+)")
    private val ROLE = Regex("Lv\\.?\\s*\\d+\\s*(.*)")

    /**
     * 识别整屏（region=null）或只识别 [region] 划出的那一块。
     * 裁剪时返回的文字框已经加回区域偏移，坐标始终是**整屏**坐标，
     * 调用方可以直接拿去点，不需要自己做换算。
     */
    fun recognize(
        bmp: Bitmap,
        scale: Float = 1f,
        region: Region? = null,
        cb: (List<ScanLine>) -> Unit,
    ) {
        val r = region?.rectOf(bmp.width, bmp.height)
        val crop = if (r != null && r.width() > 16 && r.height() > 16) {
            runCatching { Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height()) }.getOrNull()
        } else null
        val base = crop ?: bmp
        val offX = if (crop != null) r!!.left else 0
        val offY = if (crop != null) r!!.top else 0
        val src = if (scale != 1f) Bitmap.createScaledBitmap(
            base,
            (base.width * scale).toInt(),
            (base.height * scale).toInt(),
            true
        ) else base

        fun release() {
            if (src !== base && src !== bmp) src.recycle()
            if (base !== bmp) base.recycle()
        }

        val image = InputImage.fromBitmap(src, 0)
        client.process(image)
            .addOnSuccessListener { result ->
                val lines = ArrayList<ScanLine>()
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        lines.add(ScanLine(line.text, mapBack(box, offX, offY, scale)))
                    }
                }
                release()
                cb(lines)
            }
            .addOnFailureListener {
                release()
                cb(emptyList())
            }
    }

    /** 裁剪/缩放后的文字框 → 整屏坐标 */
    private fun mapBack(b: Rect, offX: Int, offY: Int, scale: Float): Rect =
        if (offX == 0 && offY == 0 && scale == 1f) b
        else Rect(
            offX + (b.left / scale).toInt(),
            offY + (b.top / scale).toInt(),
            offX + (b.right / scale).toInt(),
            offY + (b.bottom / scale).toInt(),
        )

    fun toFarms(lines: List<ScanLine>): List<Farm> {
        val servers = lines.filter { SERVER.containsMatchIn(it.text.trim()) }
        val out = LinkedHashMap<String, Farm>()
        for (s in servers) {
            val below = partnerOf(s, lines) ?: continue
            val parsed = parseLevelRole(below.text) ?: continue
            val server = serverName(s.text)
            out.putIfAbsent(
                server,
                Farm(server = server, role = parsed.second, level = parsed.first)
            )
        }
        return out.values.toList()
    }

    /** 区服名规范化：压空白 + 去掉行首 ◆ 之类的装饰符号 */
    private fun serverName(raw: String): String =
        raw.trim().replace(Regex("\\s+"), " ").replace(LEADING_JUNK, "").trim()

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
        return if (SERVER.containsMatchIn(t)) serverName(t) else null
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
