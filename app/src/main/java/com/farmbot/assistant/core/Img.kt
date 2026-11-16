package com.farmbot.assistant.core

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.sqrt

/**
 * 轻量图像工具：不依赖 OpenCV，只做本场景需要的三件事
 *  1. 区域平均色 / 网格探测（标定用）
 *  2. 灰度抽取
 *  3. 稀疏采样 ZNCC 模板匹配（找按钮、找图标）
 */
object Img {

    fun crop(src: Bitmap, r: Rect): Bitmap =
        Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())

    fun avgColor(src: Bitmap, r: Rect, step: Int = 3): IntArray {
        val x1 = maxOf(0, r.left)
        val y1 = maxOf(0, r.top)
        val x2 = minOf(src.width, r.right)
        val y2 = minOf(src.height, r.bottom)
        if (x2 <= x1 || y2 <= y1) return intArrayOf(0, 0, 0)
        var rs = 0L; var gs = 0L; var bs = 0L; var n = 0L
        var y = y1
        while (y < y2) {
            var x = x1
            while (x < x2) {
                val c = src.getPixel(x, y)
                rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c)
                n++
                x += step
            }
            y += step
        }
        if (n == 0L) return intArrayOf(0, 0, 0)
        return intArrayOf((rs / n).toInt(), (gs / n).toInt(), (bs / n).toInt())
    }

    fun hex(rgb: IntArray): String = String.format("#%02X%02X%02X", rgb[0], rgb[1], rgb[2])

    /** 把整屏切成 cols x rows 网格，输出每格平均色，用于人工标定地块与按钮位置 */
    fun probeGrid(src: Bitmap, cols: Int, rows: Int): String {
        val sb = StringBuilder()
        val cw = maxOf(1, src.width / cols)
        val ch = maxOf(1, src.height / rows)
        for (row in 0 until rows) {
            sb.append(String.format("%02d ", row))
            for (col in 0 until cols) {
                val r = Rect(col * cw, row * ch, minOf(src.width, (col + 1) * cw), minOf(src.height, (row + 1) * ch))
                sb.append(hex(avgColor(src, r, 4))).append(' ')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** 抽取灰度亮度数组（0..255），以 IntArray 返回，索引 y*width+x */
    fun gray(src: Bitmap): IntArray {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            out[i] = ((Color.red(c) * 77 + Color.green(c) * 151 + Color.blue(c) * 28) shr 8)
        }
        return out
    }

    data class Match(val x: Int, val y: Int, val w: Int, val h: Int, val score: Float) {
        val cx: Int get() = x + w / 2
        val cy: Int get() = y + h / 2
        override fun toString() = "($x,$y) score=%.3f".format(score)
    }

    /**
     * 稀疏采样 ZNCC 模板匹配。
     * 只在 roi 内滑动，按 grid x grid 的采样点计算相关系数，避免全像素开销。
     *
     * @param threshold 相关系数阈值（0.7~0.9 常用）
     * @param stride    滑动步长，越大越快越可能与最佳位置错开 1~2px
     */
    fun findTemplate(
        screen: Bitmap,
        tpl: Bitmap,
        roi: Rect,
        threshold: Float = 0.75f,
        stride: Int = 3,
        grid: Int = 14,
        maxResults: Int = 6
    ): List<Match> {
        val sw = screen.width
        val sh = screen.height
        val sg = gray(screen)
        val tg = gray(tpl)
        val tw = tpl.width
        val th = tpl.height
        if (tw <= 0 || th <= 0) return emptyList()

        val px = IntArray(grid * grid)
        val py = IntArray(grid * grid)
        val tv = FloatArray(grid * grid)
        var k = 0
        for (gy in 0 until grid) {
            val y = (gy * (th - 1)) / maxOf(1, grid - 1)
            for (gx in 0 until grid) {
                val x = (gx * (tw - 1)) / maxOf(1, grid - 1)
                px[k] = x; py[k] = y; tv[k] = tg[y * tw + x].toFloat(); k++
            }
        }
        val n = k
        var tMean = 0f
        for (i in 0 until n) tMean += tv[i]
        tMean /= n
        var tNorm = 0f
        for (i in 0 until n) { tv[i] -= tMean; tNorm += tv[i] * tv[i] }
        tNorm = sqrt(tNorm)
        if (tNorm < 1e-3f) return emptyList()
        for (i in 0 until n) tv[i] /= tNorm

        val x1 = maxOf(0, roi.left)
        val y1 = maxOf(0, roi.top)
        val x2 = minOf(sw - tw, roi.right)
        val y2 = minOf(sh - th, roi.bottom)
        if (x2 < x1 || y2 < y1) return emptyList()

        val candidates = ArrayList<Match>()
        val sVals = FloatArray(n)
        var y = y1
        while (y <= y2) {
            var x = x1
            while (x <= x2) {
                var sum = 0f
                for (i in 0 until n) {
                    val v = sg[(y + py[i]) * sw + (x + px[i])].toFloat()
                    sVals[i] = v
                    sum += v
                }
                val mean = sum / n
                var dot = 0f
                var norm = 0f
                for (i in 0 until n) {
                    val d = sVals[i] - mean
                    dot += d * tv[i]
                    norm += d * d
                }
                if (norm > 1e-3f) {
                    val score = dot / sqrt(norm)
                    if (score >= threshold) {
                        push(candidates, Match(x, y, tw, th, score), maxResults)
                    }
                }
                x += stride
            }
            y += stride
        }
        return candidates.sortedByDescending { it.score }
            .let { filterSuppressed(it, maxOf(tw, th) / 2) }
            .take(maxResults)
    }

    private fun push(list: ArrayList<Match>, m: Match, cap: Int) {
        if (list.size < cap) { list.add(m); return }
        var worst = 0
        for (i in 1 until list.size) if (list[i].score < list[worst].score) worst = i
        if (m.score > list[worst].score) list[worst] = m
    }

    private fun filterSuppressed(sorted: List<Match>, minDist: Int): List<Match> {
        val keep = ArrayList<Match>()
        for (m in sorted) {
            if (keep.none { kotlin.math.abs(it.cx - m.cx) < minDist && kotlin.math.abs(it.cy - m.cy) < minDist }) {
                keep.add(m)
            }
        }
        return keep
    }

    /** 在区域内统计满足条件的像素占比，例如判断"成熟发黄"的地块占比 */
    fun ratio(src: Bitmap, r: Rect, step: Int = 2, pred: (r: Int, g: Int, b: Int) -> Boolean): Float {
        val x1 = maxOf(0, r.left); val y1 = maxOf(0, r.top)
        val x2 = minOf(src.width, r.right); val y2 = minOf(src.height, r.bottom)
        if (x2 <= x1 || y2 <= y1) return 0f
        var hit = 0L; var total = 0L
        var y = y1
        while (y < y2) {
            var x = x1
            while (x < x2) {
                val c = src.getPixel(x, y)
                if (pred(Color.red(c), Color.green(c), Color.blue(c))) hit++
                total++
                x += step
            }
            y += step
        }
        return if (total == 0L) 0f else hit.toFloat() / total
    }
}
