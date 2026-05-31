package com.screenreader.app.ocr

import android.content.Context
import android.graphics.Rect
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognition
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class OcrEngine(context: Context) {

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    fun recognize(bitmap: Bitmap): Result<OcrOutput> {
        return runCatching {
            val scaledBitmap = downscale(bitmap)
            val image = InputImage.fromBitmap(scaledBitmap.bitmap, 0)
            val result = Tasks.await(recognizer.process(image))
            buildReadableResult(result, scaledBitmap.bitmap.width, scaledBitmap.bitmap.height)
        }
    }

    private fun downscale(bitmap: Bitmap): ScaledBitmap {
        val maxDimension = 1600
        val width = bitmap.width
        val height = bitmap.height
        val longestSide = maxOf(width, height)
        if (longestSide <= maxDimension) return ScaledBitmap(bitmap)

        val scale = maxDimension.toFloat() / longestSide.toFloat()
        val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
        return ScaledBitmap(Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true))
    }

    private fun buildReadableResult(result: Text, imageWidth: Int, imageHeight: Int): OcrOutput {
        val lineEntries = result.textBlocks
            .flatMap { block ->
                block.lines.mapNotNull { line ->
                    val bounds = line.boundingBox ?: return@mapNotNull null
                    val text = line.text.normalizeOcrText()
                    if (text.isBlank()) return@mapNotNull null
                    LineEntry(
                        text = text,
                        bounds = bounds
                    )
                }
            }

        if (lineEntries.isEmpty()) {
            return OcrOutput(result.text.trim(), OcrDebugSnapshot(imageWidth, imageHeight, emptyList(), emptyList()))
        }

        val baselineHeight = lineEntries.map { it.bounds.height() }.medianOrAverage().coerceAtLeast(1.0)

        val rows = mutableListOf<MutableList<LineEntry>>()
        for (entry in lineEntries.sortedBy { it.bounds.centerY() }) {
            val row = rows.firstOrNull { existing ->
                val localHeight = max(existing.averageHeight(), entry.height.toDouble())
                val localRowTolerance = max(localHeight * 0.72, baselineHeight * 0.5).roundToInt().coerceAtLeast(24)
                abs(existing.averageCenterY() - entry.bounds.centerY()) <= localRowTolerance
            }
            if (row == null) {
                rows += mutableListOf(entry)
            } else {
                row += entry
            }
        }

        val orderedRows = rows
            .map { row -> row.sortedBy { it.bounds.left } }
            .sortedBy { row -> row.minOf { it.bounds.top } }

        val paragraphs = mutableListOf<ParagraphEntry>()
        var previousRowBottom: Int? = null
        var previousRowRight: Int? = null
        var previousRowHeight: Double? = null

        for (row in orderedRows) {
            val rowTop = row.minOf { it.bounds.top }
            val rowBottom = row.maxOf { it.bounds.bottom }
            val rowLeft = row.minOf { it.bounds.left }
            val rowHeight = row.averageHeight()
            val paragraphGap = max(max(previousRowHeight ?: rowHeight, rowHeight) * 3.6, baselineHeight * 2.8)
                .roundToInt()
                .coerceAtLeast(108)
            val columnGap = max(rowHeight * 5.8, baselineHeight * 4.6)
                .roundToInt()
                .coerceAtLeast(260)
            val rowText = buildRowText(row, columnGap)
            if (rowText.isBlank()) continue
            val rowBounds = row.unionBounds()

            val startsNewParagraph =
                previousRowBottom == null ||
                    rowTop - previousRowBottom > paragraphGap ||
                    (previousRowRight != null && rowLeft + columnGap < previousRowRight)

            if (startsNewParagraph) {
                paragraphs += ParagraphEntry(rowText, rowBounds)
            } else {
                val previousParagraph = paragraphs.removeLast()
                paragraphs += ParagraphEntry(
                    text = mergeParagraph(previousParagraph.text, rowText),
                    bounds = previousParagraph.bounds.unionWith(rowBounds)
                )
            }

            previousRowBottom = rowBottom
            previousRowRight = row.maxOf { it.bounds.right }
            previousRowHeight = rowHeight
        }

        return OcrOutput(
            text = paragraphs.joinToString(separator = "\n\n") { it.text }.trim(),
            debugSnapshot = OcrDebugSnapshot(
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                lineBounds = lineEntries.map { Rect(it.bounds) },
                paragraphBounds = paragraphs.map { Rect(it.bounds) }
            )
        )
    }

    private fun buildRowText(row: List<LineEntry>, columnGap: Int): String {
        val pieces = mutableListOf<String>()
        var current = StringBuilder()
        var previousBounds: Rect? = null

        for (entry in row) {
            val previous = previousBounds
            if (previous != null && entry.bounds.left - previous.right > columnGap) {
                val text = current.toString().trim()
                if (text.isNotEmpty()) {
                    pieces += text
                }
                current = StringBuilder()
            }

            if (current.isNotEmpty() && shouldInsertSpace(current, entry.text)) {
                current.append(' ')
            }
            current.append(entry.text)
            previousBounds = entry.bounds
        }

        val lastText = current.toString().trim()
        if (lastText.isNotEmpty()) {
            pieces += lastText
        }

        return pieces.joinToString(separator = "，")
    }

    private fun mergeParagraph(existing: String, next: String): String {
        val trimmedExisting = existing.trimEnd()
        val separator = when {
            trimmedExisting.endsWithChinesePunctuation() || trimmedExisting.endsWith('.') -> " "
            trimmedExisting.endsWith(',') || trimmedExisting.endsWith('，') -> " "
            else -> " "
        }
        return trimmedExisting + separator + next.trimStart()
    }

    private fun shouldInsertSpace(current: StringBuilder, next: String): Boolean {
        val previousChar = current.lastOrNull() ?: return false
        val nextChar = next.firstOrNull() ?: return false
        if (previousChar.isWhitespace() || nextChar.isWhitespace()) return false
        return previousChar.isAsciiLetterOrDigit() && nextChar.isAsciiLetterOrDigit()
    }

    private fun String.normalizeOcrText(): String {
        return replace("\\s+".toRegex(), " ").trim()
    }

    private fun String.endsWithChinesePunctuation(): Boolean {
        return endsWith('。') || endsWith('！') || endsWith('？') || endsWith('：') || endsWith('；')
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean {
        return this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
    }

    private fun <T> MutableList<T>.removeLast(): T = removeAt(lastIndex)

    private fun List<LineEntry>.averageCenterY(): Int {
        return map { it.bounds.centerY() }.average().toInt()
    }

    private fun List<LineEntry>.averageHeight(): Double {
        return map { it.height }.average().takeIf { !it.isNaN() } ?: 0.0
    }

    private fun List<LineEntry>.unionBounds(): Rect {
        val left = minOf { it.bounds.left }
        val top = minOf { it.bounds.top }
        val right = maxOf { it.bounds.right }
        val bottom = maxOf { it.bounds.bottom }
        return Rect(left, top, right, bottom)
    }

    private fun List<Int>.medianOrAverage(): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid].toDouble()
        }
    }

    private data class LineEntry(
        val text: String,
        val bounds: Rect
    ) {
        val height: Int
            get() = bounds.height()
    }

    private data class ParagraphEntry(
        val text: String,
        val bounds: Rect
    )

    private data class ScaledBitmap(
        val bitmap: Bitmap
    )

    private fun Rect.unionWith(other: Rect): Rect {
        return Rect(
            minOf(left, other.left),
            minOf(top, other.top),
            maxOf(right, other.right),
            maxOf(bottom, other.bottom)
        )
    }
}
