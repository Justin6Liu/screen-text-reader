package com.screenreader.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
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
            val originalResult = processVariant(
                ImageVariant(bitmap = scaledBitmap.bitmap, offsetY = 0),
                scaledBitmap.bitmap.width,
                scaledBitmap.bitmap.height
            )
            val enhancedResult = processVariant(
                ImageVariant(
                    bitmap = enhanceForOcr(scaledBitmap.bitmap),
                    offsetY = 0
                ),
                scaledBitmap.bitmap.width,
                scaledBitmap.bitmap.height
            )
            chooseBetterResult(originalResult, enhancedResult).output
        }
    }

    private fun downscale(bitmap: Bitmap): ScaledBitmap {
        val maxDimension = when {
            minOf(bitmap.width, bitmap.height) >= 1440 -> 2400
            minOf(bitmap.width, bitmap.height) >= 1080 -> 2200
            else -> 1800
        }
        val width = bitmap.width
        val height = bitmap.height
        val longestSide = maxOf(width, height)
        if (longestSide <= maxDimension) return ScaledBitmap(bitmap)

        val scale = maxDimension.toFloat() / longestSide.toFloat()
        val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
        return ScaledBitmap(Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true))
    }

    private fun processVariant(
        imageVariant: ImageVariant,
        fullImageWidth: Int,
        fullImageHeight: Int
    ): ProcessedOcrResult {
        val image = InputImage.fromBitmap(imageVariant.bitmap, 0)
        val result = Tasks.await(recognizer.process(image))
        return buildReadableResult(result, fullImageWidth, fullImageHeight, imageVariant.offsetY)
    }

    private fun buildReadableResult(
        result: Text,
        imageWidth: Int,
        imageHeight: Int,
        offsetY: Int
    ): ProcessedOcrResult {
        val lineEntries = result.textBlocks
            .flatMap { block ->
                block.lines.mapNotNull { line ->
                    val bounds = line.boundingBox ?: return@mapNotNull null
                    val text = line.text.normalizeOcrText()
                    if (text.isBlank()) return@mapNotNull null
                    LineEntry(
                        text = text,
                        bounds = bounds.offsetBy(0, offsetY)
                    )
                }
            }
            .filterNot { entry -> shouldIgnoreLine(entry, imageWidth, imageHeight) }

        if (lineEntries.isEmpty()) {
            return ProcessedOcrResult(
                output = OcrOutput(
                    result.text.trim(),
                    OcrDebugSnapshot(imageWidth, imageHeight, emptyList(), emptyList())
                ),
                score = result.text.countMeaningfulChars()
            )
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

        val regions = mutableListOf<ParagraphEntry>()
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
                regions += ParagraphEntry(rowText, rowBounds, rowHeight)
            } else {
                val previousRegion = regions.removeLast()
                regions += ParagraphEntry(
                    text = mergeParagraph(previousRegion.text, rowText),
                    bounds = previousRegion.bounds.unionWith(rowBounds),
                    averageHeight = mergeAverageHeight(previousRegion.averageHeight, rowHeight)
                )
            }

            previousRowBottom = rowBottom
            previousRowRight = row.maxOf { it.bounds.right }
            previousRowHeight = rowHeight
        }

        val paragraphs = mergeRegionsIntoParagraphs(regions, baselineHeight)

        val finalText = paragraphs.joinToString(separator = "\n\n") { it.text }.trim()
        return ProcessedOcrResult(
            output = OcrOutput(
                text = finalText,
                debugSnapshot = OcrDebugSnapshot(
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    lineBounds = lineEntries.map { Rect(it.bounds) },
                    paragraphBounds = paragraphs.map { Rect(it.bounds) }
                )
            ),
            score = finalText.countMeaningfulChars() + lineEntries.size * 4 + paragraphs.size * 8
        )
    }

    private fun enhanceForOcr(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val sourcePixels = IntArray(width * height)
        val outputPixels = IntArray(width * height)
        bitmap.getPixels(sourcePixels, 0, width, 0, 0, width, height)

        for (index in sourcePixels.indices) {
            val pixel = sourcePixels[index]
            val alpha = Color.alpha(pixel)
            val grayscale = Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114
            val contrasted = ((grayscale - 128.0) * 1.22 + 134.0).coerceIn(0.0, 255.0)
            val leveled = if (contrasted >= 180.0) contrasted + 10.0 else contrasted - 6.0
            val level = leveled.coerceIn(0.0, 255.0).roundToInt()
            outputPixels[index] = Color.argb(alpha, level, level, level)
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(outputPixels, 0, width, 0, 0, width, height)
        }
    }

    private fun chooseBetterResult(first: ProcessedOcrResult, second: ProcessedOcrResult): ProcessedOcrResult {
        return if (second.score > first.score) second else first
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

    private fun mergeRegionsIntoParagraphs(
        regions: List<ParagraphEntry>,
        baselineHeight: Double
    ): List<ParagraphEntry> {
        if (regions.isEmpty()) return emptyList()

        val merged = mutableListOf<ParagraphEntry>()
        for (region in regions.sortedBy { it.bounds.top }) {
            val previous = merged.lastOrNull()
            if (previous == null) {
                merged += region
                continue
            }

            val verticalGap = region.bounds.top - previous.bounds.bottom
            val localHeight = max(previous.averageHeight, region.averageHeight)
            val mergeGap = max(localHeight * 1.9, baselineHeight * 1.5).roundToInt().coerceAtLeast(56)
            val leftAligned = abs(region.bounds.left - previous.bounds.left) <= max(localHeight * 2.2, 72.0).roundToInt()
            val horizontalOverlap = horizontalOverlap(previous.bounds, region.bounds)
            val overlapWideEnough = horizontalOverlap >= minOf(previous.bounds.width(), region.bounds.width()) * 0.28

            if (verticalGap <= mergeGap && (leftAligned || overlapWideEnough)) {
                merged[merged.lastIndex] = ParagraphEntry(
                    text = mergeParagraph(previous.text, region.text),
                    bounds = previous.bounds.unionWith(region.bounds),
                    averageHeight = mergeAverageHeight(previous.averageHeight, region.averageHeight)
                )
            } else {
                merged += region
            }
        }
        return merged
    }

    private fun shouldIgnoreLine(entry: LineEntry, imageWidth: Int, imageHeight: Int): Boolean {
        val topCutoff = (imageHeight * 0.085).roundToInt()
        val bottomCutoff = (imageHeight * 0.94).roundToInt()
        val edgeInset = (imageWidth * 0.045).roundToInt().coerceAtLeast(18)
        val tinyHeight = (imageHeight * 0.018).roundToInt().coerceAtLeast(18)
        val tinyWidth = (imageWidth * 0.10).roundToInt().coerceAtLeast(72)

        val isTopBarLike =
            entry.bounds.top <= topCutoff &&
                entry.height <= tinyHeight * 1.3 &&
                entry.bounds.width() <= imageWidth * 0.28

        val isBottomEdgeJunk =
            entry.bounds.bottom >= bottomCutoff &&
                entry.height <= tinyHeight * 1.2 &&
                entry.bounds.width() <= imageWidth * 0.22

        val isTinyEdgeText =
            entry.height <= tinyHeight &&
                entry.bounds.width() <= tinyWidth &&
                (entry.bounds.left <= edgeInset || entry.bounds.right >= imageWidth - edgeInset)

        val isStatusBarPattern =
            entry.bounds.top <= topCutoff &&
                STATUS_BAR_PATTERNS.any { pattern -> pattern.matches(entry.text) }

        val isVeryShortEdgeGarbage =
            entry.text.length <= 2 &&
                entry.height <= tinyHeight &&
                (entry.bounds.left <= edgeInset || entry.bounds.right >= imageWidth - edgeInset)

        return isTopBarLike || isBottomEdgeJunk || isTinyEdgeText || isStatusBarPattern || isVeryShortEdgeGarbage
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

    private fun String.countMeaningfulChars(): Int {
        return count { !it.isWhitespace() && it != '，' && it != '。' }
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

    private fun horizontalOverlap(first: Rect, second: Rect): Int {
        return minOf(first.right, second.right) - maxOf(first.left, second.left)
    }

    private fun mergeAverageHeight(first: Double, second: Double): Double {
        return (first + second) / 2.0
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
        val bounds: Rect,
        val averageHeight: Double
    )

    private data class ScaledBitmap(
        val bitmap: Bitmap
    )

    private data class ImageVariant(
        val bitmap: Bitmap,
        val offsetY: Int
    )

    private data class ProcessedOcrResult(
        val output: OcrOutput,
        val score: Int
    )

    private fun Rect.unionWith(other: Rect): Rect {
        return Rect(
            minOf(left, other.left),
            minOf(top, other.top),
            maxOf(right, other.right),
            maxOf(bottom, other.bottom)
        )
    }

    private fun Rect.offsetBy(dx: Int, dy: Int): Rect {
        return Rect(left + dx, top + dy, right + dx, bottom + dy)
    }

    companion object {
        private val STATUS_BAR_PATTERNS = listOf(
            Regex("""^\d{1,2}:\d{2}$"""),
            Regex("""^\d{1,2}:\d{2}\s?[AP]M$""", RegexOption.IGNORE_CASE),
            Regex("""^\d+%$"""),
            Regex("""^(5G|4G|LTE|VoLTE|HD|NFC|VPN|Wi-?Fi)$""", RegexOption.IGNORE_CASE)
        )
    }
}
