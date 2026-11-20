package com.farmbot.assistant.bot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.farmbot.assistant.R
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc

/**
 * 用 OpenCV 模板匹配定位农场里没有文字的图标按钮。
 * 图标是游戏固定 UI，位置比 OCR 文字框稳定，也不像写死比例那样换分辨率就偏。
 */
object FarmButtonLocator {
    /** ch = 内容区高（裁掉黑边后），落点相对模板的比例偏移须按它算而不是整屏高。 */
    data class Hit(val x: Int, val y: Int, val score: Double, val ch: Int = 0)

    /** 一键务农设置面板里的一行勾选框：center 坐标 + 是否已勾 */
    data class Check(val x: Int, val y: Int, val checked: Boolean, val score: Double)

    private var action: Bitmap? = null
    private var back: Bitmap? = null
    private var gear: Bitmap? = null
    private var boxOff: Bitmap? = null
    private var boxOn: Bitmap? = null

    fun init(ctx: Context) {
        OpenCVLoader.initLocal()
        action = BitmapFactory.decodeResource(ctx.resources, R.drawable.farm_action_template)
        back = BitmapFactory.decodeResource(ctx.resources, R.drawable.farm_back_template)
        gear = BitmapFactory.decodeResource(ctx.resources, R.drawable.lobby_gear_template)
        boxOff = BitmapFactory.decodeResource(ctx.resources, R.drawable.box_off_template)
        boxOn = BitmapFactory.decodeResource(ctx.resources, R.drawable.box_on_template)
    }

    /** 「一键务农」圆钮：位于屏幕右中，命中框中心偏右上，点击点要往左下方挪（见 FarmWaterFlow）。 */
    fun findAction(bmp: Bitmap): Hit? =
        matchCrop(bmp, action, 0.75, 0.065f) { b, x, y ->
            x > b.width * 0.45f && y > b.height * 0.3f && y < b.height * 0.8f
        }

    /** 游戏大厅右上角设置齿轮：只在右上角一小片区域里找，避开面板里同款齿轮。 */
    fun findGear(bmp: Bitmap): Hit? =
        matchCrop(bmp, gear, 0.7, 0.044f) { b, x, y ->
            x > b.width * 0.8f && y < b.height * 0.15f
        }

    /** 农场左上角 D 形返回箭头：只在上方 1/4 屏、左侧 1/3 屏里找，避免误命中别的图标。 */
    fun findBack(bmp: Bitmap): Hit? =
        matchCrop(bmp, back, 0.7, 0.047f) { b, x, y ->
            x < b.width * 0.33f && y < b.height * 0.25f
        }

    /**
     * 一键务农设置面板的三个勾选框，按 y 从上到下返回（浇水 / 收获 / 种植）。
     * 不足三个或行距不成规律时返回 null —— 农场里别处的金色方块会误匹配，
     * 只有三行凑齐且等距才算找对了面板。
     */
    fun findChecks(bmp: Bitmap): List<Check>? {
        val (b, top) = contentCrop(bmp)
        val accept = { x: Int, y: Int ->
            x > b.width * 0.55f && y > b.height * 0.2f && y < b.height * 0.95f
        }
        val found = ArrayList<Check>()
        for (h in topMatches(b, boxOff, 0.7, BOX_H, 3, accept)) {
            found += Check(h.x, h.y + top, false, h.score)
        }
        for (h in topMatches(b, boxOn, 0.7, BOX_H, 3, accept)) {
            found += Check(h.x, h.y + top, true, h.score)
        }
        if (found.size < 3) return null
        found.sortBy { it.y }
        // 三行勾选框共享同一列 x；面板卡片边缘的金色角也会高分误命中，偏离该列的一律丢掉
        val medianX = found.map { it.x }.sorted()[found.size / 2]
        val col = found.filter { kotlin.math.abs(it.x - medianX) <= b.width * 0.03f }
        if (col.size < 3) return null
        val three = col.take(3)
        val gap1 = three[1].y - three[0].y
        val gap2 = three[2].y - three[1].y
        if (gap1 < 40 || gap2 < 40) return null
        val ratio = gap1.toFloat() / gap2
        if (ratio < 0.75f || ratio > 1.33f) return null
        return three
    }

    /**
     * 左侧植物详情卡里「成熟时间」下方进度条的蓝色填充比例（0~1）；找不到进度条返回 null。
     *
     * 进度条 = 蓝色已完成段 + 深灰未完成段，等浇水后整条变蓝。
     * 只在卡片固定的左上区域找，靠「横向跨度够长 + 蓝/灰像素占多数」排除文字、图标与徽标。
     */
    fun waterBarRatio(bmp: Bitmap): Double? {
        val (img, top) = contentCrop(bmp)
        val w = img.width
        val x0 = (w * 0.01f).toInt()
        val x1 = (w * 0.22f).toInt()
        val y0 = (top + img.height * 0.32f).toInt()
        val y1 = (top + img.height * 0.45f).toInt()
        val row = IntArray(w)
        var best = 0
        var bestBlue = 0
        for (y in y0 until y1) {
            img.getPixels(row, 0, w, 0, y - top, w, 1)
            var blue = 0
            var gray = 0
            var minX = -1
            var maxX = -1
            for (x in x0 until x1) {
                val c = row[x]
                val b = c and 0xFF
                val g = (c shr 8) and 0xFF
                val r = (c shr 16) and 0xFF
                val isBlue = b > 150 && b - r > 60 && b - g > 20
                val isGray = kotlin.math.abs(b - 75) < 30 &&
                    kotlin.math.abs(g - 86) < 30 && kotlin.math.abs(r - 92) < 30
                if (isBlue) blue++
                if (isGray) gray++
                if (isBlue || isGray) {
                    if (minX < 0) minX = x
                    maxX = x
                }
            }
            if (minX < 0) continue
            val span = maxX - minX + 1
            if (span < w * 0.04f) continue
            // 蓝/灰像素要填满跨度的大半，否则只是散落的蓝色元素
            if (blue + gray < span * 0.6f) continue
            if (blue + gray > best) {
                best = blue + gray
                bestBlue = blue
            }
        }
        if (best == 0) return null
        return bestBlue.toDouble() / best
    }

    /** 同一模板取分数最高的 n 个位置（命中后把该区域抹掉再找下一个）。 */    private fun topMatches(
        bmp: Bitmap,
        src: Bitmap?,
        threshold: Double,
        heightFrac: Float,
        n: Int,
        accept: (Int, Int) -> Boolean,
    ): List<Hit> {
        val prepared = prepare(bmp, src, heightFrac) ?: return emptyList()
        val (image, needle, result, w, h) = prepared
        val out = ArrayList<Hit>()
        try {
            for (i in 0 until n) {
                val m = Core.minMaxLoc(result)
                val x = (m.maxLoc.x + w / 2).toInt()
                val y = (m.maxLoc.y + h / 2).toInt()
                if (m.maxVal < threshold) break
                if (accept(x, y)) out += Hit(x, y, m.maxVal)
                val left = (m.maxLoc.x - w / 2).toInt().coerceAtLeast(0)
                val top = (m.maxLoc.y - h / 2).toInt().coerceAtLeast(0)
                val right = (m.maxLoc.x + w).toInt().coerceAtMost(result.cols())
                val bottom = (m.maxLoc.y + h).toInt().coerceAtMost(result.rows())
                if (right > left && bottom > top) {
                    result.submat(Rect(left, top, right - left, bottom - top)).setTo(
                        org.opencv.core.Scalar(0.0)
                    )
                }
            }
        } finally {
            image.release()
            needle.release()
            result.release()
        }
        return out
    }

    /**
     * 游戏跑在 21:9 窗口时截图顶部（或底部）会带纯黑边，UI 整体缩小下移；
     * 模板缩放和区域门槛都必须按「内容区」而不是整张截图算，否则按钮看见也匹配不上
     * （2026-11-20 真机踩坑：圆钮出现却一直识别不到）。全屏时内容区=全屏，行为不变。
     * shortcut: 只按纯黑行判定，若游戏夜景出现连续纯黑天空可能多裁几十像素，影响可忽略；换引擎黑边带噪点时改用灰度阈值
     */
    private fun contentCrop(bmp: Bitmap): Pair<Bitmap, Int> {
        val h = bmp.height
        val w = bmp.width
        val row = IntArray(w)
        fun black(y: Int): Boolean {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var x = w / 10
            val xEnd = w - w / 10
            while (x < xEnd) {
                val c = row[x]
                val s = (c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)
                if (s > 12) return false
                x += 16
            }
            return true
        }
        var top = 0
        while (top < h / 3 && black(top)) top += 4
        var bot = h - 1
        while (bot > h * 2 / 3 && black(bot)) bot -= 4
        if (top <= 0 && bot >= h - 1) return bmp to 0
        return Bitmap.createBitmap(bmp, 0, top, w, bot - top + 1) to top
    }

    /** 同 [match]，但先裁掉黑边按内容区匹配，命中坐标加回偏移换算成全屏像素。 */
    private fun matchCrop(
        bmp: Bitmap,
        src: Bitmap?,
        threshold: Double,
        heightFrac: Float,
        accept: (Bitmap, Int, Int) -> Boolean,
    ): Hit? {
        val (b, top) = contentCrop(bmp)
        val hit = match(b, src, threshold, heightFrac) { x, y -> accept(b, x, y) }
        return hit?.let { Hit(it.x, it.y + top, it.score, b.height) }
    }

    /**
     * @param heightFrac 模板在真机截图里占内容区高的比例，用来按分辨率缩放模板
     * @param accept 额外位置校验，挡掉画面里相似的图标
     */
    private fun match(
        bmp: Bitmap,
        src: Bitmap?,
        threshold: Double,
        heightFrac: Float,
        accept: (Int, Int) -> Boolean,
    ): Hit? {
        val prepared = prepare(bmp, src, heightFrac) ?: return null
        val (image, needle, result, w, h) = prepared
        val m = Core.minMaxLoc(result)
        val x = (m.maxLoc.x + w / 2).toInt()
        val y = (m.maxLoc.y + h / 2).toInt()
        val hit = if (m.maxVal >= threshold && accept(x, y)) Hit(x, y, m.maxVal) else null
        image.release()
        needle.release()
        result.release()
        return hit
    }

    private data class Prepared(
        val image: Mat,
        val needle: Mat,
        val result: Mat,
        val w: Int,
        val h: Int,
    )

    private fun prepare(bmp: Bitmap, src: Bitmap?, heightFrac: Float): Prepared? {
        val tpl = src ?: return null
        val h = (bmp.height * heightFrac).toInt().coerceAtLeast(12)
        val w = (h.toFloat() * tpl.width / tpl.height).toInt().coerceAtLeast(12)
        val scaled = Bitmap.createScaledBitmap(tpl, w, h, true)
        val image = Mat()
        val needle = Mat()
        Utils.bitmapToMat(bmp, image)
        Utils.bitmapToMat(scaled, needle)
        Imgproc.cvtColor(image, image, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(needle, needle, Imgproc.COLOR_RGBA2RGB)
        val result = Mat()
        Imgproc.matchTemplate(image, needle, result, Imgproc.TM_CCOEFF_NORMED)
        return Prepared(image, needle, result, w, h)
    }

    private const val BOX_H = 0.0528f
}
