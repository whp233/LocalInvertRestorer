package com.invrestore.core

import android.graphics.Bitmap
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
     * 把矩形四条边各自微调（±3px），使“内侧取反后”与紧邻的外侧像素最接近。
     * 正确的边界处残差接近 0，偏一格就会把仍是反色的像素或本该保留的像素算进来，
     * 残差明显变大，所以这个一维搜索是锐利的。
     */
    private fun snapSides(px: IntArray, w: Int, h: Int, r: RectI, mask: Int): RectI {
        val rad = 3
        var l = r.l; var t = r.t; var rr = r.r; var b = r.b

        fun valOf(c: Int, ch: Int): Int {
            val v = when (ch) {
                0 -> (c shr 16) and 0xFF
                1 -> (c shr 8) and 0xFF
                else -> c and 0xFF
            }
            val bit = 1 shl ch
            return if (mask and bit != 0) 255 - v else v
        }

        fun diff(a: Int, b2: Int): Float {
            var s = 0
            for (ch in 0..2) s += kotlin.math.abs(valOf(a, ch) - ((b2 shr (16 - 8 * ch)) and 0xFF))
            return s / 3f
        }

        var best = 0; var bestCost = Float.MAX_VALUE
        for (o in -rad..rad) {
            val x = r.l + o
            if (x <= 0 || x >= w) continue
            var s = 0.0
            for (y in r.t until r.b) s += diff(px[y * w + x], px[y * w + x - 1])
            val c = (s / (r.b - r.t)).toFloat()
            if (c < bestCost) { bestCost = c; best = o }
        }
        l = (r.l + best).coerceIn(1, w - 1)

        best = 0; bestCost = Float.MAX_VALUE
        for (o in -rad..rad) {
            val x = r.r - 1 + o
            if (x < 0 || x >= w - 1) continue
            var s = 0.0
            for (y in r.t until r.b) s += diff(px[y * w + x], px[y * w + x + 1])
            val c = (s / (r.b - r.t)).toFloat()
            if (c < bestCost) { bestCost = c; best = o }
        }
        rr = (r.r + best).coerceIn(l + 2, w)

        best = 0; bestCost = Float.MAX_VALUE
        for (o in -rad..rad) {
            val y = r.t + o
            if (y <= 0 || y >= h) continue
            var s = 0.0
            for (x in l until rr) s += diff(px[y * w + x], px[(y - 1) * w + x])
            val c = (s / (rr - l)).toFloat()
            if (c < bestCost) { bestCost = c; best = o }
        }
        t = (r.t + best).coerceIn(1, h - 1)

        best = 0; bestCost = Float.MAX_VALUE
        for (o in -rad..rad) {
            val y = r.b - 1 + o
            if (y < 0 || y >= h - 1) continue
            var s = 0.0
            for (x in l until rr) s += diff(px[y * w + x], px[(y + 1) * w + x])
            val c = (s / (rr - l)).toFloat()
            if (c < bestCost) { bestCost = c; best = o }
        }
        b = (r.b + best).coerceIn(t + 2, h)

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
