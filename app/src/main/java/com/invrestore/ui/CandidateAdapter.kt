package com.invrestore.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.invrestore.R
import com.invrestore.core.Candidate
import com.invrestore.core.PatchKind
import com.invrestore.core.RectI
import com.invrestore.databinding.ItemCandidateBinding
import kotlin.math.roundToInt

/** 候选区域横向列表：显示编号、判定与置信度，点一下在画布上选中。 */
class CandidateAdapter(
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<CandidateAdapter.VH>() {

    private var items: List<Candidate> = emptyList()
    private var restored: List<Boolean> = emptyList()
    private var selected: Int = -1

    fun submit(list: List<Candidate>, restoredRects: List<RectI>, selectedIndex: Int) {
        items = list
        restored = list.map { c -> restoredRects.any { it.iou(c.rect) > 0.8f } }
        selected = selectedIndex
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemCandidateBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        val chip = holder.binding.root
        chip.text = if (restored[position]) {
            chip.context.getString(R.string.candidate_done_indexed, position + 1)
        } else {
            val kind = chip.context.getString(
                if (c.kind == PatchKind.INVERSION) R.string.candidate_inv else R.string.candidate_rep
            )
            chip.context.getString(
                R.string.candidate_label,
                position + 1,
                kind,
                (c.confidence * 100).roundToInt()
            )
        }
        chip.isChecked = position == selected
        chip.setOnClickListener { onClick(position) }
    }

    class VH(val binding: ItemCandidateBinding) : RecyclerView.ViewHolder(binding.root)
}
