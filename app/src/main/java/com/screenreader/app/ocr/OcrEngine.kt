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
            val sourceBitmap = scaledBitmap.bitmap
            val fullWidth = sourceBitmap.width
            val fullHeight = sourceBitmap.height

            val globalResult = listOf(
                ImageVariant(bitmap = sourceBitmap, label = "full screen"),
                ImageVariant(bitmap = enhanceForOcr(sourceBitmap), label = "full screen enhanced"),
                createUpscaledVariant(sourceBitmap, UPSCALE_FACTOR, "full screen upscaled"),
                createUpscaledVariant(enhanceForOcr(sourceBitmap), UPSCALE_FACTOR, "full screen enhanced upscaled")
            ).filterNotNull()
                .map { variant -> processVariant(variant, fullWidth, fullHeight) }
                .maxByOrNull { it.score }
                ?: ProcessedOcrResult.empty(fullWidth, fullHeight)

            val regionResult = rerunParagraphRegions(sourceBitmap, globalResult, fullWidth, fullHeight)
            chooseBetterResult(globalResult, regionResult).output
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
        return buildReadableResult(result, fullImageWidth, fullImageHeight, imageVariant)
    }

    private fun buildReadableResult(
        result: Text,
        imageWidth: Int,
        imageHeight: Int,
        imageVariant: ImageVariant
    ): ProcessedOcrResult {
        val lineEntries = result.textBlocks
            .flatMap { block ->
                block.lines.mapNotNull { line ->
                    val bounds = line.boundingBox ?: return@mapNotNull null
                    val text = line.text.normalizeOcrText()
                    if (text.isBlank()) return@mapNotNull null
                    LineEntry(
                        text = text,
                        bounds = bounds.mapToFullImage(imageVariant)
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
                regions += ParagraphEntry(
                    text = rowText,
                    bounds = rowBounds,
                    averageHeight = rowHeight,
                    rows = listOf(ReadRow(rowText, rowBounds))
                )
            } else {
                val previousRegion = regions.removeLast()
                regions += ParagraphEntry(
                    text = mergeParagraph(previousRegion.text, rowText),
                    bounds = previousRegion.bounds.unionWith(rowBounds),
                    averageHeight = mergeAverageHeight(previousRegion.averageHeight, rowHeight),
                    rows = previousRegion.rows + ReadRow(rowText, rowBounds)
                )
            }

            previousRowBottom = rowBottom
            previousRowRight = row.maxOf { it.bounds.right }
            previousRowHeight = rowHeight
        }

        val paragraphs = mergeRegionsIntoParagraphs(regions, baselineHeight)

        val readableText = buildReadableTextWithSegments(paragraphs)
        return ProcessedOcrResult(
            output = OcrOutput(
                text = readableText.text,
                debugSnapshot = OcrDebugSnapshot(
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    lineBounds = lineEntries.map { Rect(it.bounds) },
                    paragraphBounds = paragraphs.map { Rect(it.bounds) },
                    sourceLabel = imageVariant.label
                ),
                readSegments = readableText.segments
            ),
            score = readableText.text.scoreReadableText(lineEntries.size, paragraphs.size),
            paragraphBounds = paragraphs.map { Rect(it.bounds) }
        )
    }

    private fun rerunParagraphRegions(
        sourceBitmap: Bitmap,
        globalResult: ProcessedOcrResult,
        fullImageWidth: Int,
        fullImageHeight: Int
    ): ProcessedOcrResult {
        val significantBounds = globalResult.paragraphBounds
            .filter { bounds ->
                bounds.width() >= fullImageWidth * 0.25 &&
                    bounds.height() >= fullImageHeight * 0.035
            }

        if (significantBounds.isEmpty() || significantBounds.size > MAX_REGION_RERUNS) {
            return globalResult
        }

        val regionResults = significantBounds.mapNotNull { bounds ->
            val cropBounds = bounds.expandWithin(
                sourceBitmap.width,
                sourceBitmap.height,
                horizontalPadding = (bounds.width() * 0.08).roundToInt().coerceAtLeast(12),
                verticalPadding = (bounds.height() * 0.18).roundToInt().coerceAtLeast(16)
            )
            val crop = Bitmap.createBitmap(
                sourceBitmap,
                cropBounds.left,
                cropBounds.top,
                cropBounds.width(),
                cropBounds.height()
            )
            val upscaledCrop = upscaleRegionIfUseful(crop)
            val variant = ImageVariant(
                bitmap = enhanceForOcr(upscaledCrop.bitmap),
                offsetX = cropBounds.left,
                offsetY = cropBounds.top,
                scaleXToFull = cropBounds.width().toFloat() / upscaledCrop.bitmap.width.toFloat(),
                scaleYToFull = cropBounds.height().toFloat() / upscaledCrop.bitmap.height.toFloat(),
                label = "paragraph region rerun"
            )
            RegionOcrResult(
                cropBounds = cropBounds,
                result = processVariant(variant, fullImageWidth, fullImageHeight)
            ).takeIf { it.result.output.text.isNotBlank() }
        }

        if (regionResults.isEmpty()) return globalResult

        val combinedReadableText = combineRegionReadableText(regionResults)
        val combinedLineBounds = regionResults.flatMap { it.result.output.debugSnapshot?.lineBounds ?: emptyList() }
        val combinedParagraphBounds = regionResults.flatMap { it.result.paragraphBounds }
        val cropBounds = regionResults.map { Rect(it.cropBounds) }
        val combinedScore = combinedReadableText.text.scoreReadableText(
            lineCount = combinedLineBounds.size,
            paragraphCount = combinedParagraphBounds.size
        ) + REGION_RERUN_SCORE_BONUS

        return ProcessedOcrResult(
            output = OcrOutput(
                text = combinedReadableText.text,
                debugSnapshot = OcrDebugSnapshot(
                    imageWidth = fullImageWidth,
                    imageHeight = fullImageHeight,
                    lineBounds = combinedLineBounds,
                    paragraphBounds = combinedParagraphBounds,
                    sourceLabel = "paragraph region rerun",
                    referenceParagraphBounds = cropBounds
                ),
                readSegments = combinedReadableText.segments
            ),
            score = combinedScore,
            paragraphBounds = combinedParagraphBounds
        )
    }

    private fun combineRegionReadableText(regionResults: List<RegionOcrResult>): ReadableText {
        val builder = StringBuilder()
        val segments = mutableListOf<OcrReadSegment>()

        regionResults.forEachIndexed { index, regionResult ->
            val output = regionResult.result.output
            if (output.text.isBlank()) return@forEachIndexed
            if (index > 0 && builder.isNotEmpty()) {
                builder.append("\n\n")
            }

            val offset = builder.length
            builder.append(output.text)
            output.readSegments.forEach { segment ->
                segments += OcrReadSegment(
                    text = segment.text,
                    bounds = Rect(segment.bounds),
                    startIndex = offset + segment.startIndex,
                    endIndex = offset + segment.endIndex
                )
            }
        }

        return ReadableText(builder.toString().trim(), segments)
    }

    private fun createUpscaledVariant(bitmap: Bitmap, factor: Float, label: String): ImageVariant? {
        val longestSide = maxOf(bitmap.width, bitmap.height)
        if (longestSide >= MAX_UPSCALED_DIMENSION) return null

        val scale = minOf(factor, MAX_UPSCALED_DIMENSION.toFloat() / longestSide.toFloat())
        if (scale <= 1.05f) return null

        val scaledWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return ImageVariant(
            bitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true),
            scaleXToFull = bitmap.width.toFloat() / scaledWidth.toFloat(),
            scaleYToFull = bitmap.height.toFloat() / scaledHeight.toFloat(),
            label = label
        )
    }

    private fun upscaleRegionIfUseful(bitmap: Bitmap): ScaledBitmap {
        val longestSide = maxOf(bitmap.width, bitmap.height)
        if (longestSide >= REGION_TARGET_LONG_SIDE) return ScaledBitmap(bitmap)

        val scale = minOf(
            REGION_TARGET_LONG_SIDE.toFloat() / longestSide.toFloat(),
            REGION_MAX_UPSCALE
        )
        if (scale <= 1.05f) return ScaledBitmap(bitmap)

        return ScaledBitmap(
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
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
        return if (second.score > first.score + SCORE_SWITCH_MARGIN) second else first
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
                    averageHeight = mergeAverageHeight(previous.averageHeight, region.averageHeight),
                    rows = previous.rows + region.rows
                )
            } else {
                merged += region
            }
        }
        return merged
    }

    private fun buildReadableTextWithSegments(paragraphs: List<ParagraphEntry>): ReadableText {
        val builder = StringBuilder()
        val rowsWithRanges = mutableListOf<ReadRowWithRange>()

        paragraphs.forEachIndexed { paragraphIndex, paragraph ->
            if (paragraphIndex > 0) {
                builder.append("\n\n")
            }

            paragraph.rows.forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) {
                    builder.append(' ')
                }

                val start = builder.length
                builder.append(row.text)
                val end = builder.length
                if (start < end) {
                    rowsWithRanges += ReadRowWithRange(
                        text = row.text,
                        bounds = row.bounds,
                        startIndex = start,
                        endIndex = end
                    )
                }
            }
        }

        val text = builder.toString().trim()
        return ReadableText(text, buildPhraseSegments(text, rowsWithRanges))
    }

    private fun buildPhraseSegments(text: String, rows: List<ReadRowWithRange>): List<OcrReadSegment> {
        if (text.isBlank() || rows.isEmpty()) return emptyList()

        val segments = mutableListOf<OcrReadSegment>()
        var phraseStart = 0
        var phraseRows = mutableListOf<ReadRowWithRange>()

        for (row in rows) {
            phraseRows += row
            val phraseText = text.substring(phraseStart, row.endIndex).trim()
            val shouldClosePhrase =
                phraseText.endsWithSentenceBoundary() ||
                    phraseText.length >= MAX_PHRASE_SEGMENT_CHARS ||
                    row.endsBeforeParagraphBreak(text)

            if (shouldClosePhrase) {
                addPhraseSegment(text, phraseStart, row.endIndex, phraseRows, segments)
                phraseStart = nextNonWhitespaceIndex(text, row.endIndex)
                phraseRows = mutableListOf()
            }
        }

        if (phraseRows.isNotEmpty()) {
            addPhraseSegment(text, phraseStart, phraseRows.last().endIndex, phraseRows, segments)
        }

        return segments
    }

    private fun addPhraseSegment(
        fullText: String,
        start: Int,
        end: Int,
        rows: List<ReadRowWithRange>,
        segments: MutableList<OcrReadSegment>
    ) {
        val safeStart = start.coerceIn(0, fullText.length)
        val safeEnd = end.coerceIn(safeStart, fullText.length)
        val text = fullText.substring(safeStart, safeEnd).trim()
        if (text.isBlank() || rows.isEmpty()) return

        segments += OcrReadSegment(
            text = text,
            bounds = rows.map { it.bounds }.unionRectBounds(),
            startIndex = safeStart,
            endIndex = safeEnd
        )
    }

    private fun nextNonWhitespaceIndex(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index].isWhitespace()) {
            index++
        }
        return index
    }

    private fun ReadRowWithRange.endsBeforeParagraphBreak(text: String): Boolean {
        return text.substring(endIndex, minOf(text.length, endIndex + 2)).contains("\n\n")
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

    private fun String.scoreReadableText(lineCount: Int, paragraphCount: Int): Int {
        val meaningfulChars = countMeaningfulChars()
        val chineseChars = count { it.isChineseCharacter() }
        val suspiciousChars = count { it.isSuspiciousOcrCharacter() }
        return meaningfulChars +
            chineseChars * 2 +
            lineCount * 4 +
            paragraphCount * 8 -
            suspiciousChars * 6
    }

    private fun String.endsWithChinesePunctuation(): Boolean {
        return endsWith('。') || endsWith('！') || endsWith('？') || endsWith('：') || endsWith('；')
    }

    private fun String.endsWithSentenceBoundary(): Boolean {
        val last = trimEnd().lastOrNull() ?: return false
        return last == '。' || last == '！' || last == '？' ||
            last == '.' || last == '!' || last == '?' ||
            last == '；' || last == ';'
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean {
        return this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
    }

    private fun Char.isChineseCharacter(): Boolean {
        return this in '\u4e00'..'\u9fff'
    }

    private fun Char.isSuspiciousOcrCharacter(): Boolean {
        return this == '\uFFFD' || this == '□' || this == '■'
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

    private fun List<Rect>.unionRectBounds(): Rect {
        val left = minOf { it.left }
        val top = minOf { it.top }
        val right = maxOf { it.right }
        val bottom = maxOf { it.bottom }
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
        val averageHeight: Double,
        val rows: List<ReadRow>
    )

    private data class ReadRow(
        val text: String,
        val bounds: Rect
    )

    private data class ReadRowWithRange(
        val text: String,
        val bounds: Rect,
        val startIndex: Int,
        val endIndex: Int
    )

    private data class ReadableText(
        val text: String,
        val segments: List<OcrReadSegment>
    )

    private data class ScaledBitmap(
        val bitmap: Bitmap
    )

    private data class ImageVariant(
        val bitmap: Bitmap,
        val offsetX: Int = 0,
        val offsetY: Int = 0,
        val scaleXToFull: Float = 1.0f,
        val scaleYToFull: Float = 1.0f,
        val label: String = "full screen"
    )

    private data class RegionOcrResult(
        val cropBounds: Rect,
        val result: ProcessedOcrResult
    )

    private data class ProcessedOcrResult(
        val output: OcrOutput,
        val score: Int,
        val paragraphBounds: List<Rect> = emptyList()
    ) {
        companion object {
            fun empty(imageWidth: Int, imageHeight: Int): ProcessedOcrResult {
                return ProcessedOcrResult(
                    output = OcrOutput(
                        text = "",
                        debugSnapshot = OcrDebugSnapshot(
                            imageWidth = imageWidth,
                            imageHeight = imageHeight,
                            lineBounds = emptyList(),
                            paragraphBounds = emptyList()
                        )
                    ),
                    score = 0
                )
            }
        }
    }

    private fun Rect.unionWith(other: Rect): Rect {
        return Rect(
            minOf(left, other.left),
            minOf(top, other.top),
            maxOf(right, other.right),
            maxOf(bottom, other.bottom)
        )
    }

    private fun Rect.mapToFullImage(imageVariant: ImageVariant): Rect {
        return Rect(
            (left * imageVariant.scaleXToFull).roundToInt() + imageVariant.offsetX,
            (top * imageVariant.scaleYToFull).roundToInt() + imageVariant.offsetY,
            (right * imageVariant.scaleXToFull).roundToInt() + imageVariant.offsetX,
            (bottom * imageVariant.scaleYToFull).roundToInt() + imageVariant.offsetY
        )
    }

    private fun Rect.expandWithin(
        imageWidth: Int,
        imageHeight: Int,
        horizontalPadding: Int,
        verticalPadding: Int
    ): Rect {
        return Rect(
            (left - horizontalPadding).coerceAtLeast(0),
            (top - verticalPadding).coerceAtLeast(0),
            (right + horizontalPadding).coerceAtMost(imageWidth),
            (bottom + verticalPadding).coerceAtMost(imageHeight)
        )
    }

    companion object {
        private const val UPSCALE_FACTOR = 1.32f
        private const val MAX_UPSCALED_DIMENSION = 2800
        private const val REGION_TARGET_LONG_SIDE = 1900
        private const val REGION_MAX_UPSCALE = 1.7f
        private const val MAX_REGION_RERUNS = 4
        private const val REGION_RERUN_SCORE_BONUS = 16
        private const val SCORE_SWITCH_MARGIN = 18
        private const val MAX_PHRASE_SEGMENT_CHARS = 48

        private val STATUS_BAR_PATTERNS = listOf(
            Regex("""^\d{1,2}:\d{2}$"""),
            Regex("""^\d{1,2}:\d{2}\s?[AP]M$""", RegexOption.IGNORE_CASE),
            Regex("""^\d+%$"""),
            Regex("""^(5G|4G|LTE|VoLTE|HD|NFC|VPN|Wi-?Fi)$""", RegexOption.IGNORE_CASE)
        )
    }
}
