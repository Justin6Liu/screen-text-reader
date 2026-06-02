package com.screenreader.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import com.screenreader.app.ocr.OcrDebugSnapshot

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

    private val referenceParagraphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 64, 180)
        style = Paint.Style.STROKE
        strokeWidth = 5f
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
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

        val scaleX = width.toFloat() / data.imageWidth.toFloat()
        val scaleY = height.toFloat() / data.imageHeight.toFloat()

        data.lineBounds.forEach { rect ->
            val scaledRect = rect.toScaledRectF(scaleX, scaleY, rectBuffer)
            canvas.drawRect(scaledRect, lineFillPaint)
            canvas.drawRect(scaledRect, linePaint)
        }
        data.paragraphBounds.forEach { rect ->
            canvas.drawRect(rect.toScaledRectF(scaleX, scaleY, rectBuffer), paragraphPaint)
        }
        data.referenceParagraphBounds.forEach { rect ->
            canvas.drawRect(rect.toScaledRectF(scaleX, scaleY, rectBuffer), referenceParagraphPaint)
        }

        drawLegend(canvas, data)
    }

    private fun drawLegend(canvas: Canvas, data: OcrDebugSnapshot) {
        val text = "Debug OCR  ${data.sourceLabel}  image=${data.imageWidth}x${data.imageHeight} view=${width}x${height}"
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

    private fun Rect.toScaledRectF(scaleX: Float, scaleY: Float, outRect: RectF): RectF {
        outRect.set(
            left * scaleX,
            top * scaleY,
            right * scaleX,
            bottom * scaleY
        )
        return outRect
    }
}
