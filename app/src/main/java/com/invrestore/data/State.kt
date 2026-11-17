package com.invrestore.data

import android.graphics.Bitmap
import android.net.Uri
import com.invrestore.core.Candidate
import com.invrestore.core.PatchKind
import com.invrestore.core.RectI
import java.io.File

/** 一次性消费的事件，避免旋屏后 Toast/Snackbar 重复弹出。 */
class Event<out T>(private val content: T) {
    private var handled = false

    fun getIfNotHandled(): T? {
        if (handled) return null
        handled = true
        return content
    }

    fun peek(): T = content
}

enum class Screen { EDITOR, BATCH, INVERT }

data class UiMsg(val screen: Screen, val text: String)

data class SaveResult(
    val screen: Screen,
    val count: Int,
    val album: String,
    val uris: List<Uri>
)

data class SharePayload(val screen: Screen, val uris: List<Uri>)

data class ImportProgress(val done: Int, val total: Int)

// ------------------------------------------------------------------ editor

data class EditorState(
    val uri: Uri? = null,
    val original: Bitmap? = null,
    val restored: Bitmap? = null,
    val candidates: List<Candidate> = emptyList(),
    val selected: Int = -1,
    val restoredRects: List<RectI> = emptyList(),
    val busy: Boolean = false,
    val busyLabel: Int? = null,
    val manual: Boolean = false,
    val forceRgb: Boolean = false,
    val maskLabel: String? = null
) {
    val hasImage: Boolean get() = original != null
    val hasResult: Boolean get() = restored != null
    val restorableCount: Int get() = candidates.count { it.kind == PatchKind.INVERSION }
}

// ------------------------------------------------------------------- batch

enum class BatchStatus { PENDING, RUNNING, DONE, NONE, ERROR }

data class BatchItem(
    val id: String,
    val uri: Uri,
    val name: String,
    val thumb: Bitmap? = null,
    val status: BatchStatus = BatchStatus.PENDING,
    val restoredCount: Int = 0,
    val maskLabel: String? = null,
    val resultFile: File? = null,
    val error: String? = null
)

// ------------------------------------------------------------------ invert

enum class InvertMode { FULL, REGION }

data class InvertState(
    val uri: Uri? = null,
    val original: Bitmap? = null,
    val result: Bitmap? = null,
    val mode: InvertMode = InvertMode.FULL,
    val rect: RectI? = null,
    val busy: Boolean = false
) {
    val hasImage: Boolean get() = original != null
    val applied: Boolean get() = result != null
}
