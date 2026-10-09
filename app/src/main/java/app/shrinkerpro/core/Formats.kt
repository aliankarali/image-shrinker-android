package app.shrinkerpro.core

/** Every input the app accepts, keyed by lower-case file extension. */
object SupportedFormats {
    val extensions: Set<String> = setOf(
        "svg", "png", "gif", "jpg", "jpeg", "webp", "avif", "heic", "heif",
    )

    fun isSupported(extension: String): Boolean = extension.lowercase() in extensions

    /** Best-effort extension for a MIME type, for inputs whose name carries none. */
    fun extensionForMime(mime: String?): String? = when (mime?.lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg", "image/pjpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/avif" -> "avif"
        "image/heic", "image/heic-sequence" -> "heic"
        "image/heif", "image/heif-sequence" -> "heif"
        "image/svg+xml" -> "svg"
        else -> null
    }
}

/** A raster format the conversion rules apply to. SVG and GIF never convert. */
enum class NativeFormat {
    PNG, JPEG, WEBP, AVIF, HEIC;

    companion object {
        fun fromExtension(ext: String): NativeFormat? = when (ext.lowercase()) {
            "png" -> PNG
            "jpg", "jpeg" -> JPEG
            "webp" -> WEBP
            "avif" -> AVIF
            "heic", "heif" -> HEIC
            else -> null
        }
    }
}

/** A format a file can be written as. */
enum class OutputFormat(val extension: String, val mimeType: String) {
    JPEG("jpg", "image/jpeg"),
    WEBP("webp", "image/webp"),
    AVIF("avif", "image/avif"),
    PNG("png", "image/png"),
    GIF("gif", "image/gif"),
    SVG("svg", "image/svg+xml"),
}

/** A stored per-format rule: leave the format alone, or convert to one of three. */
enum class ConversionTarget(val key: String, val label: String) {
    KEEP("keep", "Keep"),
    JPEG("jpeg", "JPEG"),
    WEBP("webp", "WebP"),
    AVIF("avif", "AVIF");

    val conversionFormat: ConversionFormat?
        get() = when (this) {
            KEEP -> null
            JPEG -> ConversionFormat.JPEG
            WEBP -> ConversionFormat.WEBP
            AVIF -> ConversionFormat.AVIF
        }

    companion object {
        fun fromKey(key: String?): ConversionTarget? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The HEIC rule has no "keep": HEIC is a capture format most tools outside
 * Apple's ecosystem can't open, so it is always converted.
 */
enum class ConversionFormat(val key: String, val label: String, val output: OutputFormat) {
    JPEG("jpeg", "JPEG", OutputFormat.JPEG),
    WEBP("webp", "WebP", OutputFormat.WEBP),
    AVIF("avif", "AVIF", OutputFormat.AVIF);

    companion object {
        fun fromKey(key: String?): ConversionFormat? = entries.firstOrNull { it.key == key }
    }
}

data class ConversionRules(
    val png: ConversionTarget = ConversionTarget.KEEP,
    val jpeg: ConversionTarget = ConversionTarget.KEEP,
    val heic: ConversionFormat = ConversionFormat.JPEG,
    val webp: ConversionTarget = ConversionTarget.KEEP,
    val avif: ConversionTarget = ConversionTarget.KEEP,
)

/**
 * The session-only "Convert all to" override. PNG is offered here and nowhere
 * else: as a stored rule it would sit beside "Keep PNG" meaning almost the same.
 */
enum class SessionFormat(val label: String, val output: OutputFormat) {
    JPEG("JPEG", OutputFormat.JPEG),
    WEBP("WebP", OutputFormat.WEBP),
    AVIF("AVIF", OutputFormat.AVIF),
    PNG("PNG", OutputFormat.PNG),
}

/**
 * Encoder quality per level, the same values the desktop app uses.
 * [jpegQuality] is null for Standard: like the desktop app, Standard passes no
 * quality to mozjpeg and gets its default (75).
 */
data class QualitySettings(val jpegQuality: Int?, val webpQuality: Int, val avifQuality: Int) {
    companion object {
        /** mozjpeg's (and cjpeg's) default quality. */
        const val JPEG_DEFAULT_QUALITY = 75
    }
}

enum class QualityLevel(val key: String, val label: String, val settings: QualitySettings) {
    SUPER_LOW("superLow", "Super Low", QualitySettings(40, 40, 40)),
    LOW("low", "Low", QualitySettings(60, 60, 60)),
    STANDARD("standard", "Standard", QualitySettings(null, 80, 80)),
    HIGH("high", "High", QualitySettings(85, 85, 85));

    companion object {
        fun fromKey(key: String?): QualityLevel? = entries.firstOrNull { it.key == key }
    }
}

enum class MetadataPolicy(val key: String) {
    ALL("all"),
    COPYRIGHT("copyright"),
    NONE("none");

    companion object {
        fun fromKey(key: String?): MetadataPolicy? = entries.firstOrNull { it.key == key }
    }
}

data class PixelSize(val width: Int, val height: Int) {
    val longestSide: Int get() = maxOf(width, height)
    override fun toString() = "${width}x$height"
}

data class AspectRatio(val width: Int, val height: Int)

data class CropTarget(val width: Int, val height: Int, val mode: Mode) {
    enum class Mode { RATIO, PIXELS }

    val aspect: AspectRatio get() = AspectRatio(width, height)

    /** A pixel crop states the output size outright; a ratio crop only a shape. */
    val exactSize: PixelSize? get() = if (mode == Mode.PIXELS) PixelSize(width, height) else null
}

/** Integer rectangle, origin top-left. */
data class IntRect(val x: Int, val y: Int, val width: Int, val height: Int)
