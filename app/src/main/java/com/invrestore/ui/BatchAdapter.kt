package com.invrestore.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.invrestore.R
import com.invrestore.data.BatchItem
import com.invrestore.data.BatchStatus
import com.invrestore.databinding.ItemBatchBinding

/** 批量页的三列网格。状态直接画在缩略图上，扫一眼就知道哪张成了、哪张没戏。 */
class BatchAdapter(
    private val onClick: (BatchItem) -> Unit
) : RecyclerView.Adapter<BatchAdapter.VH>() {

    private var items: List<BatchItem> = emptyList()

    fun submit(list: List<BatchItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemBatchBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = holder.binding
        val item = items[position]
        val ctx = b.root.context

        b.thumb.setImageBitmap(item.thumb)
        b.thumb.contentDescription = item.name
        b.index.text = (position + 1).toString()
        b.name.text = item.name

        val running = item.status == BatchStatus.RUNNING
        b.spinner.setVisible(running)
        b.dim.setVisible(item.status == BatchStatus.NONE)

        val badge = when (item.status) {
            BatchStatus.PENDING -> ""
            BatchStatus.RUNNING -> ctx.getString(R.string.batch_status_running)
            BatchStatus.DONE -> "✓ ${item.restoredCount}"
            BatchStatus.NONE -> "—"
            BatchStatus.ERROR -> "!"
        }
        b.status.text = badge
        b.status.setVisible(badge.isNotEmpty())

        val bg: Int
        val fg: Int
        when (item.status) {
            BatchStatus.DONE -> {
                bg = R.color.mint_container; fg = R.color.on_mint_container
            }
            BatchStatus.ERROR -> {
                bg = R.color.danger_container; fg = R.color.danger
            }
            BatchStatus.RUNNING -> {
                bg = R.color.amber_container; fg = R.color.on_amber_container
            }
            else -> {
                bg = R.color.surface_3; fg = R.color.text_secondary
            }
        }
        b.status.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, bg))
        b.status.setTextColor(ContextCompat.getColor(ctx, fg))

        b.root.setOnClickListener { onClick(item) }
    }

    class VH(val binding: ItemBatchBinding) : RecyclerView.ViewHolder(binding.root)
}
