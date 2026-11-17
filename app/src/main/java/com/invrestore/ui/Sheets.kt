package com.invrestore.ui

import android.content.Intent
import android.net.Uri
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.invrestore.BuildConfig
import com.invrestore.R
import com.invrestore.databinding.SheetAboutBinding

private const val GITHUB_URL = "https://github.com/whp233/LocalInvertRestorer"

/** 三页共用的「关于与说明」弹层：隐私、原理、边界、开源信息。 */
fun Fragment.showAboutSheet() {
    if (!isAdded) return
    val dialog = BottomSheetDialog(requireContext(), R.style.Theme_InvRestore_Sheet)
    val binding = SheetAboutBinding.inflate(layoutInflater)
    dialog.setContentView(binding.root)
    binding.tvVersion.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
    binding.btnGithub.setOnClickListener {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
        }
        dialog.dismiss()
    }
    dialog.show()
}
