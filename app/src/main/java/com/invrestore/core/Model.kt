package com.invrestore.core

/**
 * 可调参数。桌面参考实现 tools/invrestore_ref.py 使用完全相同的数值，
 * 便于先在电脑上验证效果、再调整参数。
 */
object Tuning {
    /** 搜索阶段把长边缩到这个尺寸，检测在缩略图上做，还原在全分辨率上做。 */
    const val SEARCH_MAX = 640

    /** 测量接缝落差时使用的带宽（搜索分辨率下的像素）。 */
    const val BAND = 3

    /** 候选矩形的最小边长 / 最大面积（相对整图）。 */
    const val MIN_SIDE_RATIO = 0.05f
    const val MAX_AREA_RATIO = 0.55f

    /** 非极大值抑制的 IoU 阈值。 */
    const val NMS_IOU = 0.30f

    /** 判定“这是反色”的两个门槛：代价下降比例、相对增益。 */
    const val INVERSION_RATIO = 0.55f
    const val INVERSION_GAIN = 0.45f

    /** 原始接缝落差超过这个值，就认为“这块被动过”，但可能不是反色。 */
    const val REPLACE_SEAM = 0.10f

    /** 边缘二值化阈值（0..1）。 */
    const val EDGE_THRESHOLD = 0.055f

    /**
     * 反色接缝图的两个门槛（0..255 色彩单位）：
     * 残差 = |内侧取反 - 外侧|，落差 = |内侧 - 外侧|。
     * 残差小说明“取反后接得上”，落差大说明“这里本来就不是平坦区”。
     */
    const val SEAM_RESIDUAL = 40f
    const val SEAM_MIN_STEP = 8f

    /** 参与配对的边缘游程上限，控制计算量。 */
    const val MAX_RUNS = 160
    const val MAX_RECTS = 400

    /**
     * 还原时边界羽化宽度（全分辨率像素）。
     *
     * 默认 0：纯反色的逆运算是精确的，直接贴回即可。羽化会把还原值与“仍然
     * 反色的原值”混合，等于在边缘留下一圈未还原的暗带，反而更糟。
     * 只有在反色后又经过模糊/重压缩、边界对不齐时，才建议调到 1~2。
     */
    const val FEATHER = 0

    /** 单张图最多返回多少个候选。 */
    const val MAX_RESULTS = 5
}

/**
 * 整数像素矩形，右/下边界为开区间（与 Bitmap 的 getPixels 语义一致）。
 */
data class RectI(val l: Int, val t: Int, val r: Int, val b: Int) {
    val w: Int get() = r - l
    val h: Int get() = b - t

    fun iou(o: RectI): Float {
        val ix = minOf(r, o.r) - maxOf(l, o.l)
        val iy = minOf(b, o.b) - maxOf(t, o.t)
        if (ix <= 0 || iy <= 0) return 0f
        val inter = (ix * iy).toFloat()
        val union = (w * h + o.w * o.h).toFloat() - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun clampTo(width: Int, height: Int): RectI =
        RectI(l.coerceIn(0, width - 1), t.coerceIn(0, height - 1),
              r.coerceIn(1, width), b.coerceIn(1, height))
}

/** 通道位掩码：bit0 = R, bit1 = G, bit2 = B。 */
object Mask {
    const val RGB = 7
    fun describe(mask: Int): String = when (mask) {
        1 -> "仅 R 通道反色"
        2 -> "仅 G 通道反色"
        4 -> "仅 B 通道反色"
        3 -> "R+G 双通道反色"
        5 -> "R+B 双通道反色"
        6 -> "G+B 双通道反色"
        7 -> "RGB 全通道反色"
        else -> "未反色"
    }
}

enum class PatchKind {
    /** 区域内容是被取反的，可以真正还原。 */
    INVERSION,

    /** 区域内容是覆盖 / 涂抹 / 模糊，原始信息已被破坏，无法还原。 */
    REPLACEMENT
}

/**
 * 一个候选遮挡区域。
 *
 * @param costBefore 原样状态下的边界接缝代价（0..1，越小越连续）
 * @param costAfter  按 mask 反色后的边界接缝代价
 * @param gain       相对改善 (before-after)/before
 */
data class Candidate(
    val rect: RectI,
    val mask: Int,
    val costBefore: Float,
    val costAfter: Float,
    val gain: Float,
    val kind: PatchKind,
    val confidence: Float
) {
    val restoreSides: String
        get() = "${rect.w}×${rect.h} @ (${rect.l},${rect.t})"
}
