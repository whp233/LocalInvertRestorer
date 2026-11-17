package com.invrestore.ui

import android.os.Bundle
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.invrestore.MainActivity
import com.invrestore.R
import com.invrestore.data.BatchItem
import com.invrestore.data.BatchStatus
import com.invrestore.data.GallerySaver
import com.invrestore.data.ImageIo
import com.invrestore.data.MainViewModel
import com.invrestore.data.Screen
import com.invrestore.databinding.FragmentBatchBinding
import com.invrestore.databinding.SheetBatchDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 批量：多选导入 → 逐张检测还原 → 网格状态 → 全部保存。 */
class BatchFragment : Fragment(R.layout.fragment_batch) {

    private var _binding: FragmentBatchBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: BatchAdapter
    private var lastCount = 0

    private val picker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MainViewModel.MAX_BATCH)
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addBatch(uris)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentBatchBinding.bind(view)

        binding.tvSubtitle.setGoneIf(resources.getBoolean(R.bool.compact_header))
        binding.btnAbout.setOnClickListener { showAboutSheet() }

        adapter = BatchAdapter { item -> openDetail(item.id) }
        binding.grid.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.grid.adapter = adapter

        val pick = View.OnClickListener {
            it.tapFeedback()
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        binding.btnPick.setOnClickListener(pick)
        binding.btnAdd.setOnClickListener(pick)
        binding.btnRun.setOnClickListener { viewModel.startBatch() }
        binding.btnSaveAll.setOnClickListener { viewModel.saveBatch() }
        binding.btnStop.setOnClickListener { viewModel.stopBatch() }
        binding.btnClear.setOnClickListener { confirmClear() }

        viewModel.batch.observe(viewLifecycleOwner) { renderAll() }
        viewModel.importing.observe(viewLifecycleOwner) { renderAll() }
        viewModel.running.observe(viewLifecycleOwner) { renderAll() }
        viewModel.progress.observe(viewLifecycleOwner) { renderAll() }
        viewModel.saving.observe(viewLifecycleOwner) { renderAll() }
        viewModel.message.observe(viewLifecycleOwner) { event ->
            event.getIfNotHandled()?.takeIf { it.screen == Screen.BATCH }?.let { snack(it.text) }
        }
        viewModel.save.observe(viewLifecycleOwner) { event ->
            val result = event.getIfNotHandled()?.takeIf { it.screen == Screen.BATCH } ?: return@observe
            snack(
                getString(R.string.batch_saved, result.count),
                getString(R.string.action_share)
            ) { GallerySaver.launchShare(requireContext(), result.uris) }
        }
    }

    private fun renderAll() {
        val items = viewModel.batch.value.orEmpty()
        val importing = viewModel.importing.value
        val running = viewModel.running.value == true
        val progress = viewModel.progress.value
        val saving = viewModel.saving.value == true

        adapter.submit(items)
        if (items.size > lastCount) {
            binding.grid.scrollToPosition(items.size - 1)
        }
        lastCount = items.size

        val hasItems = items.isNotEmpty()
        binding.emptyGroup.setVisible(!hasItems)
        binding.grid.setVisible(hasItems)
        binding.summaryRow.setVisible(hasItems)
        binding.actionBar.setVisible(hasItems)

        binding.btnAdd.isEnabled = !running && importing == null
        binding.btnPick.isEnabled = !running && importing == null

        val done = items.count { it.status == BatchStatus.DONE }
        binding.tvSummary.text = getString(R.string.batch_summary, items.size, done)
        binding.btnStop.setVisible(running)
        binding.btnClear.setVisible(!running)

        val showProgress = importing != null || running
        binding.progressRow.setVisible(showProgress)
        if (importing != null) {
            binding.progress.isIndeterminate = true
            binding.tvProgress.text =
                getString(R.string.batch_importing, importing.done, importing.total)
        } else if (running && progress != null) {
            binding.progress.isIndeterminate = false
            binding.progress.max = progress.total
            binding.progress.setProgressCompat(progress.done, true)
            binding.tvProgress.text =
                getString(R.string.batch_running, progress.done, progress.total)
        }

        val canRun = !running && importing == null && items.any {
            it.status == BatchStatus.PENDING || it.status == BatchStatus.ERROR
        }
        binding.btnRun.isEnabled = canRun
        binding.btnRun.alpha = if (canRun) 1f else 0.45f
        binding.btnSaveAll.isEnabled = !running && !saving && items.any {
            it.status == BatchStatus.DONE
        }
        binding.btnSaveAll.alpha = if (binding.btnSaveAll.isEnabled) 1f else 0.45f
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.batch_clear_title)
            .setMessage(R.string.batch_clear_body)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.batch_clear_ok) { _, _ -> viewModel.clearBatch() }
            .show()
    }

    private fun openDetail(id: String) {
        val item = viewModel.batch.value.orEmpty().firstOrNull { it.id == id } ?: return
        val index = viewModel.batch.value.orEmpty().indexOf(item)
        val dialog = BottomSheetDialog(requireContext(), R.style.Theme_InvRestore_Sheet)
        val sheet = SheetBatchDetailBinding.inflate(layoutInflater)
        dialog.setContentView(sheet.root)

        sheet.tvTitle.text = "${getString(R.string.batch_detail_title, index + 1)} · ${item.name}"
        sheet.tvInfo.text = when (item.status) {
            BatchStatus.DONE -> getString(
                R.string.batch_detail_info, item.restoredCount, item.maskLabel.orEmpty()
            )
            BatchStatus.NONE -> getString(R.string.batch_detail_none)
            BatchStatus.ERROR -> getString(R.string.batch_error_decode)
            BatchStatus.RUNNING -> getString(R.string.batch_status_running)
            BatchStatus.PENDING -> getString(R.string.batch_status_pending)
        }
        sheet.btnSaveThis.setVisible(item.status == BatchStatus.DONE)
        sheet.btnOpen.setVisible(item.status != BatchStatus.ERROR)
        sheet.compare.setVisible(false)
        sheet.loading.setVisible(true)

        sheet.btnClose.setOnClickListener { dialog.dismiss() }
        sheet.btnRemove.setOnClickListener {
            dialog.dismiss()
            viewModel.removeBatch(item.id)
        }
        sheet.btnSaveThis.setOnClickListener {
            dialog.dismiss()
            viewModel.saveBatchItem(item.id)
        }
        sheet.btnOpen.setOnClickListener {
            dialog.dismiss()
            (requireActivity() as? MainActivity)?.openEditor(item.uri)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext().applicationContext
            val pair = withContext(Dispatchers.IO) {
                val before = runCatching { ImageIo.decode(ctx, item.uri, 1500) }.getOrNull()
                val after = item.resultFile?.let { file ->
                    runCatching {
                        ImageIo.thumbnail(ImageIo.decodeFile(file.absolutePath), 1500)
                    }.getOrNull()
                }
                before to after
            }
            if (!dialog.isShowing) return@launch
            sheet.loading.setVisible(false)
            sheet.compare.setVisible(true)
            sheet.compare.before = pair.first
            sheet.compare.after = pair.second
            sheet.compare.labelBefore = getString(R.string.editor_original)
            if (pair.second != null) {
                sheet.compare.labelAfter = getString(R.string.editor_restored_chip)
            }
        }
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
