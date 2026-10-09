package app.shrinkerpro.core

/**
 * Decides what format each input is written as. A pure function of the input
 * extension and the rules in force, so every combination is enumerable in tests.
 *
 * The desktop app's router also picks an intermediate format, because its
 * command-line encoders can't all read every input. On Android every raster is
 * decoded to a Bitmap first, so only the destination matters here.
 */
object ConversionRouter {

    /** `null` when [extension] isn't something the app reads at all. */
    fun destination(extension: String, rules: ConversionRules, session: SessionFormat?): OutputFormat? {
        when (extension.lowercase()) {
            // Vector, and usually animated: converting either would destroy
            // what makes it useful, so neither has a rule or obeys the override.
            "svg" -> return OutputFormat.SVG
            "gif" -> return OutputFormat.GIF
        }
        val native = NativeFormat.fromExtension(extension) ?: return null
        if (session != null) return session.output
        val ruled: ConversionFormat? = when (native) {
            NativeFormat.PNG -> rules.png.conversionFormat
            NativeFormat.JPEG -> rules.jpeg.conversionFormat
            NativeFormat.WEBP -> rules.webp.conversionFormat
            NativeFormat.AVIF -> rules.avif.conversionFormat
            NativeFormat.HEIC -> rules.heic
        }
        return ruled?.output ?: sameFormat(native)
    }

    fun sameFormat(native: NativeFormat): OutputFormat = when (native) {
        NativeFormat.PNG -> OutputFormat.PNG
        NativeFormat.JPEG -> OutputFormat.JPEG
        NativeFormat.WEBP -> OutputFormat.WEBP
        NativeFormat.AVIF -> OutputFormat.AVIF
        // Unreachable through the rules (HEIC has no "keep"), but total.
        NativeFormat.HEIC -> OutputFormat.JPEG
    }

    /** Whether writing [extension] as [output] is a same-format compress rather than a conversion. */
    fun isSameFormat(extension: String, output: OutputFormat): Boolean = when (extension.lowercase()) {
        "svg" -> output == OutputFormat.SVG
        "gif" -> output == OutputFormat.GIF
        else -> NativeFormat.fromExtension(extension)?.let { sameFormat(it) == output && it != NativeFormat.HEIC } ?: false
    }
}

object CropGeometry {
    /**
     * The largest rectangle of [aspect]'s shape centred in [size]. The shape is
     * used exactly as given — a portrait source cropped 16:9 becomes a
     * landscape strip, never quietly turned on its side. An odd leftover pixel
     * falls to the right and bottom.
     */
    fun centeredCrop(size: PixelSize, aspect: AspectRatio?): IntRect {
        val width = maxOf(1, size.width)
        val height = maxOf(1, size.height)
        val whole = IntRect(0, 0, width, height)
        if (aspect == null || aspect.width <= 0 || aspect.height <= 0) return whole

        // Long arithmetic: 20 000 × 20 000 sides overflow nothing, but an
        // unusually large source times a large ratio could.
        val source = width.toLong() * aspect.height
        val target = height.toLong() * aspect.width
        var cropWidth = width
        var cropHeight = height
        if (source > target) {
            cropWidth = minOf(width, maxOf(1, divideRoundingHalfUp(height.toLong() * aspect.width, aspect.height.toLong())))
        } else if (source < target) {
            cropHeight = minOf(height, maxOf(1, divideRoundingHalfUp(width.toLong() * aspect.height, aspect.width.toLong())))
        }
        return IntRect((width - cropWidth) / 2, (height - cropHeight) / 2, cropWidth, cropHeight)
    }

    private fun divideRoundingHalfUp(value: Long, divisor: Long): Int = ((value + divisor / 2) / divisor).toInt()
}

/** What a resize/crop does to one image: which part of the source, and how big the result. */
data class ResizePlan(val sourceSize: PixelSize, val aspect: AspectRatio?, val outputSize: PixelSize) {
    val cropRect: IntRect get() = CropGeometry.centeredCrop(sourceSize, aspect)

    val isCropping: Boolean
        get() = cropRect.let { it.width != sourceSize.width || it.height != sourceSize.height }

    /** Output width over crop width, at most 1 — nothing is ever enlarged. */
    val decodeScale: Double
        get() {
            val cropWidth = cropRect.width.toDouble()
            return if (cropWidth <= 0) 1.0 else minOf(1.0, outputSize.width / cropWidth)
        }
}

object SizePlanner {
    /**
     * Combines the session's max-size cap and crop into a plan for an image of
     * [source] pixels (as a viewer sees it, rotation applied). `null` means the
     * image keeps its dimensions.
     *
     * Nothing is enlarged: an 800×600 image asked for 1200×1200 comes out
     * 600×600. A pixel crop sets the size outright, so the cap is ignored; a
     * ratio crop says nothing about size, so the cap still applies to it.
     */
    fun plan(source: PixelSize, maxDimension: Int?, crop: CropTarget?): ResizePlan? {
        val cap = maxDimension?.takeIf { it > 0 }
        if (crop == null && cap == null) return null
        if (source.width < 1 || source.height < 1) return null

        val aspect = crop?.aspect
        val rect = CropGeometry.centeredCrop(source, aspect)
        var output = PixelSize(rect.width, rect.height)
        val exact = crop?.exactSize
        if (exact != null && output.width > exact.width) {
            output = exact
        }
        if (exact == null && cap != null && output.longestSide > cap) {
            val scale = cap.toDouble() / output.longestSide
            output = PixelSize(
                maxOf(1, Math.round(output.width * scale).toInt()),
                maxOf(1, Math.round(output.height * scale).toInt()),
            )
        }
        if (output == source) return null
        return ResizePlan(source, aspect, output)
    }
}
