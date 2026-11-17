package com.invrestore.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.invrestore.R
import com.invrestore.core.PatchKind
import com.invrestore.data.EditorState
import com.invrestore.data.GallerySaver
import com.invrestore.data.MainViewModel
import com.invrestore.data.Screen
import com.invrestore.databinding.FragmentEditorBinding
import com.invrestore.databinding.SheetEditorMoreBinding
import com.invrestore.databinding.SheetReportBinding

/** 单张精修：自动检测 → 点选 → 还原 → 按住对比 → 保存。 */
class EditorFragment : Fragment(R.layout.fragment_editor) {

    private var _binding: FragmentEditorBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var candidateAdapter: CandidateAdapter

    private var holdComparing = false
    private var hintShownFor: String? = null

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.openEditor(uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentEditorBinding.bind(view)

        binding.tvSubtitle.setGoneIf(resources.getBoolean(R.bool.compact_header))
        binding.btnAbout.setOnClickListener { showAboutSheet() }
        binding.btnPick.setOnClickListener {
            it.tapFeedback()
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.btnDetect.setOnClickListener { viewModel.detectEditor() }
        binding.btnRecheck.setOnClickListener {
            val st = viewModel.editor.value
            // 没检出东西时，这个位置变成「手动框选」，给用户一条明确的出路。
            if (st != null && st.candidates.isEmpty() && !st.manual) {
                viewModel.setEditorManual(true)
                snack(getString(R.string.editor_manual_hint))
            } else {
                viewModel.detectEditor()
            }
        }
        binding.btnRestore.setOnClickListener {
            it.tapFeedback()
            viewModel.restoreSelected()
        }
        binding.btnSave.setOnClickListener { viewModel.saveEditor() }
        binding.btnMoreDetect.setOnClickListener { showMore() }
        binding.btnMoreResult.setOnClickListener { showMore() }

        candidateAdapter = CandidateAdapter { index ->
            viewModel.selectEditor(index)
            binding.candidateList.scrollToPosition(index)
        }
        binding.candidateList.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.candidateList.adapter = candidateAdapter

        binding.canvas.onCandidateTap = { index ->
            viewModel.selectEditor(index)
            binding.candidateList.scrollToPosition(index)
        }
        binding.canvas.onManualRect = { rect -> viewModel.evaluateManual(rect) }

        setupHoldCompare()

        viewModel.editor.observe(viewLifecycleOwner) { render(it) }
        viewModel.message.observe(viewLifecycleOwner) { event ->
            event.getIfNotHandled()?.takeIf { it.screen == Screen.EDITOR }?.let { snack(it.text) }
        }
        viewModel.save.observe(viewLifecycleOwner) { event ->
            val result = event.getIfNotHandled()?.takeIf { it.screen == Screen.EDITOR } ?: return@observe
            snack(
                getString(R.string.editor_saved, result.album),
                getString(R.string.action_share)
            ) { GallerySaver.launchShare(requireContext(), result.uris) }
        }
        viewModel.sharing.observe(viewLifecycleOwner) { event ->
            event.getIfNotHandled()?.takeIf { it.screen == Screen.EDITOR }
                ?.let { GallerySaver.launchShare(requireContext(), it.uris) }
        }
        viewModel.saving.observe(viewLifecycleOwner) { updateBusy(viewModel.editor.value) }
    }

    private fun render(state: EditorState) {
        val hasImage = state.hasImage
        binding.emptyGroup.setVisible(!hasImage)
        binding.loadedGroup.setVisible(hasImage)
        if (!hasImage) return

        if (binding.canvas.manualMode != state.manual) {
            binding.canvas.manualMode = state.manual
        }
        binding.canvas.candidates = state.candidates
        binding.canvas.restoredRects = state.restoredRects
        binding.canvas.selected = state.selected

        val target = state.restored ?: state.original
        if (!holdComparing && target != null && binding.canvas.bitmap !== target) {
            binding.canvas.setBitmapKeepView(target)
        }

        val restoredCount = state.restoredRects.size
        if (state.hasResult) {
            binding.chipState.setVisible(true)
            binding.chipState.text = getString(R.string.editor_restored_chip)
            binding.chipState.setBackgroundResource(R.drawable.bg_pill_amber)
            binding.chipState.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.on_amber)
            )
        } else {
            binding.chipState.setVisible(true)
            binding.chipState.text = getString(R.string.editor_original)
            binding.chipState.setBackgroundResource(R.drawable.bg_pill)
            binding.chipState.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.text_primary)
            )
        }

        binding.tvStatus.text = when {
            state.manual -> getString(R.string.editor_manual_hint)
            restoredCount > 0 -> getString(
                R.string.editor_restored_banner, restoredCount, state.maskLabel.orEmpty()
            )
            state.candidates.isEmpty() -> getString(R.string.editor_summary_none)
            else -> getString(
                R.string.editor_summary, state.candidates.size, state.restorableCount
            )
        }
        val dotColor = when {
            restoredCount > 0 -> R.color.mint
            state.candidates.isEmpty() -> R.color.text_tertiary
            else -> R.color.amber
        }
        binding.statusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), dotColor))

        val showRecheck = !state.busy && !state.hasResult &&
                (state.candidates.isNotEmpty() || !state.manual)
        binding.btnRecheck.setVisible(showRecheck)
        if (showRecheck) {
            binding.btnRecheck.setText(
                if (state.candidates.isEmpty()) R.string.editor_manual_mode
                else R.string.editor_recheck
            )
        }
        binding.rowDetect.setVisible(!state.hasResult)
        binding.rowResult.setVisible(state.hasResult)
        binding.candidateList.setVisible(state.candidates.isNotEmpty())
        candidateAdapter.submit(state.candidates, state.restoredRects, state.selected)

        val selected = state.candidates.getOrNull(state.selected)
        val already = selected != null && state.restoredRects.any { it.iou(selected.rect) > 0.85f }
        binding.btnRestore.isEnabled = !state.busy && selected != null &&
                selected.kind == PatchKind.INVERSION && !already
        binding.btnDetect.isEnabled = !state.busy
        binding.btnCompare.isEnabled = state.hasResult
        binding.btnCompare.alpha = if (state.hasResult) 1f else 0.4f

        val key = state.uri?.toString()
        if (key != null && key != hintShownFor && !state.busy) {
            hintShownFor = key
            val hint = binding.tvZoomHint
            hint.setVisible(true)
            hint.postDelayed({ hint.setVisible(false) }, 3800)
        }
        updateBusy(state)
    }

    private fun updateBusy(state: EditorState?) {
        val st = state ?: return
        val saving = viewModel.saving.value == true
        val labelRes = when {
            st.busy -> st.busyLabel
            saving -> R.string.editor_saving
            else -> null
        }
        binding.busyPill.setVisible(labelRes != null)
        binding.tvBusy.text = labelRes?.let { getString(it) }.orEmpty()
        if (labelRes != null) binding.tvZoomHint.setVisible(false)
        binding.btnSave.isEnabled = !st.busy && !saving && st.hasImage
        binding.btnMoreResult.isEnabled = !st.busy && !saving
        binding.btnMoreDetect.isEnabled = !st.busy && !saving
        binding.btnDetect.isEnabled = !st.busy && !saving
    }

    private fun setupHoldCompare() {
        binding.btnCompare.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val st = viewModel.editor.value ?: return@setOnTouchListener false
                    if (!st.hasResult) return@setOnTouchListener false
                    holdComparing = true
                    v.tapFeedback()
                    binding.canvas.candidates = emptyList()
                    binding.canvas.restoredRects = emptyList()
                    binding.canvas.setBitmapKeepView(st.original)
                    binding.chipState.text = getString(R.string.editor_original)
                    binding.chipState.setBackgroundResource(R.drawable.bg_pill)
                    binding.chipState.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.text_primary)
                    )
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (holdComparing) {
                        holdComparing = false
                        render(viewModel.editor.value ?: return@setOnTouchListener true)
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun showMore() {
        val st = viewModel.editor.value ?: return
        val dialog = BottomSheetDialog(requireContext(), R.style.Theme_InvRestore_Sheet)
        val sheet = SheetEditorMoreBinding.inflate(layoutInflater)
        dialog.setContentView(sheet.root)

        sheet.switchManual.isChecked = st.manual
        sheet.switchForceRgb.isChecked = st.forceRgb
        sheet.rowReset.setVisible(st.hasResult)
        sheet.rowReport.isEnabled = st.candidates.isNotEmpty()

        sheet.rowManual.setOnClickListener {
            sheet.switchManual.isChecked = !sheet.switchManual.isChecked
            viewModel.setEditorManual(sheet.switchManual.isChecked)
            dialog.dismiss()
        }
        sheet.rowForceRgb.setOnClickListener {
            sheet.switchForceRgb.isChecked = !sheet.switchForceRgb.isChecked
            viewModel.setEditorForceRgb(sheet.switchForceRgb.isChecked)
        }
        sheet.rowReport.setOnClickListener {
            dialog.dismiss()
            showReport()
        }
        sheet.rowReset.setOnClickListener {
            dialog.dismiss()
            viewModel.resetEditor()
        }
        sheet.rowSave.setOnClickListener {
            dialog.dismiss()
            viewModel.saveEditor()
        }
        sheet.rowShare.setOnClickListener {
            dialog.dismiss()
            viewModel.shareEditor()
        }
        dialog.show()
    }

    private fun showReport() {
        val st = viewModel.editor.value ?: return
        val src = st.original ?: return
        val dialog = BottomSheetDialog(requireContext(), R.style.Theme_InvRestore_Sheet)
        val sheet = SheetReportBinding.inflate(layoutInflater)
        dialog.setContentView(sheet.root)

        sheet.tvSummary.text = ReportFormatter.headline(
            requireContext(), src.width, src.height, st.candidates
        )
        sheet.tvReport.text = ReportFormatter.body(requireContext(), st.candidates)
            .ifEmpty { getString(R.string.editor_report_empty) }
        sheet.btnCopy.setOnClickListener {
            val text = sheet.tvSummary.text.toString() + "\n\n" + sheet.tvReport.text
            val manager = requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("report", text))
            snack(getString(R.string.report_copied))
        }
        sheet.btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
