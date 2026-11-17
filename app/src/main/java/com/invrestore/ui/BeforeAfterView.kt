package com.invrestore.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * 前后对比：左半屏画 before，右半屏画 after，中间一条可拖动的分界线。
 * 两张图尺寸相同，共用同一个 fitCenter 目标矩形，所以分界线两侧严丝合缝。
 */
class BeforeAfterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var before: Bitmap? = null
        set(value) { field = value; invalidate() }
    var after: Bitmap? = null
        set(value) { field = value; invalidate() }

    var split: Float = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var labelBefore: String? = null
    var labelAfter: String? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10.5f)
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE
    }
    private val arrow = Path()

    private val dst = RectF()
    private var dragging = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeDst()
    }

    private fun computeDst() {
        val bmp = after ?: before ?: return
        if (width == 0 || height == 0 || bmp.width == 0 || bmp.height == 0) return
        val s = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val dw = bmp.width * s
        val dh = bmp.height * s
        dst.set(
            (width - dw) / 2f, (height - dh) / 2f,
            (width + dw) / 2f, (height + dh) / 2f
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = before
        val a = after
        if (b == null && a == null) return
        if (dst.isEmpty) computeDst()
        if (dst.isEmpty) return

        canvas.save()
        canvas.clipRect(dst)
        b?.let { canvas.drawBitmap(it, null, dst, paint) }
        if (a != null) {
            val splitX = dst.left + dst.width() * split
            canvas.save()
            canvas.clipRect(splitX, dst.top, dst.right, dst.bottom)
            canvas.drawBitmap(a, null, dst, paint)
            canvas.restore()
        }
        canvas.restore()

        if (a == null) return

        val x = dst.left + dst.width() * split
        val cy = dst.centerY()

        line.color = Color.parseColor("#66000000")
        line.strokeWidth = dp(4f)
        canvas.drawLine(x, dst.top, x, dst.bottom, line)
        line.color = Color.WHITE
        line.strokeWidth = dp(1.8f)
        canvas.drawLine(x, dst.top, x, dst.bottom, line)

        knob.color = Color.parseColor("#33000000")
        canvas.drawCircle(x, cy, dp(15f), knob)
        knob.color = Color.WHITE
        canvas.drawCircle(x, cy, dp(12.5f), knob)

        arrow.reset()
        arrow.moveTo(x - dp(4.6f), cy)
        arrow.lineTo(x - dp(1.3f), cy - dp(3.2f))
        arrow.lineTo(x - dp(1.3f), cy + dp(3.2f))
        arrow.close()
        arrow.moveTo(x + dp(4.6f), cy)
        arrow.lineTo(x + dp(1.3f), cy - dp(3.2f))
        arrow.lineTo(x + dp(1.3f), cy + dp(3.2f))
        arrow.close()
        knob.color = Color.parseColor("#1A1D23")
        canvas.drawPath(arrow, knob)

        drawLabel(canvas, labelBefore, dst.left + dp(10f), dst.top + dp(10f), true)
        drawLabel(canvas, labelAfter, dst.right - dp(10f), dst.top + dp(10f), false)
    }

    private fun drawLabel(canvas: Canvas, label: String?, x: Float, y: Float, alignStart: Boolean) {
        if (label.isNullOrEmpty()) return
        val tw = text.measureText(label)
        val padH = dp(6f)
        val h = dp(18f)
        val w = tw + padH * 2
        val left = if (alignStart) x else x - w
        chip.color = Color.parseColor("#CC0B0E13")
        canvas.drawRoundRect(RectF(left, y, left + w, y + h), h / 2, h / 2, chip)
        canvas.drawText(label, left + padH, y + h * 0.7f, text)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (after == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                updateSplit(event.x)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) updateSplit(event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateSplit(x: Float) {
        if (dst.width() <= 0f) return
        split = (x - dst.left) / dst.width()
    }
}
