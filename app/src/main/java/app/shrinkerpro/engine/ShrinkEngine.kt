package app.shrinkerpro.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import app.shrinkerpro.core.ConversionRouter
import app.shrinkerpro.core.ConversionRules
import app.shrinkerpro.core.CropTarget
import app.shrinkerpro.core.FileNames
import app.shrinkerpro.core.MetadataPolicy
import app.shrinkerpro.core.OutputFormat
import app.shrinkerpro.core.PixelSize
import app.shrinkerpro.core.QualitySettings
import app.shrinkerpro.core.SaveLocation
import app.shrinkerpro.core.SessionFormat
import app.shrinkerpro.core.SizePlanner
import app.shrinkerpro.core.gif.GifOptimizer
import java.io.File

/** A snapshot of everything one batch is shrunk with: stored settings plus the session's overrides. */
data class JobSettings(
    val location: SaveLocation,
    val customFolder: Uri?,
    val useSubfolder: Boolean,
    val keepOriginal: Boolean,
    val rules: ConversionRules,
    val metadata: MetadataPolicy,
    val quality: QualitySettings,
    val sessionFormat: SessionFormat?,
    val maxDimension: Int?,
    val crop: CropTarget?,
)

class ShrinkPlan(
    val input: InputFile,
    val output: OutputFormat,
    val isSameFormat: Boolean,
    val destination: Destination,
) {
    fun writing(to: Destination) = ShrinkPlan(input, output, isSameFormat, to)
}

data class ShrinkResult(
    val input: InputFile,
    val outputUri: Uri,
    val outputName: String,
    val mimeType: String,
    val originalBytes: Long,
    val shrunkBytes: Long,
    /** The result came out no smaller, so the original was left alone. */
    val keptOriginal: Boolean,
) {
    /** Percentage saved, rounded; negative when a conversion or resize grew the file. */
    val savedPercent: Int
        get() = if (originalBytes <= 0) 0 else Math.round(100.0 * (originalBytes - shrunkBytes) / originalBytes).toInt()
}

class ShrinkEngine(private val context: Context) {
    private val raster = RasterCodec(context)
    private val metadata = MetadataCopier(context)
    private val svg = SvgOptimizer(context)

    /** Decides format and destination. Throws [ShrinkException] for unsupported files. */
    fun plan(input: InputFile, settings: JobSettings, folders: FolderCache): ShrinkPlan {
        if (!input.readable) throw ShrinkException(ShrinkException.Kind.READ, input.name)
        val output = ConversionRouter.destination(input.extension, settings.rules, settings.sessionFormat)
            ?: throw ShrinkException(ShrinkException.Kind.UNSUPPORTED, input.name)
        val sameFormat = ConversionRouter.isSameFormat(input.extension, output)
        // A same-format result keeps the input's own extension spelling (.jpeg stays .jpeg).
        val name = FileNames.outputName(input.name, settings.keepOriginal, if (sameFormat) null else output.extension)
        val subfolder = if (settings.useSubfolder) FileNames.SUBFOLDER else null

        val destination = when (settings.location) {
            SaveLocation.SAME_FOLDER -> when {
                input.parent != null -> Destination(folders.tree(input.parent, subfolder), name, null)
                // Chosen individually or shared: the folder itself is out of reach,
                // but the document is writable, so "replace" can still happen in place.
                subfolder == null && name == input.name && input.canWrite -> Destination(null, name, input.uri)
                else -> Destination(folders.pictures(subfolder), name, null)
            }
            SaveLocation.CUSTOM -> {
                val tree = settings.customFolder
                    ?: return ShrinkPlan(input, output, sameFormat, Destination(folders.pictures(subfolder), name, null))
                val folder = runCatching { folders.treeRoot(tree, subfolder) }.getOrElse {
                    throw ShrinkException(ShrinkException.Kind.FOLDER_UNAVAILABLE, input.name, cause = it)
                }
                Destination(folder, name, null)
            }
            SaveLocation.PICTURES -> Destination(folders.pictures(subfolder), name, null)
        }
        return ShrinkPlan(input, output, sameFormat, destination)
    }

    /** What's at the destination now, if anything. */
    fun existing(destination: Destination): Uri? =
        destination.inPlace ?: runCatching { destination.folder?.find(destination.name) }.getOrNull()

    /** Whether writing [plan] destroys the very file it was made from. */
    fun overwritesInput(plan: ShrinkPlan): Boolean {
        val existing = existing(plan.destination) ?: return false
        return sameDocument(existing, plan.input.uri)
    }

    private fun sameDocument(a: Uri, b: Uri): Boolean {
        if (a == b) return true
        return runCatching {
            android.provider.DocumentsContract.getDocumentId(a) == android.provider.DocumentsContract.getDocumentId(b)
        }.getOrDefault(false)
    }

    fun shrink(plan: ShrinkPlan, settings: JobSettings): ShrinkResult {
        val input = plan.input
        val scratch = raster.scratchFile(plan.output.extension)
        try {
            val resized = encode(plan, settings, scratch)
            val shrunkBytes = scratch.length()
            if (shrunkBytes <= 0) throw ShrinkException(ShrinkException.Kind.ENCODE, input.name, plan.output.extension)
            val originalBytes = input.size.takeIf { it > 0 } ?: measure(input.uri)

            // Compressing never makes a file bigger. Re-encoding at a quality
            // above the one a file was stored at inflates it, and that quality
            // can't be read back — so check afterwards and keep the original.
            // Conversions and resizes are exempt: that growth was asked for.
            if (plan.isSameFormat && !resized && shrunkBytes >= originalBytes) {
                return ShrinkResult(input, input.uri, input.name, plan.output.mimeType, originalBytes, originalBytes, keptOriginal = true)
            }

            val destination = plan.destination
            val written = try {
                if (destination.inPlace != null) {
                    context.contentResolver.overwrite(destination.inPlace, scratch)
                    destination.inPlace
                } else {
                    val folder = destination.folder!!
                    folder.write(destination.name, plan.output.mimeType, scratch, folder.find(destination.name))
                }
            } catch (e: Exception) {
                throw ShrinkException(ShrinkException.Kind.WRITE, input.name, e.message, e)
            }
            return ShrinkResult(input, written, destination.name, plan.output.mimeType, originalBytes, shrunkBytes, keptOriginal = false)
        } finally {
            scratch.delete()
        }
    }

    /** Writes the result to [scratch]; returns whether its dimensions changed. */
    private fun encode(plan: ShrinkPlan, settings: JobSettings, scratch: File): Boolean {
        val input = plan.input
        val planFor = { size: PixelSize -> SizePlanner.plan(size, settings.maxDimension, settings.crop) }
        return when (plan.output) {
            OutputFormat.SVG -> {
                // Vector: there are no pixels to cap or crop.
                val text = readBytes(input).toString(Charsets.UTF_8)
                val optimized = try {
                    svg.optimize(text)
                } catch (e: Exception) {
                    throw ShrinkException(ShrinkException.Kind.SVG, input.name, e.message ?: e.javaClass.simpleName, e)
                }
                scratch.writeText(optimized, Charsets.UTF_8)
                false
            }
            OutputFormat.GIF -> {
                val bytes = readBytes(input)
                val size = gifSize(bytes) ?: throw ShrinkException(ShrinkException.Kind.DECODE, input.name)
                val resize = planFor(size)
                val out = try {
                    GifOptimizer.optimize(bytes, resize)
                } catch (e: Exception) {
                    throw ShrinkException(ShrinkException.Kind.DECODE, input.name, e.message, e)
                }
                scratch.writeBytes(out)
                resize != null
            }
            else -> encodeRaster(plan, settings, scratch, planFor)
        }
    }

    private fun encodeRaster(
        plan: ShrinkPlan,
        settings: JobSettings,
        scratch: File,
        planFor: (PixelSize) -> app.shrinkerpro.core.ResizePlan?,
    ): Boolean {
        val input = plan.input
        val decoded = try {
            // The PNG quantiser works in sRGB bytes; other encoders keep wide colour.
            raster.decode(input.uri, input.extension, planFor, forceSrgb = plan.output == OutputFormat.PNG)
        } catch (e: OutOfMemoryError) {
            throw ShrinkException(ShrinkException.Kind.DECODE, input.name, "image too large", e)
        } catch (e: Exception) {
            throw ShrinkException(ShrinkException.Kind.DECODE, input.name, e.message, e)
        }
        val bitmap = decoded.bitmap
        val size = PixelSize(bitmap.width, bitmap.height)
        val source = if (settings.metadata != MetadataPolicy.NONE) metadata.read(input.uri) else null
        try {
            scratch.outputStream().buffered().use { out ->
                when (plan.output) {
                    OutputFormat.JPEG -> raster.encodeJpeg(bitmap, settings.quality.jpegQuality, out)
                    OutputFormat.WEBP -> raster.encodeWebp(bitmap, settings.quality.webpQuality, out)
                    OutputFormat.PNG -> raster.encodePng(bitmap, out)
                    OutputFormat.AVIF -> {
                        val exif = metadata.exifBlock(source, settings.metadata, size, scratch.parentFile!!)
                        raster.encodeAvif(bitmap, settings.quality.avifQuality, exif, out)
                    }
                    else -> error("not a raster output: ${plan.output}")
                }
            }
        } catch (e: OutOfMemoryError) {
            throw ShrinkException(ShrinkException.Kind.ENCODE, input.name, plan.output.extension, e)
        } catch (e: Exception) {
            Log.w(TAG, "encode failed for ${input.name}", e)
            throw ShrinkException(ShrinkException.Kind.ENCODE, input.name, plan.output.extension, e)
        } finally {
            bitmap.recycle()
        }
        if (plan.output != OutputFormat.AVIF) {
            metadata.apply(scratch, source, settings.metadata, size)
        }
        return decoded.plan != null
    }

    private fun readBytes(input: InputFile): ByteArray = try {
        context.contentResolver.openInputStream(input.uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("no stream")
    } catch (e: Exception) {
        throw ShrinkException(ShrinkException.Kind.READ, input.name, e.message, e)
    }

    private fun measure(uri: Uri): Long = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
    }.getOrNull()?.takeIf { it > 0 } ?: runCatching {
        context.contentResolver.openInputStream(uri)?.use { s ->
            var total = 0L
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = s.read(buffer); if (n < 0) break; total += n }
            total
        }
    }.getOrNull() ?: 0L

    private fun gifSize(bytes: ByteArray): PixelSize? {
        if (bytes.size < 10) return null
        val w = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
        val h = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
        return if (w > 0 && h > 0) PixelSize(w, h) else null
    }

    private companion object {
        const val TAG = "ShrinkEngine"
    }
}
