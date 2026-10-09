package app.shrinkerpro.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import app.shrinkerpro.core.ExifTextPatch
import app.shrinkerpro.core.MetadataPolicy
import app.shrinkerpro.core.PixelSize
import java.io.File

/**
 * Carries EXIF from the original to the result according to the metadata policy.
 *
 * Rotation is never copied: it has already been applied to the pixels, so an
 * orientation tag would only make viewers rotate the picture a second time.
 *
 * Only tags with plain text or numeric values are copied. ExifInterface can
 * only set tags from strings, and round-tripping binary blobs (maker notes,
 * XMP) through a string would corrupt them — so those are left behind rather
 * than written back damaged.
 */
class MetadataCopier(private val context: Context) {

    /** The source's EXIF, or null if it has none or the format can't carry it. */
    fun read(uri: Uri): ExifInterface? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { ExifInterface(it.fileDescriptor) }
    }.recoverCatching {
        context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
    }.getOrNull()

    /** Writes the policy's share of [source]'s tags into [file], which must be JPEG, PNG or WebP. */
    fun apply(file: File, source: ExifInterface?, policy: MetadataPolicy, size: PixelSize) {
        if (source == null || policy == MetadataPolicy.NONE) return
        val tags = tagsFor(policy)
        val values = tags.mapNotNull { tag -> source.getAttribute(tag)?.let { tag to it } }
        if (values.isEmpty()) return
        try {
            val target = ExifInterface(file)
            for ((tag, value) in values) target.setAttribute(tag, value)
            if (policy == MetadataPolicy.ALL &&
                (source.getAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION) != null)
            ) {
                target.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, size.width.toString())
                target.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, size.height.toString())
            }
            target.saveAttributes()
            restoreText(file, source, tags)
        } catch (e: Exception) {
            // The image itself is fine; only its metadata couldn't be written.
            Log.w(TAG, "could not write metadata to ${file.name}", e)
        }
    }

    /** Puts back the original bytes of text tags ExifInterface wrote as '?' (see [ExifTextPatch]). */
    private fun restoreText(file: File, source: ExifInterface, copied: List<String>) {
        val originals = ExifTextPatch.TEXT_TAGS
            .filterKeys { it in copied }
            .mapNotNull { (name, id) -> source.getAttributeBytes(name)?.let { id to it } }
            .filter { (_, bytes) -> bytes.any { it < 0 } }
            .toMap()
        if (originals.isEmpty()) return
        val bytes = file.readBytes()
        if (ExifTextPatch.patch(bytes, originals)) file.writeBytes(bytes)
    }

    /**
     * The policy's tags as a raw EXIF (TIFF) block, for encoders that take one
     * directly (AVIF). Built by letting ExifInterface write a throwaway JPEG and
     * lifting the APP1 payload out of it.
     */
    fun exifBlock(source: ExifInterface?, policy: MetadataPolicy, size: PixelSize, scratch: File): ByteArray? {
        if (source == null || policy == MetadataPolicy.NONE) return null
        if (tagsFor(policy).none { source.getAttribute(it) != null }) return null
        val carrier = File(scratch, "exif-${System.nanoTime()}.jpg")
        try {
            val pixel = createBitmap(1, 1)
            carrier.outputStream().use { pixel.compress(Bitmap.CompressFormat.JPEG, 50, it) }
            pixel.recycle()
            apply(carrier, source, policy, size)
            return extractApp1Exif(carrier.readBytes())
        } catch (e: Exception) {
            Log.w(TAG, "could not build an EXIF block", e)
            return null
        } finally {
            carrier.delete()
        }
    }

    companion object {
        private const val TAG = "MetadataCopier"

        /** The TIFF payload of a JPEG's EXIF APP1 segment, without the "Exif\0\0" prefix. */
        internal fun extractApp1Exif(jpeg: ByteArray): ByteArray? {
            var i = 2
            while (i + 4 <= jpeg.size) {
                if (jpeg[i] != 0xFF.toByte()) return null
                val marker = jpeg[i + 1].toInt() and 0xFF
                val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
                if (marker == 0xE1 && length >= 8 && i + 2 + length <= jpeg.size &&
                    String(jpeg, i + 4, 4, Charsets.US_ASCII) == "Exif"
                ) {
                    return jpeg.copyOfRange(i + 10, i + 2 + length)
                }
                if (marker == 0xDA) return null
                i += 2 + length
            }
            return null
        }

        private val COPYRIGHT_TAGS = listOf(ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_ARTIST)

        private val ALL_TAGS = COPYRIGHT_TAGS + listOf(
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_IMAGE_UNIQUE_ID,
            ExifInterface.TAG_CAMERA_OWNER_NAME, ExifInterface.TAG_BODY_SERIAL_NUMBER,
            ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_LENS_SERIAL_NUMBER, ExifInterface.TAG_LENS_SPECIFICATION,
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_MAX_APERTURE_VALUE, ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_BRIGHTNESS_VALUE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_MODE,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_SENSITIVITY_TYPE,
            ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX, ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_LIGHT_SOURCE, ExifInterface.TAG_FLASH, ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO, ExifInterface.TAG_SCENE_CAPTURE_TYPE,
            ExifInterface.TAG_CONTRAST, ExifInterface.TAG_SATURATION, ExifInterface.TAG_SHARPNESS,
            ExifInterface.TAG_SUBJECT_DISTANCE, ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
            ExifInterface.TAG_SUBJECT_AREA, ExifInterface.TAG_SENSING_METHOD, ExifInterface.TAG_GAIN_CONTROL,
            ExifInterface.TAG_COLOR_SPACE,
            ExifInterface.TAG_X_RESOLUTION, ExifInterface.TAG_Y_RESOLUTION, ExifInterface.TAG_RESOLUTION_UNIT,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            ExifInterface.TAG_GPS_DEST_BEARING, ExifInterface.TAG_GPS_DEST_BEARING_REF,
            ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_TRACK_REF,
            ExifInterface.TAG_GPS_H_POSITIONING_ERROR, ExifInterface.TAG_GPS_DOP,
            ExifInterface.TAG_GPS_MAP_DATUM, ExifInterface.TAG_GPS_SATELLITES,
            ExifInterface.TAG_GPS_STATUS, ExifInterface.TAG_GPS_MEASURE_MODE,
        )

        fun tagsFor(policy: MetadataPolicy): List<String> = when (policy) {
            MetadataPolicy.ALL -> ALL_TAGS
            MetadataPolicy.COPYRIGHT -> COPYRIGHT_TAGS
            MetadataPolicy.NONE -> emptyList()
        }
    }
}
