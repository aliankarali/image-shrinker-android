package io.github.aliankarali.imageshrinker.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import io.github.aliankarali.imageshrinker.core.IntRect
import io.github.aliankarali.imageshrinker.core.JpegIcc
import io.github.aliankarali.imageshrinker.core.PixelSize
import io.github.aliankarali.imageshrinker.core.QualitySettings
import io.github.aliankarali.imageshrinker.core.ResizePlan
import io.github.aliankarali.imageshrinker.core.png.PngWriter
import io.github.aliankarali.imageshrinker.core.png.Quantizer
import com.radzivon.bartoshyk.avif.coder.AvSpeed
import com.radzivon.bartoshyk.avif.coder.AvifEncodingOptions
import com.radzivon.bartoshyk.avif.coder.Coder
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer

/** A decoded image, upright, with the plan that was applied to it (if any). */
class DecodedRaster(val bitmap: Bitmap, val plan: ResizePlan?)

class RasterCodec(private val context: Context) {

    private val avif by lazy { Coder() }

    /**
     * Decodes [uri] upright and applies whatever crop and resize [planFor]
     * asks for, given the image's size as a viewer sees it.
     *
     * When the result is much smaller than the source, decoding is sub-sampled
     * to save memory — but only by a power of two that leaves at least twice
     * the output resolution, so the final downscale still has detail to average.
     */
    fun decode(uri: Uri, extension: String, planFor: (PixelSize) -> ResizePlan?, forceSrgb: Boolean): DecodedRaster {
        if (extension in EXPLICIT_ORIENTATION) {
            decodeOriented(uri, planFor, forceSrgb)?.let { return it }
        }
        var rawSize = PixelSize(0, 0)
        var sample = 1
        val bitmap = try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                if (forceSrgb) decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                rawSize = PixelSize(info.size.width, info.size.height)
                // Whether the header size is before or after rotation varies by
                // release, so only sub-sample when it's safe either way.
                sample = minOf(
                    safeSampleSize(planFor(rawSize)),
                    safeSampleSize(planFor(PixelSize(rawSize.height, rawSize.width))),
                )
                if (sample > 1) decoder.setTargetSampleSize(sample)
            }
        } catch (e: Exception) {
            // Devices without a platform AVIF (pre-Android 12) or HEVC decoder.
            if (extension !in setOf("avif", "heic", "heif")) throw e
            sample = 1
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw e
            runCatching { avif.decode(bytes) }.getOrElse { throw e }.also {
                rawSize = PixelSize(it.width, it.height)
            }
        }

        val full = fullSize(bitmap, rawSize, sample)
        val plan = planFor(full)
        if (plan == null) return DecodedRaster(bitmap, null)
        return DecodedRaster(transform(bitmap, full, plan), plan)
    }

    /**
     * Decodes with BitmapFactory — which never applies EXIF orientation, on any
     * release — and then rotates explicitly. ImageDecoder's handling of a PNG's
     * or WebP's EXIF orientation differs between releases (Android 16 ignores
     * it for PNG), so relying on it would leave some photos sideways.
     * Returns null if BitmapFactory can't read the file.
     */
    private fun decodeOriented(uri: Uri, planFor: (PixelSize) -> ResizePlan?, forceSrgb: Boolean): DecodedRaster? {
        val resolver = context.contentResolver
        val exif = runCatching { resolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull()
        val rotation = exif?.rotationDegrees ?: 0
        val flipped = exif?.isFlipped ?: false

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val quarterTurn = rotation == 90 || rotation == 270
        val full = if (quarterTurn) PixelSize(bounds.outHeight, bounds.outWidth) else PixelSize(bounds.outWidth, bounds.outHeight)
        val plan = planFor(full)

        val options = BitmapFactory.Options().apply {
            inSampleSize = safeSampleSize(plan)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            if (forceSrgb) inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val upright = if (rotation == 0 && !flipped) raw else {
            val matrix = Matrix().apply {
                if (flipped) postScale(-1f, 1f)
                postRotate(rotation.toFloat())
            }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        }
        if (plan == null) return DecodedRaster(upright, null)
        return DecodedRaster(transform(upright, full, plan), plan)
    }

    /** The upright full-resolution size, recovered from the sub-sampled bitmap. */
    private fun fullSize(bitmap: Bitmap, raw: PixelSize, sample: Int): PixelSize {
        if (sample == 1) return PixelSize(bitmap.width, bitmap.height)
        fun near(a: Int, b: Int) = kotlin.math.abs(a - b) <= 1
        val unrotated = near(bitmap.width, raw.width / sample) && near(bitmap.height, raw.height / sample)
        return if (unrotated) raw else PixelSize(raw.height, raw.width)
    }

    private fun safeSampleSize(plan: ResizePlan?): Int {
        if (plan == null) return 1
        var sample = 1
        while (sample * 4 * plan.decodeScale <= 1.0) sample *= 2
        return sample
    }

    private fun transform(bitmap: Bitmap, full: PixelSize, plan: ResizePlan): Bitmap {
        val f = bitmap.width.toDouble() / full.width
        val crop = plan.cropRect
        val scaled = IntRect(
            x = (crop.x * f).toInt().coerceIn(0, bitmap.width - 1),
            y = (crop.y * f).toInt().coerceIn(0, bitmap.height - 1),
            width = 0, height = 0,
        ).let {
            it.copy(
                width = Math.round(crop.width * f).toInt().coerceIn(1, bitmap.width - it.x),
                height = Math.round(crop.height * f).toInt().coerceIn(1, bitmap.height - it.y),
            )
        }
        var current = if (scaled.x == 0 && scaled.y == 0 && scaled.width == bitmap.width && scaled.height == bitmap.height) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, scaled.x, scaled.y, scaled.width, scaled.height)
        }
        val out = plan.outputSize
        // Halve with bilinear filtering until within 2× of the target: each halving
        // averages 2×2 blocks, which approximates an area filter and avoids the
        // aliasing a single large bilinear step produces.
        while (current.width / 2 >= out.width && current.height / 2 >= out.height) {
            current = current.scale(current.width / 2, current.height / 2)
        }
        if (current.width != out.width || current.height != out.height) {
            current = current.scale(out.width, out.height)
        }
        return current
    }

    /** Which JPEG encoder a bitmap goes through. */
    enum class JpegEncoder { MOZJPEG, PLATFORM }

    fun jpegEncoderFor(bitmap: Bitmap): JpegEncoder = when {
        !MozJpeg.isAvailable -> JpegEncoder.PLATFORM
        // Ultra HDR: the platform encoder writes the gain map that carries the
        // HDR highlights; mozjpeg would silently drop it.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap.hasGainmap() -> JpegEncoder.PLATFORM
        // 16-bit and 10-bit pixels: mozjpeg takes 8-bit only, and the platform
        // encoder does the tone mapping down.
        bitmap.config != Bitmap.Config.ARGB_8888 -> JpegEncoder.PLATFORM
        else -> JpegEncoder.MOZJPEG
    }

    /**
     * Encodes with mozjpeg — the desktop app's encoder, set up identically — or,
     * for the cases in [jpegEncoderFor], the platform's. A null [quality] is
     * mozjpeg's default (75), which is what Standard means on the desktop too.
     */
    fun encodeJpeg(bitmap: Bitmap, quality: Int?, out: OutputStream) {
        // JPEG has no transparency: flatten onto white rather than the black
        // that transparent pixels would otherwise become (premultiplied, their
        // stored colour is zero).
        val opaque = if (bitmap.hasAlpha()) {
            val config = if (bitmap.config == Bitmap.Config.RGBA_F16) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
            val colorSpace = bitmap.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
            createBitmap(bitmap.width, bitmap.height, config, false, colorSpace).also {
                val canvas = Canvas(it)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                // Drawing copies pixels only; keep the HDR gain map with them.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap.hasGainmap()) {
                    it.gainmap = bitmap.gainmap
                }
            }
        } else bitmap
        when (jpegEncoderFor(opaque)) {
            JpegEncoder.PLATFORM -> check(
                opaque.compress(Bitmap.CompressFormat.JPEG, quality ?: QualitySettings.JPEG_DEFAULT_QUALITY, out),
            ) { "JPEG encoder failed" }
            JpegEncoder.MOZJPEG -> {
                val (pixels, profile) = withColorProfile(opaque)
                out.write(MozJpeg.encode(pixels, quality, profile))
            }
        }
    }

    /**
     * mozjpeg writes whatever profile it's given, but Android has no API that
     * exports a colour space as an ICC profile. The platform encoder does embed
     * one, so encode a single pixel in the same space and take its profile.
     * sRGB needs none. A space with no obtainable profile is converted to sRGB
     * rather than written unlabelled, which would shift its colours.
     */
    private fun withColorProfile(bitmap: Bitmap): Pair<Bitmap, ByteArray?> {
        val space = bitmap.colorSpace ?: return bitmap to null
        if (space.isSrgb) return bitmap to null
        profileFor(space)?.let { return bitmap to it }
        val srgb = createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.SRGB))
        Canvas(srgb).drawBitmap(bitmap, 0f, 0f, null)
        return srgb to null
    }

    private fun profileFor(space: ColorSpace): ByteArray? = synchronized(profiles) {
        profiles.getOrPut(space) {
            runCatching {
                val pixel = createBitmap(1, 1, Bitmap.Config.ARGB_8888, false, space)
                val jpeg = java.io.ByteArrayOutputStream().also { pixel.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                JpegIcc.extract(jpeg)
            }.getOrNull() ?: ByteArray(0)
        }.takeIf { it.isNotEmpty() }
    }

    private val profiles = HashMap<ColorSpace, ByteArray>()

    fun encodeWebp(bitmap: Bitmap, quality: Int, out: OutputStream) {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        check(bitmap.compress(format, quality, out)) { "WebP encoder failed" }
    }

    fun encodeAvif(bitmap: Bitmap, quality: Int, exif: ByteArray?, out: OutputStream) {
        val input = if (bitmap.config == Bitmap.Config.ARGB_8888 || bitmap.config == Bitmap.Config.RGBA_F16) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        val exifBuffer = exif?.let { ByteBuffer.allocateDirect(it.size).put(it).apply { flip() } }
        val bytes = avif.encodeAvif(
            input,
            exifBuffer,
            AvifEncodingOptions(quality = quality.coerceIn(0, 100), speed = AvSpeed.MEDIUM),
        )
        out.write(bytes)
    }

    /**
     * pngquant's job: reduce to a palette of at most 256 colours (exactly, when
     * the image already has that few) and write an indexed PNG.
     */
    fun encodePng(bitmap: Bitmap, out: OutputStream) {
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val pixels = IntArray(argb.width * argb.height)
        argb.getPixels(pixels, 0, argb.width, 0, 0, argb.width, argb.height)
        out.write(PngWriter.write(Quantizer.quantize(pixels, argb.width, argb.height)))
    }

    private companion object {
        /** Formats whose rotation lives in an EXIF tag the platform decoders treat inconsistently. */
        val EXPLICIT_ORIENTATION = setOf("png", "webp")
    }

    fun scratchFile(extension: String): File {
        val dir = File(context.cacheDir, "shrink").apply { mkdirs() }
        return File.createTempFile("out-", ".$extension", dir)
    }
}
