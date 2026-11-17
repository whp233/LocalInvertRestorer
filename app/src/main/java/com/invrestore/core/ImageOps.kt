package com.invrestore.core

import android.graphics.Bitmap

/**
 * 与检测无关的纯像素操作。
 *
 * 反色是 c' = 255 - c，对 RGB 三个通道分别取反，alpha 保持不变。
 * 整图与矩形区域走同一条代码路径，只是矩形不同。
 */
object ImageOps {

    /** 返回新的 Bitmap，不修改 src。rect 为 null 时处理整图。 */
    fun invert(src: Bitmap, rect: RectI? = null): Bitmap {
        val w = src.width
        val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val r = (rect ?: RectI(0, 0, w, h)).clampTo(w, h)
        if (r.w <= 0 || r.h <= 0) return out

        val pix = IntArray(r.w * r.h)
        out.getPixels(pix, 0, r.w, r.l, r.t, r.w, r.h)
        for (i in pix.indices) {
            val c = pix[i]
            val a = (c ushr 24) and 0xFF
            val nr = 255 - ((c shr 16) and 0xFF)
            val ng = 255 - ((c shr 8) and 0xFF)
            val nb = 255 - (c and 0xFF)
            pix[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        out.setPixels(pix, 0, r.w, r.l, r.t, r.w, r.h)
        return out
    }
}
