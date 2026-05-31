package com.screenreader.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import com.screenreader.app.ocr.OcrDebugSnapshot
import kotlin.math.min

class OcrDebugOverlayView(context: Context) : View(context) {

    private val lineFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 57, 219, 214)
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 57, 219, 214)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val paragraphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 171, 64)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 18, 28, 33)
        style = Paint.Style.FILL
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
    }

    private val rectBuffer = RectF()
    private var snapshot: OcrDebugSnapshot? = null

    fun updateSnapshot(snapshot: OcrDebugSnapshot?) {
        this.snapshot = snapshot
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = snapshot ?: return
        if (data.imageWidth <= 0 || data.imageHeight <= 0) return

        val scale = min(width.toFloat() / data.imageWidth.toFloat(), height.toFloat() / data.imageHeight.toFloat())
        val offsetX = (width - data.imageWidth * scale) / 2f
        val offsetY = (height - data.imageHeight * scale) / 2f

        data.lineBounds.forEach { rect ->
            val scaledRect = rect.toScaledRectF(scale, offsetX, offsetY, rectBuffer)
            canvas.drawRect(scaledRect, lineFillPaint)
            canvas.drawRect(scaledRect, linePaint)
        }
        data.paragraphBounds.forEach { rect ->
            canvas.drawRect(rect.toScaledRectF(scale, offsetX, offsetY, rectBuffer), paragraphPaint)
        }

        drawLegend(canvas)
    }

    private fun drawLegend(canvas: Canvas) {
        val text = "Debug OCR  Orange=paragraph  Cyan=line"
        val paddingHorizontal = 24f
        val paddingVertical = 16f
        val textWidth = labelTextPaint.measureText(text)
        val textHeight = labelTextPaint.textSize
        val left = 24f
        val top = 24f
        val right = left + textWidth + paddingHorizontal * 2
        val bottom = top + textHeight + paddingVertical * 2
        canvas.drawRoundRect(RectF(left, top, right, bottom), 20f, 20f, labelBackgroundPaint)
        canvas.drawText(text, left + paddingHorizontal, bottom - paddingVertical, labelTextPaint)
    }

    private fun Rect.toScaledRectF(scale: Float, offsetX: Float, offsetY: Float, outRect: RectF): RectF {
        outRect.set(
            left * scale + offsetX,
            top * scale + offsetY,
            right * scale + offsetX,
            bottom * scale + offsetY
        )
        return outRect
    }
}
