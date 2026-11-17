package com.invrestore.core

import android.graphics.Bitmap

/**
 * 批量场景的“一次还原所有可逆区域”。
 *
 * 只处理判定为 INVERSION 的候选；相互重叠的候选按置信度优先保留一个，
 * 避免同一块像素被取反两次（取反两次等于没做）。
 */
object RestorePipeline {

    data class Result(val bitmap: Bitmap?, val applied: List<Candidate>)

    fun pick(candidates: List<Candidate>): List<Candidate> {
        val ordered = candidates
            .filter { it.kind == PatchKind.INVERSION }
            .sortedWith(compareByDescending<Candidate> { it.confidence }
                .thenByDescending { it.rect.w * it.rect.h })
        val picked = ArrayList<Candidate>(ordered.size)
        for (c in ordered) {
            if (picked.any { it.rect.iou(c.rect) > 0.2f }) continue
            picked += c
        }
        return picked
    }

    fun restoreAll(
        src: Bitmap,
        candidates: List<Candidate>,
        forceRgb: Boolean = false
    ): Result {
        val picked = pick(candidates)
        if (picked.isEmpty()) return Result(null, emptyList())
        val jobs = picked.map {
            RestoreJob(it.rect, if (forceRgb) Mask.RGB else it.mask)
        }
        return Result(Restorer.restoreMany(src, jobs), picked)
    }
}
