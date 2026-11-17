package com.invrestore.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.animation.ValueAnimator
import com.invrestore.R
import com.invrestore.core.Candidate
import com.invrestore.core.PatchKind
import com.invrestore.core.RectI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 图片画布：负责绘制位图、候选框，并处理缩放 / 平移 / 点选 / 手动画框。
 *
 * 位图与标注共用同一套矩阵（fit + scale + translate），所以框永远贴着像素，
 * 放大后也不会漂移。所有标注尺寸都用 dp，缩放时保持可读。
 */
class PhotoCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var _bitmap: Bitmap? = null

    var bitmap: Bitmap?
        get() = _bitmap
        set(value) {
            _bitmap = value
            resetTransform()
            invalidate()
        }

    var candidates: List<Candidate> = emptyList()
        set(value) { field = value; invalidate() }

    var restoredRects: List<RectI> = emptyList()
        set(value) { field = value; invalidate() }

    var selected: Int = -1
        set(value) { field = value; invalidate() }

    /** 需要在画面上持续显示的“已框选”矩形（反色页用）。 */
    var manualRect: RectI? = null
        set(value) { field = value; invalidate() }

    var manualMode: Boolean = false
        set(value) {
            field = value
            dragRect = null
            if (value) resetTransform()
            invalidate()
        }

    var onCandidateTap: ((Int) -> Unit)? = null
    var onManualRect: ((RectI) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var fit = 1f
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f

    private var dragRect: RectF? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var animator: ValueAnimator? = null

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(5f)), 0f)
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10.5f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (manualMode) return false
            var hit = -1
            var bestArea = Int.MAX_VALUE
            candidates.forEachIndexed { i, c ->
                if (toView(c.rect).contains(e.x, e.y) && c.rect.w * c.rect.h < bestArea) {
                    bestArea = c.rect.w * c.rect.h
                    hit = i
                }
            }
            if (hit >= 0) onCandidateTap?.invoke(hit)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (manualMode) return false
            if (scale > 1.2f) animateTo(1f) else animateTo(2.6f, e.x, e.y)
            return true
        }
    })

    private val scaleGesture = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = !manualMode

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val bmp = bitmap ?: return false
                val old = scale
                val next = (scale * detector.scaleFactor).coerceIn(1f, 7f)
                val focusX = detector.focusX
                val focusY = detector.focusY
                val bx = (focusX - tx) / (fit * old)
                val by = (focusY - ty) / (fit * old)
                scale = next
                tx = focusX - bx * fit * scale
                ty = focusY - by * fit * scale
                clamp()
                invalidate()
                return true
            }
        })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeFit()
        center()
        invalidate()
    }

    fun setBitmapKeepView(bmp: Bitmap?) {
        val old = _bitmap
        if (bmp != null && old != null &&
            bmp.width == old.width && bmp.height == old.height
        ) {
            _bitmap = bmp
            invalidate()
        } else {
            bitmap = bmp
        }
    }

    fun isZoomed(): Boolean = scale > 1.05f

    fun resetZoom() {
        animateTo(1f)
    }

    fun clearManualRect() {
        dragRect = null
        invalidate()
    }

    // ------------------------------------------------------------- transform

    private fun recomputeFit() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        fit = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
    }

    fun resetTransform() {
        animator?.cancel()
        scale = 1f
        recomputeFit()
        center()
    }

    private fun center() {
        val bmp = bitmap ?: return
        tx = (width - bmp.width * fit * scale) / 2f
        ty = (height - bmp.height * fit * scale) / 2f
    }

    private fun clamp() {
        val bmp = bitmap ?: return
        val dw = bmp.width * fit * scale
        val dh = bmp.height * fit * scale
        tx = if (dw <= width) (width - dw) / 2f else tx.coerceIn(width - dw, 0f)
        ty = if (dh <= height) (height - dh) / 2f else ty.coerceIn(height - dh, 0f)
    }

    private fun animateTo(targetScale: Float, focusX: Float = width / 2f, focusY: Float = height / 2f) {
        val bmp = bitmap ?: return
        animator?.cancel()
        val startScale = scale
        val startTx = tx
        val startTy = ty
        val bx = (focusX - tx) / (fit * scale)
        val by = (focusY - ty) / (fit * scale)
        val endTx = focusX - bx * fit * targetScale
        val endTy = focusY - by * fit * targetScale
        val endTxc = if (bmp.width * fit * targetScale <= width)
            (width - bmp.width * fit * targetScale) / 2f
        else endTx.coerceIn(width - bmp.width * fit * targetScale, 0f)
        val endTyc = if (bmp.height * fit * targetScale <= height)
            (height - bmp.height * fit * targetScale) / 2f
        else endTy.coerceIn(height - bmp.height * fit * targetScale, 0f)

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            addUpdateListener {
                val t = it.animatedFraction
                scale = startScale + (targetScale - startScale) * t
                tx = startTx + (endTxc - startTx) * t
                ty = startTy + (endTyc - startTy) * t
                invalidate()
            }
            start()
        }
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return

        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(fit * scale, fit * scale)
        canvas.drawBitmap(bmp, 0f, 0f, imagePaint)
        canvas.restore()

        candidates.forEachIndexed { i, c ->
            val restored = restoredRects.any { it.iou(c.rect) > 0.8f }
            val isInv = c.kind == PatchKind.INVERSION
            val color = when {
                restored -> MINT
                isInv -> AMBER
                else -> GRAY
            }
            val rf = toView(c.rect)
            val isSel = i == selected && !manualMode

            // 已还原区域不再铺色，否则会盖住刚还原出来的像素，用户没法核对效果。
            boxFill.color = when {
                restored -> Color.TRANSPARENT
                isSel -> withAlpha(color, 60)
                else -> withAlpha(color, 30)
            }
            canvas.drawRect(rf, boxFill)
            boxStroke.color = color
            boxStroke.strokeWidth = dp(if (isSel) 2.4f else 1.6f)
            if (restored) {
                dash.color = color
                dash.strokeWidth = dp(2f)
                canvas.drawRect(rf, dash)
            } else {
                canvas.drawRect(rf, boxStroke)
            }

            if (isSel) drawHandles(canvas, rf, color)

            val text = if (restored) {
                context.getString(R.string.candidate_done_indexed, i + 1)
            } else {
                val kind = context.getString(
                    if (isInv) R.string.candidate_inv else R.string.candidate_rep
                )
                context.getString(
                    R.string.candidate_label,
                    i + 1,
                    kind,
                    (c.confidence * 100).roundToInt()
                )
            }
            drawLabel(canvas, rf, text, color, darkText = restored || isInv)
        }

        manualRect?.let { r ->
            val rf = toView(r)
            boxFill.color = withAlpha(Color.WHITE, 26)
            canvas.drawRect(rf, boxFill)
            dash.color = Color.WHITE
            dash.strokeWidth = dp(1.8f)
            canvas.drawRect(rf, dash)
            drawHandles(canvas, rf, Color.WHITE)
        }

        dragRect?.let { rf ->
            dash.color = Color.WHITE
            dash.strokeWidth = dp(2f)
            canvas.drawRect(rf, dash)
            drawHandles(canvas, rf, Color.WHITE)
        }
    }

    private fun drawHandles(canvas: Canvas, rf: RectF, color: Int) {
        val s = dp(5f)
        boxFill.color = color
        for (x in floatArrayOf(rf.left, rf.right)) {
            for (y in floatArrayOf(rf.top, rf.bottom)) {
                canvas.drawRect(x - s, y - s, x + s, y + s, boxFill)
            }
        }
    }

    private fun drawLabel(canvas: Canvas, rf: RectF, text: String, color: Int, darkText: Boolean) {
        val tw = labelText.measureText(text)
        val th = labelText.textSize
        val padH = dp(6f)
        val padV = dp(3f)
        val boxW = tw + padH * 2
        val boxH = th + padV * 2
        var left = rf.left
        if (left + boxW > width - dp(2f)) left = width - boxW - dp(2f)
        left = left.coerceAtLeast(dp(2f))
        var top = rf.top - boxH - dp(3f)
        if (top < dp(2f)) top = rf.top + dp(3f)
        top = top.coerceAtMost(height - boxH - dp(2f))

        labelBg.color = color
        canvas.drawRoundRect(
            RectF(left, top, left + boxW, top + boxH),
            dp(4f), dp(4f), labelBg
        )
        labelText.color = if (darkText) Color.parseColor("#201700") else Color.WHITE
        canvas.drawText(text, left + padH, top + padV + th * 0.82f, labelText)
    }

    private fun toView(r: RectI): RectF {
        val s = fit * scale
        return RectF(tx + r.l * s, ty + r.t * s, tx + r.r * s, ty + r.b * s)
    }

    private fun toBitmap(x: Float, y: Float, bmp: Bitmap): Pair<Int, Int> {
        val s = fit * scale
        val bx = ((x - tx) / s).roundToInt().coerceIn(0, bmp.width)
        val by = ((y - ty) / s).roundToInt().coerceIn(0, bmp.height)
        return bx to by
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = bitmap ?: return false
        if (manualMode) return handleManual(event, bmp)

        scaleGesture.onTouchEvent(event)
        gesture.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleGesture.isInProgress && scale > 1.01f) {
                    tx += event.x - lastX
                    ty += event.y - lastY
                    clamp()
                    invalidate()
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastX = 0f
                lastY = 0f
            }
        }
        return true
    }

    private fun handleManual(event: MotionEvent, bmp: Bitmap): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.x.coerceIn(tx, tx + bmp.width * fit)
                dragStartY = event.y.coerceIn(ty, ty + bmp.height * fit)
                dragRect = RectF(dragStartX, dragStartY, dragStartX, dragStartY)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val endX = event.x.coerceIn(tx, tx + bmp.width * fit)
                val endY = event.y.coerceIn(ty, ty + bmp.height * fit)
                dragRect?.set(
                    min(dragStartX, endX), min(dragStartY, endY),
                    max(dragStartX, endX), max(dragStartY, endY)
                )
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val rf = dragRect
                dragRect = null
                if (rf != null) {
                    val (x0, y0) = toBitmap(rf.left, rf.top, bmp)
                    val (x1, y1) = toBitmap(rf.right, rf.bottom, bmp)
                    if (x1 - x0 >= 12 && y1 - y0 >= 12) {
                        invalidate()
                        onManualRect?.invoke(RectI(x0, y0, x1, y1))
                        return true
                    }
                }
                invalidate()
            }
        }
        return true
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (alpha shl 24) or (color and 0x00FFFFFF)

    companion object {
        private val AMBER = Color.parseColor("#FFC53D")
        private val MINT = Color.parseColor("#5CD6BE")
        private val GRAY = Color.parseColor("#9BA5B3")
    }
}
