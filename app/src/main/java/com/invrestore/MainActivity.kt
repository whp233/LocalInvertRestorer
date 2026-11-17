package com.invrestore

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import com.invrestore.core.Candidate
import com.invrestore.core.InversionDetector
import com.invrestore.core.Mask
import com.invrestore.core.PatchKind
import com.invrestore.core.RectI
import com.invrestore.core.Restorer
import com.invrestore.ui.RegionOverlayView
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var preview: ImageView
    private lateinit var overlay: RegionOverlayView
    private lateinit var report: TextView
    private lateinit var status: TextView
    private lateinit var btnRestore: Button
    private lateinit var btnSave: Button
    private lateinit var cbManual: CheckBox
    private lateinit var cbForceRgb: CheckBox

    private val detector = InversionDetector()
    private var original: Bitmap? = null
    private var restored: Bitmap? = null
    private var showingRestored = false

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        runAsync("正在读取图片…") {
            val bmp = loadBitmap(uri)
            runOnUiThread {
                original = bmp
                restored = null
                showingRestored = false
                preview.setImageBitmap(bmp)
                overlay.setBitmapSize(bmp.width, bmp.height)
                overlay.clear()
                btnRestore.isEnabled = false
                btnSave.isEnabled = true
                report.text = "原图 ${bmp.width}×${bmp.height}\n点击“自动检测”开始分析，或打开“手动框选”自己框出可疑区域。"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        report = findViewById(R.id.report)
        status = findViewById(R.id.status)
        btnRestore = findViewById(R.id.btnRestore)
        btnSave = findViewById(R.id.btnSave)
        cbManual = findViewById(R.id.cbManual)
        cbForceRgb = findViewById(R.id.cbForceRgb)

        findViewById<Button>(R.id.btnOpen).setOnClickListener {
            picker.launch("image/*")
        }

        findViewById<Button>(R.id.btnDetect).setOnClickListener { runDetect() }

        btnRestore.setOnClickListener { runRestore() }

        btnSave.setOnClickListener { saveCurrent() }

        findViewById<Button>(R.id.btnCompare).setOnClickListener {
            val o = original ?: return@setOnClickListener
            val r = restored ?: return@setOnClickListener
            showingRestored = !showingRestored
            preview.setImageBitmap(if (showingRestored) r else o)
        }

        cbManual.setOnCheckedChangeListener { _, checked ->
            overlay.manualMode = checked
            if (checked) report.text = "手动框选：在画面上拖出一个矩形，松手后会自动判定该区域是否可逆。"
        }

        overlay.onManualRect = { rect -> runManual(rect) }

        btnRestore.isEnabled = false
        btnSave.isEnabled = false
    }

    // ---------------------------------------------------------------- detect

    private fun runDetect() {
        val src = original ?: return
        runAsync("正在分析…") {
            val found = detector.detect(src)
            runOnUiThread {
                overlay.candidates = found
                overlay.manualMode = false
                cbManual.isChecked = false
                restored = null
                showingRestored = false
                preview.setImageBitmap(src)
                btnRestore.isEnabled = found.any { it.kind == PatchKind.INVERSION }
                report.text = buildReport(src, found)
                if (found.isEmpty()) {
                    toast("没有找到明显的矩形接缝。可以试试手动框选。")
                }
            }
        }
    }

    private fun runManual(rect: RectI) {
        val src = original ?: return
        runAsync("正在判定所选区域…") {
            val c = detector.evaluate(src, rect)
            runOnUiThread {
                if (c == null) {
                    toast("该区域没有可逆的反色特征，原始信息很可能已经被覆盖或涂抹。")
                    return@runOnUiThread
                }
                val list = ArrayList<Candidate>(overlay.candidates)
                list.removeAll { it.rect.iou(c.rect) > 0.5f }
                list.add(0, c)
                overlay.candidates = list
                overlay.selected = 0
                btnRestore.isEnabled = c.kind == PatchKind.INVERSION
                report.text = buildReport(src, list)
            }
        }
    }

    // --------------------------------------------------------------- restore

    private fun runRestore() {
        val src = original ?: return
        val c = overlay.selectedCandidate() ?: run {
            toast("先点选一个候选区域")
            return
        }
        if (c.kind != PatchKind.INVERSION) {
            toast("这个区域是被覆盖/涂抹的，像素信息已经丢失，无法还原。")
            return
        }
        val mask = if (cbForceRgb.isChecked) Mask.RGB else c.mask
        runAsync("正在还原…") {
            val out = Restorer.restore(src, c.rect, mask)
            runOnUiThread {
                restored = out
                showingRestored = true
                preview.setImageBitmap(out)
                btnSave.isEnabled = true
                toast("已还原 ${c.restoreSides}（${Mask.describe(mask)}），可点“对比”查看原图")
            }
        }
    }

    // ------------------------------------------------------------------ save

    private fun saveCurrent() {
        val bmp = restored ?: original ?: return
        runAsync("正在保存…") {
            val path = try {
                writeToGallery(bmp)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (path != null) toast("已保存：$path") else toast("保存失败")
            }
        }
    }

    private fun writeToGallery(bmp: Bitmap): String {
        val name = "invrestore_${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/InvertRestore")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("insert failed")
            contentResolver.openOutputStream(uri)!!.use { os ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return "Pictures/InvertRestore/$name"
        }
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "InvertRestore")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, name)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f.absolutePath
    }

    // ------------------------------------------------------------------ misc

    private fun buildReport(src: Bitmap, list: List<Candidate>): String {
        if (list.isEmpty()) {
            return "原图 ${src.width}×${src.height}\n未检测到矩形接缝。"
        }
        val inv = list.count { it.kind == PatchKind.INVERSION }
        val sb = StringBuilder()
        sb.append("原图 ${src.width}×${src.height}，检测到 ${list.size} 个可疑区域")
        sb.append("（可还原 $inv 个）\n")
        list.forEachIndexed { i, c ->
            sb.append("\n[${i + 1}] ${c.restoreSides}\n")
            if (c.kind == PatchKind.INVERSION) {
                sb.append("    判定：局部反色 · 置信度 ${(c.confidence * 100).roundToInt()}%\n")
                sb.append("    接缝落差 %.3f → %.3f（下降 %d%%）\n"
                    .format(c.costBefore, c.costAfter,
                        ((1f - c.costAfter / max(c.costBefore, 1e-4f)) * 100).roundToInt()))
                sb.append("    还原通道：${Mask.describe(c.mask)}\n")
            } else {
                sb.append("    判定：覆盖/涂抹 · 信息已不可逆\n")
                sb.append("    边界落差 %.3f，取反后无改善，说明内容是被替换而非取反。\n"
                    .format(c.costBefore))
            }
        }
        return sb.toString().trimEnd()
    }

    private fun runAsync(label: String, work: () -> Unit) {
        status.text = label
        setBusy(true)
        Thread {
            try {
                work()
            } catch (t: Throwable) {
                runOnUiThread { toast("出错了：${t.message}") }
            } finally {
                runOnUiThread {
                    status.text = ""
                    setBusy(false)
                }
            }
        }.start()
    }

    private fun setBusy(busy: Boolean) {
        findViewById<View>(R.id.btnOpen).isEnabled = !busy
        findViewById<View>(R.id.btnDetect).isEnabled = !busy && original != null
        btnRestore.isEnabled = !busy && (overlay.selectedCandidate()?.kind == PatchKind.INVERSION)
        btnSave.isEnabled = !busy && (original != null)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------- image input

    private fun loadBitmap(uri: Uri): Bitmap {
        val maxEdge = 2600
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longEdge = max(info.size.width, info.size.height)
                if (longEdge > maxEdge) {
                    decoder.setTargetSampleSize(ceil(longEdge.toDouble() / maxEdge).toInt())
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / sample > maxEdge) sample *= 2
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: throw IllegalStateException("无法解码图片")

        val orientation = contentResolver.openInputStream(uri)?.use { ins ->
            ExifInterface(ins).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val angle = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        return if (angle == 0f) bmp else Bitmap.createBitmap(
            bmp, 0, 0, bmp.width, bmp.height,
            Matrix().apply { postRotate(angle) }, true)
    }
}
