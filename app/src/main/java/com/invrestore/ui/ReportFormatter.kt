package com.invrestore.ui

import android.content.Context
import com.invrestore.R
import com.invrestore.core.Candidate
import com.invrestore.core.PatchKind
import com.invrestore.data.MaskNames
import kotlin.math.max
import kotlin.math.roundToInt

/** 把检测结果整理成给人看的报告：一句话结论 + 逐条技术细节。 */
object ReportFormatter {

    fun headline(context: Context, width: Int, height: Int, list: List<Candidate>): String {
        if (list.isEmpty()) {
            return context.getString(R.string.report_none, width, height)
        }
        val inv = list.count { it.kind == PatchKind.INVERSION }
        return context.getString(R.string.report_summary, width, height, list.size, inv)
    }

    fun body(context: Context, list: List<Candidate>): String {
        if (list.isEmpty()) return ""
        val sb = StringBuilder()
        list.forEachIndexed { index, c ->
            if (index > 0) sb.append('\n')
            sb.append(context.getString(R.string.report_item, index + 1, c.restoreSides))
            sb.append('\n')
            if (c.kind == PatchKind.INVERSION) {
                sb.append("    ")
                sb.append(
                    context.getString(
                        R.string.report_kind_inv,
                        (c.confidence * 100).roundToInt()
                    )
                )
                sb.append('\n')
                val drop = ((1f - c.costAfter / max(c.costBefore, 1e-4f)) * 100f)
                    .roundToInt().coerceIn(0, 100)
                sb.append("    ")
                sb.append(context.getString(R.string.report_seam, c.costBefore, c.costAfter, drop))
                sb.append('\n')
                sb.append("    ")
                sb.append(
                    context.getString(R.string.report_channel, MaskNames.of(context, c.mask))
                )
            } else {
                sb.append("    ")
                sb.append(context.getString(R.string.report_kind_rep))
                sb.append('\n')
                sb.append("    ")
                sb.append(context.getString(R.string.report_rep_detail, c.costBefore))
            }
        }
        return sb.toString()
    }
}
