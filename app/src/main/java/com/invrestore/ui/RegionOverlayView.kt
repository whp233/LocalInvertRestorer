package com.invrestore.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.invrestore.core.Candidate
import com.invrestore.core.PatchKind
import com.invrestore.core.RectI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 覆盖在 ImageView 上的标注层：画候选框、支持点选与手动框选。
 *
 * 坐标映射与 ImageView 的 FIT_CENTER 保持一致（两者尺寸相同），
 * 这样框的位置才能和画面像素严格对应。
 */
class RegionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmapW = 0
    private var bitmapH = 0

    var candidates: List<Candidate> = emptyList()
        set(value) {
            field = value
            selected = if (value.isEmpty()) -1 else 0
            invalidate()
        }

    var selected: Int = -1
        set(value) { field = value; invalidate() }

    var manualMode: Boolean = false
        set(value) { field = value; manualRect = null; invalidate() }

    var onManualRect: ((RectI) -> Unit)? = null

    private var dragging = false
    private var startX = 0f
    private var startY = 0f
    private var curX = 0f
    private var curY = 0f
    private var manualRect: RectI? = null

    private val density = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        typeface = Typeface.DEFAULT_BOLD
        color = Color.BLACK
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun setBitmapSize(w: Int, h: Int) {
        bitmapW = w
        bitmapH = h
        invalidate()
    }

    fun selectedCandidate(): Candidate? =
        if (selected in candidates.indices) candidates[selected] else null

    fun clear() {
        candidates = emptyList()
        manualRect = null
        invalidate()
    }

    private fun scaleFactor(): Float {
        if (bitmapW <= 0 || bitmapH <= 0 || width == 0) return 1f
        return min(width.toFloat() / bitmapW, height.toFloat() / bitmapH)
    }

    private fun offsetX(): Float = (width - bitmapW * scaleFactor()) / 2f
    private fun offsetY(): Float = (height - bitmapH * scaleFactor()) / 2f

    private fun toView(r: RectI): RectF {
        val s = scaleFactor()
        val ox = offsetX()
        val oy = offsetY()
        return RectF(ox + r.l * s, oy + r.t * s, ox + r.r * s, oy + r.b * s)
    }

    private fun toBitmap(x: Float, y: Float): Pair<Int, Int> {
        val s = scaleFactor()
        val bx = ((x - offsetX()) / s).roundToInt().coerceIn(0, max(0, bitmapW))
        val by = ((y - offsetY()) / s).roundToInt().coerceIn(0, max(0, bitmapH))
        return bx to by
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        candidates.forEachIndexed { i, c ->
            val rf = toView(c.rect)
            val isInv = c.kind == PatchKind.INVERSION
            val base = if (isInv) Color.parseColor("#FFC53D") else Color.parseColor("#9E9E9E")
            val sel = i == selected
            stroke.color = base
            stroke.strokeWidth = (if (sel) 3f else 1.6f) * density
            fill.color = Color.argb(if (sel) 46 else 22, Color.red(base), Color.green(base), Color.blue(base))
            canvas.drawRect(rf, fill)
            canvas.drawRect(rf, stroke)

            val text = "${i + 1}${if (isInv) " 反色 " else " 覆盖 "}${(c.confidence * 100).roundToInt()}%"
            val tw = label.measureText(text)
            val th = label.textSize
            val ly = (rf.top - th - 4f * density).coerceAtLeast(0f)
            labelBg.color = base
            canvas.drawRoundRect(RectF(rf.left, ly, rf.left + tw + 8f * density, ly + th + 4f * density),
                3f * density, 3f * density, labelBg)
            canvas.drawText(text, rf.left + 4f * density, ly + th, label)
        }

        manualRect?.let { r ->
            val rf = toView(r)
            stroke.color = Color.WHITE
            stroke.strokeWidth = 3f * density
            fill.color = Color.argb(36, 255, 255, 255)
            canvas.drawRect(rf, fill)
            canvas.drawRect(rf, stroke)
        }

        if (dragging) {
            val rf = RectF(min(startX, curX), min(startY, curY), max(startX, curX), max(startY, curY))
            stroke.color = Color.WHITE
            stroke.strokeWidth = 2f * density
            canvas.drawRect(rf, stroke)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y
                curX = event.x; curY = event.y
                if (manualMode) {
                    dragging = true
                    invalidate()
                    return true
                }
                // 点选：命中最小的那个候选框
                var hit = -1
                var bestArea = Int.MAX_VALUE
                candidates.forEachIndexed { i, c ->
                    val rf = toView(c.rect)
                    if (rf.contains(event.x, event.y) && c.rect.w * c.rect.h < bestArea) {
                        bestArea = c.rect.w * c.rect.h
                        hit = i
                    }
                }
                if (hit >= 0) selected = hit
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging) { curX = event.x; curY = event.y; invalidate() }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    val (x0, y0) = toBitmap(min(startX, curX), min(startY, curY))
                    val (x1, y1) = toBitmap(max(startX, curX), max(startY, curY))
                    if (x1 - x0 >= 12 && y1 - y0 >= 12) {
                        manualRect = RectI(x0, y0, x1, y1)
                        onManualRect?.invoke(manualRect!!)
                    }
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
