package com.invrestore.ui

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.invrestore.R

fun Context.dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

fun View.setVisible(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}

fun View.setGoneIf(condition: Boolean) {
    visibility = if (condition) View.GONE else View.VISIBLE
}

fun View.tapFeedback() {
    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
}

fun Fragment.snack(
    message: String,
    actionLabel: String? = null,
    action: (() -> Unit)? = null
) {
    if (!isAdded) return
    val bar = Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG)
    bar.setBackgroundTint(ContextCompat.getColor(requireContext(), R.color.surface_3))
    bar.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
    if (actionLabel != null && action != null) {
        bar.setActionTextColor(ContextCompat.getColor(requireContext(), R.color.amber))
        bar.setAction(actionLabel) { action() }
    }
    bar.show()
}
