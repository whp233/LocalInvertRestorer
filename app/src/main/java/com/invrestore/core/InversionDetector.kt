package com.invrestore.core

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 局部反色遮挡的自动检测器。
 *
 * 核心思路
 * --------
 * 反色是**可逆变换**：c' = 255 - c。它没有丢掉任何像素信息，只破坏了编辑边界
 * 两侧的颜色连续性。于是我们不去“猜内容”，而是量化边界：
 *
 *   对候选矩形的四条边，各取内侧 band 像素与外侧 band 像素的平均色，
 *   计算两者的差（接缝落差 seam step）。把矩形内部按某个通道组合取反后
 *   再算一次，如果落差显著塌陷，就说明这个矩形确实是“反色块”，
 *   并且塌陷后的像素正是原始内容。
 *
 * 为什么用积分图：band 平均色需要 O(1) 查询，否则上千个候选矩形 × 8 种通道
 * 组合会慢到没法在手机上跑。反色是仿射变换，可以直接作用在均值上，
 * 不需要逐像素取反再求均值。
 */
class InversionDetector {

    /**
     * 自动找候选区域。耗时操作，请放在后台线程调用。
     * 返回的矩形坐标已经是**原图坐标系**。
     */
    fun detect(src: Bitmap): List<Candidate> {
        if (src.width < 48 || src.height < 48) return emptyList()

        val scale = min(1f, Tuning.SEARCH_MAX / max(src.width, src.height).toFloat())
        val sw = max(1, (src.width * scale).roundToInt())
        val sh = max(1, (src.height * scale).roundToInt())
        val small = if (sw == src.width && sh == src.height) src
        else Bitmap.createScaledBitmap(src, sw, sh, true)

        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== src) small.recycle()

        val plane = (sw + 1) * (sh + 1)
        val sat = FloatArray(3 * plane)
        buildSat(px, sw, sh, sat, plane)

        val gray = FloatArray(sw * sh)
        for (i in gray.indices) {
            val c = px[i]
            gray[i] = (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3f
        }

        val seamV = invSeamMap(px, sw, sh, vertical = true)
        val seamH = invSeamMap(px, sw, sh, vertical = false)
        val rects = rectCandidates(gray, seamV, seamH, sw, sh)
        val raw = ArrayList<Candidate>(rects.size)

        for (rc in rects) {
            val c = scoreRect(sat, plane, sw, sh, rc) ?: continue
            raw.add(c)
        }

        // 非极大值抑制：同一条接缝会被相邻的游程重复提出，只留最可信的
        raw.sortWith(
            compareByDescending<Candidate> { it.kind == PatchKind.INVERSION }
                .thenByDescending { it.confidence }
                .thenByDescending { it.costBefore }
        )
        val kept = ArrayList<Candidate>()
        for (c in raw) {
            if (kept.all { it.rect.iou(c.rect) < Tuning.NMS_IOU }) kept.add(c)
            if (kept.size >= Tuning.MAX_RESULTS) break
        }

        val inv = 1f / scale
        return kept.map { c ->
            val r = c.rect
            c.copy(rect = RectI(
                (r.l * inv).roundToInt(), (r.t * inv).roundToInt(),
                (r.r * inv).roundToInt(), (r.b * inv).roundToInt()
            ).clampTo(src.width, src.height))
        }
    }

    /**
     * 对用户手动框选的区域做同样的评分（用于“手动框选”模式）。
     * @param rect 原图坐标系下的矩形
     */
    fun evaluate(src: Bitmap, rect: RectI): Candidate? {
        if (src.width < 48 || src.height < 48) return null
        val scale = min(1f, Tuning.SEARCH_MAX / max(src.width, src.height).toFloat())
        val sw = max(1, (src.width * scale).roundToInt())
        val sh = max(1, (src.height * scale).roundToInt())
        val small = if (sw == src.width && sh == src.height) src
        else Bitmap.createScaledBitmap(src, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== src) small.recycle()

        val plane = (sw + 1) * (sh + 1)
        val sat = FloatArray(3 * plane)
        buildSat(px, sw, sh, sat, plane)

        val r = rect.clampTo(src.width, src.height)
        val rc = RectI(
            (r.l * scale).roundToInt(), (r.t * scale).roundToInt(),
            (r.r * scale).roundToInt(), (r.b * scale).roundToInt()
        ).clampTo(sw, sh)
        val cand = scoreRect(sat, plane, sw, sh, rc) ?: return null
        return cand.copy(rect = r)
    }

    // ------------------------------------------------------------ scoring

    /**
     * 给一个矩形打分：先看四条边是否都有真实落差，再遍历 7 种通道取反组合，
     * 取接缝落差最小的一种。
     */
    private fun scoreRect(sat: FloatArray, plane: Int, w: Int, h: Int,
                          rc0: RectI): Candidate? {
        var rc = rc0
        var sides = sidesOf(sat, plane, w, h, rc, 0)
        if (sides.size < 3) return null
        var c0 = sides.average().toFloat()
        if ((sides.minOrNull() ?: 0f) < 0.035f || c0 < 0.10f) return null

        // 只在“确实被动过”的矩形上才值得做逐像素精修
        var bestMask = 0
        var cBest = c0
        for (mask in 1..7) {
            val c = seamMean(sat, plane, w, h, rc, mask)
            if (c < cBest) { bestMask = mask; cBest = c }
        }

        rc = refine(sat, plane, w, h, rc, max(bestMask, 1))
        sides = sidesOf(sat, plane, w, h, rc, 0)
        if (sides.size < 3) return null
        c0 = sides.average().toFloat()
        cBest = if (bestMask == 0) c0 else seamMean(sat, plane, w, h, rc, bestMask)

        val ratio = cBest / max(c0, 1e-6f)
        val gain = (c0 - cBest) / max(c0, 1e-6f)

        return when {
            bestMask != 0 && ratio <= Tuning.INVERSION_RATIO && gain >= Tuning.INVERSION_GAIN ->
                Candidate(rc, bestMask, c0, cBest, gain, PatchKind.INVERSION,
                    min(1f, 0.65f * gain + 0.35f * (1f - ratio)))

            c0 >= Tuning.REPLACE_SEAM && sides.size == 4 ->
                Candidate(rc, 0, c0, c0, 0f, PatchKind.REPLACEMENT,
                    min(1f, c0 / 0.32f))

            else -> null
        }
    }

    /** 四条边各微调 ±rad 像素，使接缝落差最小（抵消游程端点的量化误差）。 */
    private fun refine(sat: FloatArray, plane: Int, w: Int, h: Int,
                       rc: RectI, mask: Int, rad: Int = 2): RectI {
        var best = rc
        var bestCost = seamMean(sat, plane, w, h, rc, mask)
        for (dl in -rad..rad) for (dt in -rad..rad) for (dr in -rad..rad) for (db in -rad..rad) {
            val cand = RectI(rc.l + dl, rc.t + dt, rc.r + dr, rc.b + db)
            if (cand.l < 0 || cand.t < 0 || cand.r > w || cand.b > h) continue
            if (cand.w < 8 || cand.h < 8) continue
            val c = seamMean(sat, plane, w, h, cand, mask)
            if (c < bestCost) { bestCost = c; best = cand }
        }
        return best
    }

    private fun seamMean(sat: FloatArray, plane: Int, w: Int, h: Int,
                         rc: RectI, mask: Int): Float {
        val s = sidesOf(sat, plane, w, h, rc, mask)
        return if (s.isEmpty()) 0f else s.average().toFloat()
    }

    /**
     * 四条边的接缝落差（0..1）。反色是仿射的，所以对 band 均值取反即可。
     */
    private fun sidesOf(sat: FloatArray, plane: Int, w: Int, h: Int,
                        rc: RectI, mask: Int, band: Int = Tuning.BAND): FloatArray {
        val out = ArrayList<Float>(4)
        fun cmp(innerL: Int, innerT: Int, innerR: Int, innerB: Int,
                outerL: Int, outerT: Int, outerR: Int, outerB: Int) {
            val mi = rectMean(sat, plane, w, h, innerL, innerT, innerR, innerB)
            if (mask != 0) for (ch in 0..2) if ((mask shr ch) and 1 == 1) mi[ch] = 255f - mi[ch]
            val mo = rectMean(sat, plane, w, h, outerL, outerT, outerR, outerB)
            var d = 0f
            for (ch in 0..2) d += abs(mi[ch] - mo[ch])
            out.add(d / 3f / 255f)
        }
        val bnd = band
        if (rc.t - bnd >= 0) cmp(rc.l, rc.t, rc.r, min(rc.t + bnd, rc.b),
                                 rc.l, rc.t - bnd, rc.r, rc.t)
        if (rc.b + bnd <= h) cmp(rc.l, max(rc.b - bnd, rc.t), rc.r, rc.b,
                                 rc.l, rc.b, rc.r, min(rc.b + bnd, h))
        if (rc.l - bnd >= 0) cmp(rc.l, rc.t, min(rc.l + bnd, rc.r), rc.b,
                                 rc.l - bnd, rc.t, rc.l, rc.b)
        if (rc.r + bnd <= w) cmp(max(rc.r - bnd, rc.l), rc.t, rc.r, rc.b,
                                 rc.r, rc.t, min(rc.r + bnd, w), rc.b)
        return out.toFloatArray()
    }

    /** 半开矩形 [l,r)×[t,b) 的平均色，O(1)。 */
    private fun rectMean(sat: FloatArray, plane: Int, w: Int, h: Int,
                         l0: Int, t0: Int, r0: Int, b0: Int): FloatArray {
        val l = l0.coerceIn(0, w); val t = t0.coerceIn(0, h)
        val r = r0.coerceIn(0, w); val b = b0.coerceIn(0, h)
        if (r <= l || b <= t) return floatArrayOf(0f, 0f, 0f)
        val w1 = w + 1
        val area = (r - l) * (b - t)
        val out = FloatArray(3)
        for (ch in 0..2) {
            val off = ch * plane
            val s = sat[off + b * w1 + r] - sat[off + t * w1 + r] -
                    sat[off + b * w1 + l] + sat[off + t * w1 + l]
            out[ch] = s / area
        }
        return out
    }

    private fun buildSat(px: IntArray, w: Int, h: Int, sat: FloatArray, plane: Int) {
        val w1 = w + 1
        for (y in 0 until h) {
            var ar = 0f; var ag = 0f; var ab = 0f
            val row = (y + 1) * w1
            val prev = y * w1
            for (x in 0 until w) {
                val c = px[y * w + x]
                ar += ((c shr 16) and 0xFF).toFloat()
                ag += ((c shr 8) and 0xFF).toFloat()
                ab += (c and 0xFF).toFloat()
                val i = row + x + 1
                val p = prev + x + 1
                sat[i] = sat[p] + ar
                sat[plane + i] = sat[plane + p] + ag
                sat[2 * plane + i] = sat[2 * plane + p] + ab
            }
        }
    }

    // ------------------------------------------------------------ geometry

    private data class Run(val pos: Int, val start: Int, val end: Int) {
        val len: Int get() = end - start
    }

    /**
     * 反色接缝图。逐像素判断“这里像不像一条反色边界”。
     *
     * 通用边缘图把所有强边都算进来，一条弱边（比如反色块的左边恰好压在与其
     * 反相后很接近的颜色上）会直接掉到阈值以下，整块区域随之漏检。这里换成
     * 假设驱动的度量：取边界两侧各 k 像素的均值，若内侧取反后与外侧接近
     * （残差小）、且两侧本来确有落差（不是平坦区），才记为一条接缝。
     *
     * @param vertical true 求竖边界（x-1|x），false 求横边界（y-1|y）
     */
    private fun invSeamMap(px: IntArray, w: Int, h: Int, vertical: Boolean): FloatArray {
        val out = FloatArray(w * h)
        val n = if (vertical) w else h
        val m = if (vertical) h else w
        val k = max(2, (0.010f * min(w, h)).roundToInt())
        if (n <= 2 * k + 1) return out

        // 前缀和，布局 [i][j][3]，i 为滑动方向、j 为另一方向
        val pref = FloatArray((n + 1) * m * 3)
        for (j in 0 until m) {
            var r = 0f; var g = 0f; var b = 0f
            for (i in 0..n) {
                val o = (i * m + j) * 3
                pref[o] = r; pref[o + 1] = g; pref[o + 2] = b
                if (i < n) {
                    val c = if (vertical) px[j * w + i] else px[i * w + j]
                    r += ((c shr 16) and 0xFF).toFloat()
                    g += ((c shr 8) and 0xFF).toFloat()
                    b += (c and 0xFF).toFloat()
                }
            }
        }

        for (i in 1 until n) {
            val i0 = max(0, i - k)
            val i1 = min(n, i + k)
            val ln = (i - i0).toFloat()
            val rn = (i1 - i).toFloat()
            for (j in 0 until m) {
                val p0 = (i0 * m + j) * 3
                val p1 = (i * m + j) * 3
                val p2 = (i1 * m + j) * 3
                val lr = (pref[p1] - pref[p0]) / ln
                val lg = (pref[p1 + 1] - pref[p0 + 1]) / ln
                val lb = (pref[p1 + 2] - pref[p0 + 2]) / ln
                val rr = (pref[p2] - pref[p1]) / rn
                val rg = (pref[p2 + 1] - pref[p1 + 1]) / rn
                val rb = (pref[p2 + 2] - pref[p1 + 2]) / rn
                val res = (abs(255f - lr - rr) + abs(255f - lg - rg) + abs(255f - lb - rb)) / 3f
                if (res >= Tuning.SEAM_RESIDUAL) continue
                val dif = (abs(lr - rr) + abs(lg - rg) + abs(lb - rb)) / 3f
                if (dif <= Tuning.SEAM_MIN_STEP) continue
                if (vertical) out[j * w + i] = 1f else out[i * w + j] = 1f
            }
        }
        return out
    }

    /**
     * 提出候选矩形，两路来源：
     *  1. 通用边缘图 —— 高对比边界，也覆盖“贴图覆盖”这种不可逆情况；
     *  2. 反色接缝图 —— 由反色假设直接推出，弱边也能抓到。
     */
    private fun rectCandidates(gray: FloatArray, seamV: FloatArray, seamH: FloatArray,
                               w: Int, h: Int): List<RectI> {
        val ex = FloatArray(w * h)
        val ey = FloatArray(w * h)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w - 1) ex[base + x] = abs(gray[base + x + 1] - gray[base + x]) / 255f
        }
        for (y in 0 until h - 1) {
            val base = y * w
            val next = base + w
            for (x in 0 until w) ey[base + x] = abs(gray[next + x] - gray[base + x]) / 255f
        }

        val minRun = max(10, (0.030f * min(w, h)).toInt())
        val tol = max(2, (0.008f * min(w, h)).toInt())
        val minSide = max(10, (Tuning.MIN_SIDE_RATIO * min(w, h)).toInt())
        val maxArea = Tuning.MAX_AREA_RATIO * w * h

        val out = ArrayList<RectI>(96)
        addCandidates(ex, ey, Tuning.EDGE_THRESHOLD,
            max(2, (0.006f * min(w, h)).toInt()), w, h, minRun, minSide, maxArea, tol, out)
        // 反色缝隙本来就会被穿过边界的内容打断，需要更宽的间隙容差才能连成一条
        addCandidates(seamV, seamH, 0.5f,
            max(3, (0.012f * min(w, h)).toInt()), w, h, minRun, minSide, maxArea, tol, out)

        // 去重：同一个框可能被两路都提出来
        val kept = ArrayList<RectI>(out.size)
        for (r in out.sortedByDescending { it.w.toLong() * it.h }) {
            if (kept.all { it.iou(r) < 0.6f }) kept.add(r)
        }
        return kept
    }

    /**
     * 在给定的二值图上做“垂直游程配对 → 验证上下边”。
     */
    private fun addCandidates(
        bx: FloatArray, by: FloatArray, thr: Float, gap: Int,
        w: Int, h: Int, minRun: Int, minSide: Int, maxArea: Float, tol: Int,
        out: MutableList<RectI>
    ) {
        val vRuns = ArrayList<Run>(256)
        collectRuns(bx, w, h, vertical = true, thr = thr, minLen = minRun, gap = gap, out = vRuns)
        if (vRuns.size < 2) return

        // 先按长度截断（短边不可能构成矩形），再按位置排序 ——
        // 下面的配对循环假定 pos 递增，顺序错了会把绝大多数配对直接跳过
        vRuns.sortWith(compareByDescending { it.len })
        val sel = ArrayList(vRuns.subList(0, min(vRuns.size, Tuning.MAX_RUNS)))
        sel.sortWith(compareBy { it.pos })
        if (sel.size < 2) return

        fun rowCoverage(y: Int, x1: Int, x2: Int): Float {
            if (x2 <= x1 || y < 0 || y >= h) return 0f
            var n = 0
            var x = x1
            while (x < x2) { if (by[y * w + x] > thr) n++; x++ }
            return n.toFloat() / (x2 - x1)
        }

        for (i in sel.indices) {
            val a = sel[i]
            for (j in i + 1 until sel.size) {
                val bRun = sel[j]
                if (bRun.pos - a.pos < minSide) continue
                // 两条垂直边必须在高度上大幅重叠：间隙容差会让游程略微越过角点，
                // 因此用重叠比例判断，而不是绝对端点差
                val overlap = min(a.end, bRun.end) - max(a.start, bRun.start)
                if (overlap < 0.8f * min(a.len, bRun.len)) continue
                val t0 = max(a.start, bRun.start)
                val b0 = min(a.end, bRun.end)
                if (b0 - t0 < minSide) continue
                if ((bRun.pos - a.pos).toFloat() * (b0 - t0) > maxArea) continue

                var bestT = t0; var covT = 0f
                for (tt in max(0, t0 - tol)..min(h - 1, t0 + tol)) {
                    val c = rowCoverage(tt, a.pos, bRun.pos)
                    if (c > covT) { covT = c; bestT = tt }
                }
                var bestB = b0; var covB = 0f
                for (bb in max(0, b0 - tol)..min(h - 1, b0 + tol)) {
                    val c = rowCoverage(bb, a.pos, bRun.pos)
                    if (c > covB) { covB = c; bestB = bb }
                }
                if (covT >= 0.5f && covB >= 0.5f && bestB - bestT >= minSide) {
                    out.add(RectI(a.pos, bestT, bRun.pos, bestB))
                    if (out.size >= Tuning.MAX_RECTS) return
                }
            }
        }
    }

    /**
     * 提取边缘游程，容忍 [gap] 个像素的断裂。
     *
     * 真实照片里接缝常被穿过它的内容（文字、发丝、褶皱纹理）打断，逐像素要求
     * 连续会把一条完整的边切成碎片，导致漏检。
     */
    private fun collectRuns(
        edge: FloatArray, w: Int, h: Int, vertical: Boolean,
        thr: Float, minLen: Int, gap: Int, out: ArrayList<Run>
    ) {
        val outer = if (vertical) w else h
        val innerN = if (vertical) h else w
        for (p in 0 until outer) {
            var start = -1
            var last = -1
            var i = 0
            while (i < innerN) {
                val v = if (vertical) edge[i * w + p] else edge[p * w + i]
                if (v > thr) {
                    if (start < 0) { start = i; last = i }
                    else if (i - last - 1 <= gap) last = i
                    else {
                        if (last - start + 1 >= minLen) out.add(Run(p, start, last + 1))
                        start = i; last = i
                    }
                } else if (start >= 0 && i - last > gap) {
                    if (last - start + 1 >= minLen) out.add(Run(p, start, last + 1))
                    start = -1
                }
                i++
            }
            if (start >= 0 && last - start + 1 >= minLen) out.add(Run(p, start, last + 1))
        }
    }
}
