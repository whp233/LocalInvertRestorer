package com.invrestore

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import androidx.core.content.ContextCompat
import com.invrestore.core.InversionDetector
import com.invrestore.ui.ReportFormatter
import com.invrestore.databinding.SheetAboutBinding
import com.invrestore.databinding.SheetBatchDetailBinding
import com.invrestore.databinding.SheetEditorMoreBinding
import com.invrestore.databinding.SheetInvertMoreBinding
import com.invrestore.databinding.SheetReportBinding
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** 弹层视觉走查：按真实内容测量/绘制，检查有没有挤压、截断和空白失衡。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SheetPreviewTest {

    @Test
    fun captureSheets() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val out = TestPaths.screens()
        val w = activity.resources.displayMetrics.widthPixels
        val bmp = BitmapFactory.decodeFile(TestPaths.sample("demo_inverted.png").absolutePath)
        val found = InversionDetector().detect(bmp)

        shot(activity, out, w, "08_sheet_editor_more.jpg", R.layout.sheet_editor_more) { root ->
            SheetEditorMoreBinding.bind(root).apply {
                switchForceRgb.isChecked = true
                rowReset.visibility = View.VISIBLE
            }
        }
        shot(activity, out, w, "09_sheet_report.jpg", R.layout.sheet_report) { root ->
            SheetReportBinding.bind(root).apply {
                tvReport.setTextIsSelectable(false)
                tvSummary.text = ReportFormatter.headline(activity, bmp.width, bmp.height, found)
                tvReport.text = ReportFormatter.body(activity, found)
            }
        }
        shot(activity, out, w, "10_sheet_batch_detail.jpg", R.layout.sheet_batch_detail) { root ->
            SheetBatchDetailBinding.bind(root).apply {
                tvTitle.text = activity.getString(R.string.batch_detail_title, 1) + " · IMG_2026_0001.jpg"
                tvInfo.text = activity.getString(R.string.batch_detail_info, 2, "RGB 全通道")
                loading.visibility = View.GONE
                compare.visibility = View.VISIBLE
                compare.before = bmp
                compare.after = bmp
                compare.labelBefore = activity.getString(R.string.editor_original)
                compare.labelAfter = activity.getString(R.string.editor_restored_chip)
            }
        }
        shot(activity, out, w, "11_sheet_invert_more.jpg", R.layout.sheet_invert_more) { root ->
            SheetInvertMoreBinding.bind(root).apply { rowReset.visibility = View.VISIBLE }
        }
        shot(activity, out, w, "12_sheet_about.jpg", R.layout.sheet_about) { root ->
            SheetAboutBinding.bind(root).tvVersion.text =
                activity.getString(R.string.about_version, BuildConfig.VERSION_NAME)
        }
    }

    private fun shot(
        activity: MainActivity,
        out: File,
        width: Int,
        name: String,
        layout: Int,
        configure: (View) -> Unit
    ) {
        val root = activity.layoutInflater.inflate(layout, null, false)
        configure(root)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val h = root.measuredHeight.coerceAtLeast(1)
        root.layout(0, 0, width, h)
        idle()
        val bitmap = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ContextCompat.getColor(activity, R.color.surface_1))
        root.draw(canvas)
        FileOutputStream(File(out, name)).use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
        }
        println("sheet $name ${width}x$h")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
