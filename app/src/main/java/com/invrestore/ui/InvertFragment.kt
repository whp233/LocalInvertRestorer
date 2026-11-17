package com.invrestore.ui

import android.os.Bundle
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.invrestore.R
import com.invrestore.data.GallerySaver
import com.invrestore.data.InvertMode
import com.invrestore.data.InvertState
import com.invrestore.data.MainViewModel
import com.invrestore.data.Screen
import com.invrestore.databinding.FragmentInvertBinding
import com.invrestore.databinding.SheetInvertMoreBinding

/** 独立反色工具：整图 / 框选，滑块对比，可保存或分享。 */
class InvertFragment : Fragment(R.layout.fragment_invert) {

    private var _binding: FragmentInvertBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private var lastResultToken: Any? = null

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.openInvert(uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentInvertBinding.bind(view)

        binding.tvSubtitle.setGoneIf(resources.getBoolean(R.bool.compact_header))
        binding.btnAbout.setOnClickListener { showAboutSheet() }
        binding.btnPick.setOnClickListener {
            it.tapFeedback()
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        binding.btnModeFull.setOnClickListener { viewModel.setInvertMode(InvertMode.FULL) }
        binding.btnModeRegion.setOnClickListener { viewModel.setInvertMode(InvertMode.REGION) }
        binding.btnApply.setOnClickListener {
            it.tapFeedback()
            viewModel.applyInvert()
        }
        binding.btnReset.setOnClickListener { viewModel.resetInvert() }
        binding.btnSave.setOnClickListener { viewModel.saveInvert() }
        binding.btnMore.setOnClickListener { showMore() }
        binding.btnMoreResult.setOnClickListener { showMore() }

        binding.canvas.onManualRect = { rect -> viewModel.setInvertRect(rect) }

        viewModel.invert.observe(viewLifecycleOwner) { render(it) }
        viewModel.message.observe(viewLifecycleOwner) { event ->
            event.getIfNotHandled()?.takeIf { it.screen == Screen.INVERT }?.let { snack(it.text) }
        }
        viewModel.save.observe(viewLifecycleOwner) { event ->
            val result = event.getIfNotHandled()?.takeIf { it.screen == Screen.INVERT } ?: return@observe
            snack(
                getString(R.string.invert_saved),
                getString(R.string.action_share)
            ) { GallerySaver.launchShare(requireContext(), result.uris) }
        }
        viewModel.sharing.observe(viewLifecycleOwner) { event ->
            event.getIfNotHandled()?.takeIf { it.screen == Screen.INVERT }
                ?.let { GallerySaver.launchShare(requireContext(), it.uris) }
        }
        viewModel.saving.observe(viewLifecycleOwner) { updateBusy(viewModel.invert.value) }
    }

    private fun render(state: InvertState) {
        val hasImage = state.hasImage
        binding.emptyGroup.setVisible(!hasImage)
        binding.loadedGroup.setVisible(hasImage)
        if (!hasImage) return

        val showResult = state.applied
        binding.canvas.setVisible(!showResult)
        binding.compare.setVisible(showResult)

        if (!showResult) {
            val target = state.original
            if (target != null && binding.canvas.bitmap !== target) {
                binding.canvas.bitmap = target
            }
            val manual = state.mode == InvertMode.REGION
            if (binding.canvas.manualMode != manual) {
                binding.canvas.manualMode = manual
            }
            binding.canvas.manualRect = state.rect
        }

        if (showResult) {
            binding.compare.before = state.original
            binding.compare.after = state.result
            if (state.result !== lastResultToken) {
                lastResultToken = state.result
                binding.compare.split = 0.5f
                binding.compare.labelBefore = getString(R.string.invert_original)
                binding.compare.labelAfter = getString(R.string.invert_result)
            }
        } else {
            lastResultToken = null
        }

        // 对比态左右两侧已有“原图 / 反色”标签，再放状态胶囊会压住左侧标签。
        binding.chipState.setVisible(!showResult)
        binding.chipState.text = if (showResult) {
            getString(R.string.invert_applied)
        } else {
            getString(R.string.invert_original)
        }
        binding.chipState.setBackgroundResource(
            if (showResult) R.drawable.bg_pill_amber else R.drawable.bg_pill
        )
        binding.chipState.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (showResult) R.color.on_amber else R.color.text_primary
            )
        )

        binding.tvStatus.text = when {
            showResult -> getString(R.string.invert_applied_hint)
            state.mode == InvertMode.FULL -> getString(R.string.invert_full_hint)
            state.rect == null -> getString(R.string.invert_region_hint)
            else -> getString(
                R.string.invert_region_info, state.rect.w, state.rect.h
            )
        }

        val canPick = !state.busy
        binding.btnModeFull.isEnabled = canPick && !showResult
        binding.btnModeRegion.isEnabled = canPick && !showResult
        binding.btnModeFull.isChecked = state.mode == InvertMode.FULL
        binding.btnModeRegion.isChecked = state.mode == InvertMode.REGION
        binding.btnModeFull.alpha = if (showResult) 0.4f else 1f
        binding.btnModeRegion.alpha = if (showResult) 0.4f else 1f

        binding.rowAction.setVisible(!showResult)
        binding.rowResult.setVisible(showResult)
        binding.btnApply.isEnabled = canPick
        binding.btnSave.isEnabled = !state.busy && viewModel.saving.value != true

        updateBusy(state)
    }

    private fun updateBusy(state: InvertState?) {
        val st = state ?: return
        val saving = viewModel.saving.value == true
        binding.busyPill.setVisible(st.busy || saving)
        binding.tvBusy.text = when {
            st.busy -> getString(R.string.editor_busy_generic)
            saving -> getString(R.string.editor_saving)
            else -> ""
        }
        binding.btnSave.isEnabled = !st.busy && !saving
        binding.btnMore.isEnabled = !st.busy && !saving
        binding.btnMoreResult.isEnabled = !st.busy && !saving
    }

    private fun showMore() {
        val st = viewModel.invert.value ?: return
        val dialog = BottomSheetDialog(requireContext(), R.style.Theme_InvRestore_Sheet)
        val sheet = SheetInvertMoreBinding.inflate(layoutInflater)
        dialog.setContentView(sheet.root)
        sheet.rowReset.setVisible(st.applied)
        sheet.rowSave.setOnClickListener {
            dialog.dismiss()
            viewModel.saveInvert()
        }
        sheet.rowShare.setOnClickListener {
            dialog.dismiss()
            viewModel.shareInvert()
        }
        sheet.rowReset.setOnClickListener {
            dialog.dismiss()
            viewModel.resetInvert()
        }
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
