package com.invrestore.data

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.invrestore.R
import com.invrestore.core.ImageOps
import com.invrestore.core.InversionDetector
import com.invrestore.core.Mask
import com.invrestore.core.PatchKind
import com.invrestore.core.RectI
import com.invrestore.core.RestorePipeline
import com.invrestore.core.Restorer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 全应用唯一的业务状态：单张、批量、反色三个页签共享一组图片，
 * 切页签不丢状态，旋屏不重新解码。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val MAX_BATCH = 30
    }

    private val bg = Dispatchers.IO
    private val cpu = Dispatchers.Default

    // ------------------------------------------------------------------ state

    private val _editor = MutableLiveData(EditorState())
    val editor: LiveData<EditorState> = _editor

    private val _batch = MutableLiveData<List<BatchItem>>(emptyList())
    val batch: LiveData<List<BatchItem>> = _batch
    private val _importing = MutableLiveData<ImportProgress?>(null)
    val importing: LiveData<ImportProgress?> = _importing
    private val _running = MutableLiveData(false)
    val running: LiveData<Boolean> = _running
    private val _progress = MutableLiveData<ImportProgress?>(null)
    val progress: LiveData<ImportProgress?> = _progress

    private val _invert = MutableLiveData(InvertState())
    val invert: LiveData<InvertState> = _invert

    private val _message = MutableLiveData<Event<UiMsg>>()
    val message: LiveData<Event<UiMsg>> = _message
    private val _save = MutableLiveData<Event<SaveResult>>()
    val save: LiveData<Event<SaveResult>> = _save
    private val _sharing = MutableLiveData<Event<SharePayload>>()
    val sharing: LiveData<Event<SharePayload>> = _sharing
    private val _saving = MutableLiveData(false)
    val saving: LiveData<Boolean> = _saving

    private var editorJob: Job? = null
    private var batchJob: Job? = null

    private val batchDir = File(app.cacheDir, "batch").apply {
        runCatching { listFiles()?.forEach { it.delete() } }
        mkdirs()
    }

    // ------------------------------------------------------------------ editor

    fun openEditor(uri: Uri) {
        if (_editor.value?.busy == true) return
        editorJob?.cancel()
        _editor.value = EditorState(uri = uri, busy = true, busyLabel = R.string.editor_loading)
        editorJob = viewModelScope.launch {
            val ctx = getApplication<Application>()
            val bmp = runCatching { withContext(bg) { ImageIo.decode(ctx, uri) } }.getOrNull()
            if (bmp == null) {
                _editor.value = EditorState()
                say(Screen.EDITOR, R.string.editor_error_load)
                return@launch
            }
            _editor.value = EditorState(
                uri = uri, original = bmp, busy = true, busyLabel = R.string.editor_detecting
            )
            val found = runCatching {
                withContext(cpu) { InversionDetector().detect(bmp) }
            }.getOrDefault(emptyList())
            val selected = when {
                found.isEmpty() -> -1
                else -> found.indexOfFirst { it.kind == PatchKind.INVERSION }
                    .let { if (it >= 0) it else 0 }
            }
            _editor.value = EditorState(
                uri = uri, original = bmp, candidates = found, selected = selected
            )
            if (found.isEmpty()) say(Screen.EDITOR, R.string.editor_no_result)
        }
    }

    fun detectEditor() {
        val st = _editor.value ?: return
        val src = st.original ?: return
        if (st.busy) return
        _editor.value = st.copy(
            busy = true, busyLabel = R.string.editor_detecting,
            restored = null, restoredRects = emptyList(), maskLabel = null
        )
        editorJob = viewModelScope.launch {
            val found = runCatching {
                withContext(cpu) { InversionDetector().detect(src) }
            }.getOrDefault(emptyList())
            val cur = _editor.value ?: return@launch
            val selected = when {
                found.isEmpty() -> -1
                else -> found.indexOfFirst { it.kind == PatchKind.INVERSION }
                    .let { if (it >= 0) it else 0 }
            }
            _editor.value = cur.copy(
                candidates = found, selected = selected,
                busy = false, busyLabel = null,
                restored = null, restoredRects = emptyList(), maskLabel = null
            )
            if (found.isEmpty()) say(Screen.EDITOR, R.string.editor_no_result)
        }
    }

    fun selectEditor(index: Int) {
        val st = _editor.value ?: return
        if (index !in st.candidates.indices) return
        _editor.value = st.copy(selected = index)
    }

    fun setEditorManual(on: Boolean) {
        _editor.value = _editor.value?.copy(manual = on)
    }

    fun setEditorForceRgb(on: Boolean) {
        _editor.value = _editor.value?.copy(forceRgb = on)
    }

    fun restoreSelected() {
        val st = _editor.value ?: return
        if (st.busy) return
        val src = st.original ?: return
        val c = st.candidates.getOrNull(st.selected)
        if (c == null) {
            say(Screen.EDITOR, R.string.editor_need_select)
            return
        }
        if (c.kind != PatchKind.INVERSION) {
            say(Screen.EDITOR, R.string.editor_rep_not_restorable)
            return
        }
        if (st.restoredRects.any { it.iou(c.rect) > 0.85f }) {
            say(Screen.EDITOR, R.string.editor_already_restored)
            return
        }
        val mask = if (st.forceRgb) Mask.RGB else c.mask
        _editor.value = st.copy(busy = true, busyLabel = R.string.editor_restoring)
        editorJob = viewModelScope.launch {
            val base = st.restored ?: src
            val out = runCatching {
                withContext(cpu) { Restorer.restore(base, c.rect, mask) }
            }.getOrNull()
            val cur = _editor.value ?: return@launch
            if (out == null) {
                _editor.value = cur.copy(busy = false, busyLabel = null)
                say(Screen.EDITOR, R.string.editor_error_generic)
            } else {
                _editor.value = cur.copy(
                    restored = out,
                    restoredRects = cur.restoredRects + c.rect,
                    busy = false, busyLabel = null,
                    maskLabel = MaskNames.of(getApplication(), mask)
                )
                say(Screen.EDITOR, R.string.editor_restored_toast)
            }
        }
    }

    fun evaluateManual(rect: RectI) {
        val st = _editor.value ?: return
        val src = st.original ?: return
        if (st.busy) return
        _editor.value = st.copy(busy = true, busyLabel = R.string.editor_detecting)
        editorJob = viewModelScope.launch {
            val c = runCatching {
                withContext(cpu) { InversionDetector().evaluate(src, rect) }
            }.getOrNull()
            val cur = _editor.value ?: return@launch
            if (c == null) {
                _editor.value = cur.copy(busy = false, busyLabel = null)
                say(Screen.EDITOR, R.string.editor_manual_none)
            } else {
                val list = cur.candidates.filter { it.rect.iou(c.rect) <= 0.45f }
                    .toMutableList()
                list.add(0, c)
                _editor.value = cur.copy(
                    candidates = list, selected = 0,
                    busy = false, busyLabel = null, manual = false
                )
                say(Screen.EDITOR, R.string.editor_manual_toast)
            }
        }
    }

    fun resetEditor() {
        val st = _editor.value ?: return
        if (!st.hasResult) return
        _editor.value = st.copy(
            restored = null, restoredRects = emptyList(), maskLabel = null
        )
        say(Screen.EDITOR, R.string.editor_reset_done)
    }

    fun saveEditor() {
        val st = _editor.value ?: return
        val bmp = st.restored ?: st.original ?: return
        val uri = st.uri ?: return
        if (_saving.value == true) return
        _saving.value = true
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val name = withContext(bg) { ImageIo.baseName(ImageIo.displayName(ctx, uri)) }
            val res = runCatching {
                withContext(bg) {
                    GallerySaver.saveBitmap(ctx, bmp, name, ctx.getString(R.string.suffix_restored))
                }
            }.getOrNull()
            _saving.value = false
            if (res == null) say(Screen.EDITOR, R.string.editor_save_failed)
            else _save.value = Event(SaveResult(Screen.EDITOR, 1, res.album, listOf(res.uri)))
        }
    }

    fun shareEditor() {
        val st = _editor.value ?: return
        val bmp = st.restored ?: st.original ?: return
        val uri = st.uri ?: return
        if (_saving.value == true) return
        _saving.value = true
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val name = withContext(bg) { ImageIo.baseName(ImageIo.displayName(ctx, uri)) }
            val out = runCatching {
                withContext(bg) {
                    GallerySaver.prepareShare(ctx, bmp, name, ctx.getString(R.string.suffix_restored))
                }
            }.getOrNull()
            _saving.value = false
            if (out == null) say(Screen.EDITOR, R.string.editor_save_failed)
            else _sharing.value = Event(SharePayload(Screen.EDITOR, listOf(out)))
        }
    }

    // ------------------------------------------------------------------- batch

    fun addBatch(uris: List<Uri>) {
        if (uris.isEmpty() || _importing.value != null) return
        val current = _batch.value.orEmpty()
        if (current.size >= MAX_BATCH) {
            say(Screen.BATCH, R.string.batch_full)
            return
        }
        val take = uris.take(MAX_BATCH - current.size)
        _importing.value = ImportProgress(0, take.size)
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val added = ArrayList<BatchItem>(take.size)
            var failed = 0
            take.forEachIndexed { i, uri ->
                _importing.value = ImportProgress(i, take.size)
                val name = runCatching {
                    withContext(bg) { ImageIo.displayName(ctx, uri) }
                }.getOrDefault("image")
                val thumb = runCatching {
                    withContext(bg) { ImageIo.decodeThumb(ctx, uri) }
                }.getOrNull()
                if (thumb == null) failed++ else added.add(
                    BatchItem(UUID.randomUUID().toString(), uri, name, thumb)
                )
            }
            _batch.value = _batch.value.orEmpty() + added
            _importing.value = null
            if (failed > 0) say(Screen.BATCH, R.string.batch_add_failed, failed)
            else if (added.isNotEmpty()) say(Screen.BATCH, R.string.batch_added, added.size)
        }
    }

    fun startBatch() {
        if (batchJob?.isActive == true) return
        val targets = _batch.value.orEmpty()
            .filter { it.status == BatchStatus.PENDING || it.status == BatchStatus.ERROR }
        if (targets.isEmpty()) {
            say(Screen.BATCH, R.string.batch_all_done)
            return
        }
        _running.value = true
        _progress.value = ImportProgress(0, targets.size)
        batchJob = viewModelScope.launch {
            var done = 0
            try {
                for (item in targets) {
                    if (!isActive) break
                    updateItem(item.id) { it.copy(status = BatchStatus.RUNNING, error = null) }
                    try {
                        val result = withContext(bg) { processBatchItem(item) }
                        updateItem(item.id) { result }
                    } catch (ce: CancellationException) {
                        updateItem(item.id) { it.copy(status = BatchStatus.PENDING) }
                        throw ce
                    } catch (t: Throwable) {
                        updateItem(item.id) {
                            it.copy(status = BatchStatus.ERROR, error = t.message)
                        }
                    }
                    done++
                    _progress.value = ImportProgress(done, targets.size)
                }
            } finally {
                _running.value = false
                _progress.value = null
            }
        }
    }

    fun stopBatch() {
        batchJob?.cancel()
        _running.value = false
        _progress.value = null
    }

    fun saveBatch() {
        val items = _batch.value.orEmpty().filter {
            it.status == BatchStatus.DONE && it.resultFile != null
        }
        if (items.isEmpty()) {
            say(Screen.BATCH, R.string.batch_nothing)
            return
        }
        if (_saving.value == true) return
        _saving.value = true
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val files = items.map { it.resultFile!! to it.name }
            val out = runCatching {
                withContext(bg) {
                    GallerySaver.saveFiles(ctx, files, ctx.getString(R.string.suffix_restored))
                }
            }.getOrNull()
            _saving.value = false
            if (out == null || out.count == 0) say(Screen.BATCH, R.string.batch_save_failed)
            else _save.value = Event(SaveResult(Screen.BATCH, out.count, out.album, out.uris))
        }
    }

    fun saveBatchItem(id: String) {
        val item = _batch.value.orEmpty().firstOrNull { it.id == id } ?: return
        if (item.status != BatchStatus.DONE || item.resultFile == null) {
            say(Screen.BATCH, R.string.batch_nothing)
            return
        }
        if (_saving.value == true) return
        _saving.value = true
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val out = runCatching {
                withContext(bg) {
                    GallerySaver.saveFiles(
                        ctx,
                        listOf(item.resultFile to item.name),
                        ctx.getString(R.string.suffix_restored)
                    )
                }
            }.getOrNull()
            _saving.value = false
            if (out == null || out.count == 0) say(Screen.BATCH, R.string.batch_save_failed)
            else _save.value = Event(SaveResult(Screen.BATCH, out.count, out.album, out.uris))
        }
    }

    fun removeBatch(id: String) {
        val item = _batch.value.orEmpty().firstOrNull { it.id == id } ?: return
        GallerySaver.deleteQuietly(item.resultFile)
        _batch.value = _batch.value.orEmpty().filterNot { it.id == id }
        say(Screen.BATCH, R.string.batch_removed)
    }

    fun clearBatch() {
        if (batchJob?.isActive == true) batchJob?.cancel()
        _batch.value.orEmpty().forEach { GallerySaver.deleteQuietly(it.resultFile) }
        _batch.value = emptyList()
        _running.value = false
        _progress.value = null
    }

    private fun updateItem(id: String, transform: (BatchItem) -> BatchItem) {
        val list = _batch.value ?: return
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val copy = list.toMutableList()
        copy[idx] = transform(copy[idx])
        _batch.value = copy
    }

    private fun processBatchItem(item: BatchItem): BatchItem {
        val ctx = getApplication<Application>()
        val src = ImageIo.decode(ctx, item.uri, 2400)
        try {
            val found = InversionDetector().detect(src)
            val result = RestorePipeline.restoreAll(src, found)
            val out = result.bitmap
                ?: return item.copy(
                    status = BatchStatus.NONE, restoredCount = 0, resultFile = null
                )
            try {
                val file = File(batchDir, "${item.id}.png")
                FileOutputStream(file).use { os ->
                    if (!out.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                        throw IllegalStateException("compress failed")
                    }
                }
                return item.copy(
                    status = BatchStatus.DONE,
                    restoredCount = result.applied.size,
                    maskLabel = MaskNames.of(ctx, result.applied.first().mask),
                    resultFile = file
                )
            } finally {
                out.recycle()
            }
        } finally {
            src.recycle()
        }
    }

    // ------------------------------------------------------------------ invert

    fun openInvert(uri: Uri) {
        if (_invert.value?.busy == true) return
        _invert.value = InvertState(uri = uri, busy = true)
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val bmp = runCatching { withContext(bg) { ImageIo.decode(ctx, uri) } }.getOrNull()
            if (bmp == null) {
                _invert.value = InvertState()
                say(Screen.INVERT, R.string.editor_error_load)
            } else {
                _invert.value = InvertState(uri = uri, original = bmp)
            }
        }
    }

    fun setInvertMode(mode: InvertMode) {
        val st = _invert.value ?: return
        if (st.applied || st.busy) return
        _invert.value = st.copy(mode = mode, rect = if (mode == InvertMode.FULL) null else st.rect)
    }

    fun setInvertRect(rect: RectI?) {
        val st = _invert.value ?: return
        if (st.applied) return
        _invert.value = st.copy(rect = rect)
    }

    fun applyInvert() {
        val st = _invert.value ?: return
        val src = st.original ?: return
        if (st.busy) return
        if (st.mode == InvertMode.REGION && st.rect == null) {
            say(Screen.INVERT, R.string.invert_region_none)
            return
        }
        val rect = if (st.mode == InvertMode.REGION) st.rect else null
        _invert.value = st.copy(busy = true)
        viewModelScope.launch {
            val out = runCatching {
                withContext(cpu) { ImageOps.invert(src, rect) }
            }.getOrNull()
            val cur = _invert.value ?: return@launch
            if (out == null) {
                _invert.value = cur.copy(busy = false)
                say(Screen.INVERT, R.string.editor_error_generic)
            } else {
                _invert.value = cur.copy(result = out, busy = false)
            }
        }
    }

    fun resetInvert() {
        val st = _invert.value ?: return
        if (!st.applied) return
        _invert.value = st.copy(result = null, rect = null, mode = InvertMode.FULL)
    }

    fun saveInvert() {
        val st = _invert.value ?: return
        val bmp = st.result ?: st.original ?: return
        val uri = st.uri ?: return
        if (_saving.value == true) return
        _saving.value = true
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val name = withContext(bg) { ImageIo.baseName(ImageIo.displayName(ctx, uri)) }
            val res = runCatching {
                withContext(bg) {
                    GallerySaver.saveBitmap(ctx, bmp, name, ctx.getString(R.string.suffix_inverted))
                }
            }.getOrNull()
            _saving.value = false
            if (res == null) say(Screen.INVERT, R.string.editor_save_failed)
            else _save.value = Event(SaveResult(Screen.INVERT, 1, res.album, listOf(res.uri)))
        }
    }

    fun shareInvert() {
        val st = _invert.value ?: return
        val bmp = st.result ?: st.original ?: return
        val uri = st.uri ?: return
        if (_saving.value == true) return
        _saving.value = true
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val name = withContext(bg) { ImageIo.baseName(ImageIo.displayName(ctx, uri)) }
            val out = runCatching {
                withContext(bg) {
                    GallerySaver.prepareShare(ctx, bmp, name, ctx.getString(R.string.suffix_inverted))
                }
            }.getOrNull()
            _saving.value = false
            if (out == null) say(Screen.INVERT, R.string.editor_save_failed)
            else _sharing.value = Event(SharePayload(Screen.INVERT, listOf(out)))
        }
    }

    // -------------------------------------------------------------------- misc

    private fun say(screen: Screen, res: Int, vararg args: Any) {
        _message.value = Event(
            UiMsg(screen, getApplication<Application>().getString(res, *args))
        )
    }

    override fun onCleared() {
        editorJob?.cancel()
        batchJob?.cancel()
        super.onCleared()
    }
}

/** 通道掩码的本地化名称，UI 与报告共用。 */
object MaskNames {
    fun of(context: Context, mask: Int): String {
        val id = when (mask) {
            1 -> R.string.mask_r
            2 -> R.string.mask_g
            4 -> R.string.mask_b
            3 -> R.string.mask_rg
            5 -> R.string.mask_rb
            6 -> R.string.mask_gb
            7 -> R.string.mask_rgb
            else -> R.string.mask_none
        }
        return context.getString(id)
    }
}
