package com.invrestore

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.invrestore.core.ImageOps
import com.invrestore.core.InversionDetector
import com.invrestore.core.RestorePipeline
import com.invrestore.data.BatchItem
import com.invrestore.data.BatchStatus
import com.invrestore.ui.BatchAdapter
import com.invrestore.ui.CandidateAdapter
import com.invrestore.ui.EditorFragment
import com.invrestore.ui.InvertFragment
import com.invrestore.databinding.FragmentEditorBinding
import com.invrestore.databinding.FragmentInvertBinding
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 用真实布局 + 真实检测/还原结果渲染截图，供 README 与视觉走查使用。
 * 文案全部取 strings.xml，不硬编码，保证截图和应用实际界面一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {

    @Test
    fun captureScreens() {
        val out = TestPaths.screens()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()

        capture(activity, File(out, "01_editor_empty.jpg"))

        val nav = activity.findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.selectedItemId = R.id.nav_batch
        idle()
        capture(activity, File(out, "02_batch_empty.jpg"))

        nav.selectedItemId = R.id.nav_invert
        idle()
        capture(activity, File(out, "03_invert_empty.jpg"))

        // 单张：真实检测 demo 样张
        nav.selectedItemId = R.id.nav_restore
        idle()
        val editor = activity.supportFragmentManager.fragments
            .filterIsInstance<EditorFragment>().first()
        val eb = FragmentEditorBinding.bind(editor.requireView())
        val bmp = BitmapFactory.decodeFile(
            TestPaths.sample("demo_inverted.png").absolutePath
        )
        val found = InversionDetector().detect(bmp)
        val restored = RestorePipeline.restoreAll(bmp, found)

        eb.emptyGroup.visibility = View.GONE
        eb.loadedGroup.visibility = View.VISIBLE
        eb.canvas.bitmap = bmp
        eb.canvas.candidates = found
        eb.canvas.selected = found.indexOfFirst { it.mask != 0 }.coerceAtLeast(0)
        eb.chipState.visibility = View.VISIBLE
        eb.chipState.text = activity.getString(R.string.editor_original)
        eb.tvStatus.text = activity.getString(
            R.string.editor_summary, found.size, found.count { it.mask != 0 }
        )
        eb.candidateList.visibility = View.VISIBLE
        (eb.candidateList.adapter as CandidateAdapter)
            .submit(found, emptyList(), eb.canvas.selected)
        eb.rowDetect.visibility = View.VISIBLE
        eb.rowResult.visibility = View.GONE
        eb.btnRecheck.visibility = View.VISIBLE
        eb.btnRecheck.setText(R.string.editor_recheck)
        idle()
        capture(activity, File(out, "04_editor_loaded.jpg"))

        // 单张：真实还原结果
        val resultBmp = restored.bitmap
        if (resultBmp != null) {
            eb.canvas.bitmap = resultBmp
            eb.canvas.candidates = found
            eb.canvas.restoredRects = restored.applied.map { it.rect }
            eb.canvas.selected = 0
            eb.chipState.text = activity.getString(R.string.editor_restored_chip)
            eb.chipState.setBackgroundResource(R.drawable.bg_pill_amber)
            eb.chipState.setTextColor(
                androidx.core.content.ContextCompat.getColor(activity, R.color.on_amber)
            )
            eb.tvStatus.text = activity.getString(
                R.string.editor_restored_banner,
                restored.applied.size,
                com.invrestore.data.MaskNames.of(activity, restored.applied.first().mask)
            )
            (eb.candidateList.adapter as CandidateAdapter)
                .submit(found, restored.applied.map { it.rect }, 0)
            eb.rowDetect.visibility = View.GONE
            eb.rowResult.visibility = View.VISIBLE
            eb.btnRecheck.visibility = View.GONE
            idle()
            capture(activity, File(out, "05_editor_restored.jpg"))
        }

        // 批量：6 张、状态各异（列表视觉走查用）
        nav.selectedItemId = R.id.nav_batch
        idle()
        val batchFragment = activity.supportFragmentManager.fragments
            .filterIsInstance<com.invrestore.ui.BatchFragment>().first()
        val bv = batchFragment.requireView()
        bv.findViewById<View>(R.id.emptyGroup).visibility = View.GONE
        val grid = bv.findViewById<RecyclerView>(R.id.grid)
        grid.visibility = View.VISIBLE
        bv.findViewById<View>(R.id.summaryRow).visibility = View.VISIBLE
        bv.findViewById<View>(R.id.actionBar).visibility = View.VISIBLE
        val thumbs = (0 until 6).map {
            Bitmap.createScaledBitmap(bmp, 300, 200, true)
        }
        val statuses = listOf(
            BatchStatus.DONE, BatchStatus.DONE, BatchStatus.NONE,
            BatchStatus.DONE, BatchStatus.RUNNING, BatchStatus.ERROR
        )
        val items = (0 until 6).map { i ->
            BatchItem(
                id = "id$i",
                uri = android.net.Uri.EMPTY,
                name = "IMG_2026_000$i.jpg",
                thumb = thumbs[i],
                status = statuses[i],
                restoredCount = if (statuses[i] == BatchStatus.DONE) (i % 2) + 1 else 0,
                maskLabel = activity.getString(R.string.mask_rgb)
            )
        }
        bv.findViewById<android.widget.TextView>(R.id.tvSummary).text =
            activity.getString(R.string.batch_summary, items.size, items.count { it.status == BatchStatus.DONE })
        (grid.adapter as BatchAdapter).submit(items)
        bv.findViewById<android.widget.Button>(R.id.btnRun).apply {
            isEnabled = true; alpha = 1f
        }
        bv.findViewById<android.widget.Button>(R.id.btnSaveAll).apply {
            isEnabled = true; alpha = 1f
        }
        idle()
        capture(activity, File(out, "06_batch_list.jpg"))

        // 反色：真实整图反色，可拖动对比
        nav.selectedItemId = R.id.nav_invert
        idle()
        val invert = activity.supportFragmentManager.fragments
            .filterIsInstance<InvertFragment>().first()
        val ib = FragmentInvertBinding.bind(invert.requireView())
        ib.emptyGroup.visibility = View.GONE
        ib.loadedGroup.visibility = View.VISIBLE
        ib.canvas.visibility = View.GONE
        ib.compare.visibility = View.VISIBLE
        ib.compare.before = bmp
        ib.compare.after = ImageOps.invert(bmp)
        ib.compare.labelBefore = activity.getString(R.string.invert_original)
        ib.compare.labelAfter = activity.getString(R.string.invert_result)
        ib.chipState.visibility = View.GONE
        ib.tvStatus.text = activity.getString(R.string.invert_applied_hint)
        ib.rowAction.visibility = View.GONE
        ib.rowResult.visibility = View.VISIBLE
        idle()
        capture(activity, File(out, "07_invert_applied.jpg"))
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun capture(activity: MainActivity, file: File) {
        idle()
        val decor = activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        println("saved ${file.absolutePath} ${file.length()}")
    }
}
