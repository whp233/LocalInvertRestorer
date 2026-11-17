package com.invrestore.core

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 还原执行器。
 *
 * 只做两件事，都是可解释的：
 *  1. 对矩形内像素按检测出的通道掩码取反 —— 这是反色变换的逆运算，
 *     数学上严格可逆，还原出的就是原始数据。
 *  2. 用边界两侧的均值做一次**色调对齐**（增益限幅 0.75~1.35），
 *     再对边界做 3px 羽化。因为真实图片常是“先调色再反色”或经过 JPEG
 *     压缩，直接取反可能残留轻微色偏与硬边，这一步不是“猜内容”，
 *     只是让接缝消失。
 */
object Restorer {

    fun restore(
        src: Bitmap,
        rect: RectI,
        mask: Int,
        matchTone: Boolean = true,
        feather: Int = Tuning.FEATHER
    ): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = bmp.width
        val h = bmp.height
        val r0 = rect.clampTo(w, h)
        if (r0.w <= 2 || r0.h <= 2 || mask == 0) return bmp

        val pix = IntArray(w * h)
        bmp.getPixels(pix, 0, w, 0, 0, w, h)
        var r = r0

        // 搜索阶段是在缩略图上做的，映射回原图会有 1~2px 的量化误差。
        // 误差虽小，却会漏掉一条“仍然是反色”的像素列/行，在边界留下细线。
        // 这里用真实像素把四条边各自对齐到落差最小的位置。
        r = snapSides(pix, w, h, r, mask)

        val k = max(2, feather)
        val offset = FloatArray(3)   // 逐通道的色调偏移（加性，比乘性增益稳健）

        if (matchTone) {
            // 只比较“紧贴边界的成对像素”：内侧还原值 vs 外侧原值。
            // 用中值而不是均值 —— 边界外侧难免压到深色物体或高光，均值会被带偏，
            // 乘性增益随之把整块区域压暗/提亮，反而制造出一圈接缝。
            val dr = ArrayList<Int>(2048)
            val dg = ArrayList<Int>(2048)
            val db = ArrayList<Int>(2048)

            fun restoreChannel(v: Int, bit: Int): Int =
                if (mask and bit != 0) 255 - v else v

            fun pair(outPix: Int, inPix: Int) {
                val oR = (outPix shr 16) and 0xFF
                val oG = (outPix shr 8) and 0xFF
                val oB = outPix and 0xFF
                val iR = restoreChannel((inPix shr 16) and 0xFF, 1)
                val iG = restoreChannel((inPix shr 8) and 0xFF, 2)
                val iB = restoreChannel(inPix and 0xFF, 4)
                dr.add(oR - iR); dg.add(oG - iG); db.add(oB - iB)
            }

            if (r.t - 1 >= 0) for (x in r.l until r.r) pair(pix[(r.t - 1) * w + x], pix[r.t * w + x])
            if (r.b < h) for (x in r.l until r.r) pair(pix[r.b * w + x], pix[(r.b - 1) * w + x])
            if (r.l - 1 >= 0) for (y in r.t until r.b) pair(pix[y * w + r.l - 1], pix[y * w + r.l])
            if (r.r < w) for (y in r.t until r.b) pair(pix[y * w + r.r], pix[y * w + r.r - 1])

            if (dr.size > 8) {
                offset[0] = median(dr).coerceIn(-15, 15).toFloat()
                offset[1] = median(dg).coerceIn(-15, 15).toFloat()
                offset[2] = median(db).coerceIn(-15, 15).toFloat()
            }
        }

        for (y in r.t until r.b) {
            val ay = alphaAt(y - r.t, r.b - 1 - y, feather)
            for (x in r.l until r.r) {
                val a = ay * alphaAt(x - r.l, r.r - 1 - x, feather)
                if (a <= 0f) continue
                val idx = y * w + x
                val c = pix[idx]
                val or = ((c shr 16) and 0xFF).toFloat()
                val og = ((c shr 8) and 0xFF).toFloat()
                val ob = (c and 0xFF).toFloat()

                var nr = (if (mask and 1 != 0) 255f - or else or) + offset[0]
                var ng = (if (mask and 2 != 0) 255f - og else og) + offset[1]
                var nb = (if (mask and 4 != 0) 255f - ob else ob) + offset[2]
                nr = nr.coerceIn(0f, 255f); ng = ng.coerceIn(0f, 255f); nb = nb.coerceIn(0f, 255f)

                val fr = or + (nr - or) * a
                val fg = og + (ng - og) * a
                val fb = ob + (nb - ob) * a
                pix[idx] = (0xFF shl 24) or
                        ((fr.toInt().coerceIn(0, 255)) shl 16) or
                        ((fg.toInt().coerceIn(0, 255)) shl 8) or
                        fb.toInt().coerceIn(0, 255)
            }
        }

        bmp.setPixels(pix, 0, w, 0, 0, w, h)
        return bmp
    }

    /**
     * 把矩形四条边各自微调，抵消缩略图搜索带来的量化误差（缩放 0.4 倍时
     * 1px 搜索误差 = 2.5px 原图误差，所以搜索半径给到 ±6）。
     *
     * 判据是**比值**而不是绝对残差：
     *   score = |内侧取反 - 外侧| / (|内侧 - 外侧| + 1)
     * 正确的边界处“取反后接得上”（分子小）且“本来就有落差”（分母大），比值极小；
     * 偏到边界外侧时两侧都很平滑、分子巨大，偏到内侧时分子同样下不来。
     *
     * 早先只用绝对残差，会掉进一个陷阱：一旦整段偏移都落在边界外侧的平滑区，
     * 任何一对相邻像素都“差不多”，argmin 就变成随机挑一个，把边界带到沟里，
     * 结果在边缘留下一列被错误取反的像素。
     */
    private fun snapSides(px: IntArray, w: Int, h: Int, r: RectI, mask: Int): RectI {
        val rad = 6
        var l = r.l; var t = r.t; var rr = r.r; var b = r.b

        fun chOf(c: Int, ch: Int) = (c shr (16 - 8 * ch)) and 0xFF

        fun chVal(c: Int, ch: Int): Int {
            val v = chOf(c, ch)
            return if (mask and (1 shl ch) != 0) 255 - v else v
        }

        /** 返回 (取反后残差, 原始落差)，均为通道均值。 */
        fun pairCost(inner: Int, outer: Int): FloatArray {
            var res = 0f
            var dif = 0f
            for (ch in 0..2) {
                val iv = chVal(inner, ch)
                val ov = chOf(outer, ch)
                res += abs(iv - ov)
                dif += abs(chOf(inner, ch) - ov)
            }
            return floatArrayOf(res / 3f, dif / 3f)
        }

        fun pick(cost: (Int) -> FloatArray?): Int {
            var best = 0
            var bestScore = Float.MAX_VALUE
            for (o in -rad..rad) {
                val c = cost(o) ?: continue
                val sc = c[0] / (c[1] + 1f)
                if (sc < bestScore) { bestScore = sc; best = o }
            }
            return best
        }

        // 左边：比较 x 与 x-1
        l = (r.l + pick { o ->
            val x = r.l + o
            if (x <= 0 || x >= w) null else {
                var res = 0f; var dif = 0f
                for (y in r.t until r.b) {
                    val c = pairCost(px[y * w + x], px[y * w + x - 1])
                    res += c[0]; dif += c[1]
                }
                val n = (r.b - r.t).toFloat()
                floatArrayOf(res / n, dif / n)
            }
        }).coerceIn(1, w - 1)

        // 右边：比较 x 与 x+1（x 是最后一个内侧像素）
        rr = (r.r + pick { o ->
            val x = r.r - 1 + o
            if (x < 0 || x >= w - 1) null else {
                var res = 0f; var dif = 0f
                for (y in r.t until r.b) {
                    val c = pairCost(px[y * w + x], px[y * w + x + 1])
                    res += c[0]; dif += c[1]
                }
                val n = (r.b - r.t).toFloat()
                floatArrayOf(res / n, dif / n)
            }
        }).coerceIn(l + 2, w)

        // 上边
        t = (r.t + pick { o ->
            val y = r.t + o
            if (y <= 0 || y >= h) null else {
                var res = 0f; var dif = 0f
                for (x in l until rr) {
                    val c = pairCost(px[y * w + x], px[(y - 1) * w + x])
                    res += c[0]; dif += c[1]
                }
                val n = (rr - l).toFloat()
                floatArrayOf(res / n, dif / n)
            }
        }).coerceIn(1, h - 1)

        // 下边：y 是最后一个内侧像素
        b = (r.b + pick { o ->
            val y = r.b - 1 + o
            if (y < 0 || y >= h - 1) null else {
                var res = 0f; var dif = 0f
                for (x in l until rr) {
                    val c = pairCost(px[y * w + x], px[(y + 1) * w + x])
                    res += c[0]; dif += c[1]
                }
                val n = (rr - l).toFloat()
                floatArrayOf(res / n, dif / n)
            }
        }).coerceIn(t + 2, h)

        return RectI(l, t, rr, b)
    }

    private fun median(values: MutableList<Int>): Int {
        val a = IntArray(values.size) { values[it] }
        a.sort()
        return if (a.size % 2 == 1) a[a.size / 2]
        else (a[a.size / 2 - 1] + a[a.size / 2]) / 2
    }

    /** 距边缘 d 像素处的羽化系数：前 feather 像素从 0 渐变到 1。 */
    private fun alphaAt(dLow: Int, dHigh: Int, feather: Int): Float {
        if (feather <= 0) return 1f
        val d = min(dLow, dHigh)
        if (d >= feather) return 1f
        return (d + 1).toFloat() / (feather + 1).toFloat()
    }
}
