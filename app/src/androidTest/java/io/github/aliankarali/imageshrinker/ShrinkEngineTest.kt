package io.github.aliankarali.imageshrinker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aliankarali.imageshrinker.core.ConversionRules
import io.github.aliankarali.imageshrinker.core.CropTarget
import io.github.aliankarali.imageshrinker.core.FileNames
import io.github.aliankarali.imageshrinker.core.MetadataPolicy
import io.github.aliankarali.imageshrinker.core.OutputFormat
import io.github.aliankarali.imageshrinker.core.QualityLevel
import io.github.aliankarali.imageshrinker.core.SaveLocation
import io.github.aliankarali.imageshrinker.core.SessionFormat
import io.github.aliankarali.imageshrinker.engine.Destination
import io.github.aliankarali.imageshrinker.engine.FolderCache
import io.github.aliankarali.imageshrinker.engine.InputFile
import io.github.aliankarali.imageshrinker.engine.JobSettings
import io.github.aliankarali.imageshrinker.engine.OutputFolder
import io.github.aliankarali.imageshrinker.engine.ShrinkEngine
import io.github.aliankarali.imageshrinker.engine.ShrinkPlan
import io.github.aliankarali.imageshrinker.engine.ShrinkResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Writes into a plain directory, so tests can inspect results as files. */
private class LocalFolder(val dir: File) : OutputFolder {
    override val key: String get() = "local:${dir.path}"
    override val displayName: String get() = dir.name
    override fun find(name: String): Uri? = File(dir, name).takeIf { it.exists() }?.let(Uri::fromFile)
    override fun write(name: String, mimeType: String, source: File, existing: Uri?): Uri {
        dir.mkdirs()
        val target = File(dir, name)
        source.copyTo(target, overwrite = true)
        return Uri.fromFile(target)
    }
}

@RunWith(AndroidJUnit4::class)
class ShrinkEngineTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var work: File
    private lateinit var out: LocalFolder
    private lateinit var engine: ShrinkEngine

    @Before fun setUp() {
        work = File(context.cacheDir, "engine-test-${System.nanoTime()}").apply { mkdirs() }
        out = LocalFolder(File(work, "out"))
        engine = ShrinkEngine(context)
    }

    @After fun tearDown() {
        work.deleteRecursively()
    }

    private fun fixture(name: String, as_: String = name): InputFile {
        val file = File(work, as_)
        InstrumentationRegistry.getInstrumentation().context.assets.open("fixtures/$name").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return InputFile(Uri.fromFile(file), as_, FileNames.extension(as_).lowercase(), file.length(), parent = null, canWrite = true)
    }

    private fun job(
        rules: ConversionRules = ConversionRules(),
        session: SessionFormat? = null,
        quality: QualityLevel = QualityLevel.STANDARD,
        metadata: MetadataPolicy = MetadataPolicy.ALL,
        maxDimension: Int? = null,
        crop: CropTarget? = null,
    ) = JobSettings(
        location = SaveLocation.PICTURES, customFolder = null, useSubfolder = false, keepOriginal = true,
        rules = rules, metadata = metadata, quality = quality.settings, sessionFormat = session,
        maxDimension = maxDimension, crop = crop,
    )

    /** Plans normally, then redirects the output into the test's local folder. */
    private fun shrink(input: InputFile, job: JobSettings): ShrinkResult {
        val plan = engine.plan(input, job, FolderCache(context))
        val local = ShrinkPlan(plan.input, plan.output, plan.isSameFormat, Destination(out, plan.destination.name, null))
        return engine.shrink(local, job)
    }

    private fun decode(uri: Uri): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    private fun outputFile(result: ShrinkResult) = File(result.outputUri.path!!)

    // ---- formats -------------------------------------------------------------------------

    @Test fun everyRasterFormatShrinksInItsOwnFormat() {
        for (name in listOf("sample.jpg", "sample.png", "sample.webp", "sample.avif")) {
            val input = fixture(name)
            val result = shrink(input, job())
            assertTrue("$name grew: ${result.shrunkBytes} > ${result.originalBytes}", result.shrunkBytes <= result.originalBytes)
            val bitmap = decode(result.outputUri)
            assertEquals("$name width", 548, bitmap.width)
            assertEquals("$name height", 547, bitmap.height)
            if (!result.keptOriginal) assertEquals(FileNames.extension(name), FileNames.extension(result.outputName))
        }
    }

    @Test fun pngIsPalettedAndSmaller() {
        val result = shrink(fixture("sample.png"), job())
        assertFalse(result.keptOriginal)
        assertTrue("saved ${result.savedPercent}%", result.savedPercent > 30)
        val bytes = outputFile(result).readBytes()
        assertEquals("colour type: indexed", 3, bytes[25].toInt())
    }

    @Test fun heicBecomesJpegByDefault() {
        val result = shrink(fixture("sample.heic"), job())
        assertEquals("sample.min.jpg", result.outputName)
        assertEquals(OutputFormat.JPEG.mimeType, result.mimeType)
        val bitmap = decode(result.outputUri)
        assertEquals(548, bitmap.width)
        assertEquals(547, bitmap.height)
    }

    @Test fun conversionRulesAndSessionOverride() {
        val rules = ConversionRules(png = io.github.aliankarali.imageshrinker.core.ConversionTarget.WEBP)
        assertEquals("sample.min.webp", shrink(fixture("sample.png"), job(rules = rules)).outputName)
        for ((format, ext) in listOf(SessionFormat.AVIF to "avif", SessionFormat.PNG to "png", SessionFormat.JPEG to "jpg", SessionFormat.WEBP to "webp")) {
            val result = shrink(fixture("sample.jpg"), job(rules = rules, session = format))
            assertEquals("sample.min.$ext", result.outputName)
            val bitmap = decode(result.outputUri)
            assertEquals("$ext width", 548, bitmap.width)
        }
    }

    @Test fun svgIsOptimisedBySvgo() {
        val result = shrink(fixture("sample.svg"), job())
        assertEquals(163L, result.originalBytes)
        // Same svgo version as the desktop app, which documents 163 → 109 bytes.
        assertEquals(109L, result.shrunkBytes)
        assertTrue(outputFile(result).readText().startsWith("<svg"))
    }

    @Test fun gifIsOptimisedLosslessly() {
        for (name in listOf("sample.gif", "offsetframes.gif")) {
            val result = shrink(fixture(name), job())
            assertTrue("$name grew", result.shrunkBytes <= result.originalBytes)
        }
    }

    // ---- never grow ------------------------------------------------------------------------

    @Test fun aSameFormatResultThatWouldGrowKeepsTheOriginal() {
        // A JPEG stored at quality 20, re-encoded at High (85), comes out bigger.
        val low = File(work, "low.jpg")
        decode(fixture("sample.png").uri).let { b ->
            low.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 20, it) }
        }
        val input = InputFile(Uri.fromFile(low), "low.jpg", "jpg", low.length(), null, true)
        val result = shrink(input, job(quality = QualityLevel.HIGH))
        assertTrue(result.keptOriginal)
        assertEquals(result.originalBytes, result.shrunkBytes)
        assertEquals(input.uri, result.outputUri)
        assertNull("nothing was written", out.find("low.min.jpg"))
    }

    // ---- size ------------------------------------------------------------------------------

    @Test fun pixelCropIsExactOnEveryRasterRoute() {
        val crop = CropTarget(320, 180, CropTarget.Mode.PIXELS)
        for (name in listOf("sample.jpg", "sample.png", "sample.webp", "sample.avif", "sample.heic")) {
            val bitmap = decode(shrink(fixture(name), job(crop = crop)).outputUri)
            assertEquals("$name width", 320, bitmap.width)
            assertEquals("$name height", 180, bitmap.height)
        }
    }

    @Test fun ratioCropKeepsResolutionAndCapScales() {
        val square = decode(shrink(fixture("sample.png"), job(crop = CropTarget(1, 1, CropTarget.Mode.RATIO))).outputUri)
        assertEquals(547, square.width)
        assertEquals(547, square.height)
        val capped = decode(shrink(fixture("sample.jpg"), job(maxDimension = 100)).outputUri)
        assertEquals(100, capped.width)
        assertEquals(100, capped.height)
    }

    @Test fun pixelCropNeverEnlarges() {
        val bitmap = decode(shrink(fixture("sample.jpg"), job(crop = CropTarget(1200, 1200, CropTarget.Mode.PIXELS))).outputUri)
        assertEquals(547, bitmap.width)
        assertEquals(547, bitmap.height)
    }

    @Test fun gifIsCroppedOnEveryFrame() {
        val result = shrink(fixture("offsetframes.gif"), job(crop = CropTarget(120, 68, CropTarget.Mode.PIXELS)))
        val gif = io.github.aliankarali.imageshrinker.core.gif.GifDecoder.decode(outputFile(result).readBytes())
        assertEquals(120, gif.width)
        assertEquals(68, gif.height)
        assertTrue(gif.frames.size > 1)
    }

    // ---- rotation and metadata --------------------------------------------------------------

    /**
     * The fixtures store a 200×100 quadrant marker (red top-left, green
     * top-right, blue bottom-left, white bottom-right) tagged "rotate 90° CW".
     * Upright, red is top-right and the image is 100×200.
     */
    private fun assertUpright(name: String, bitmap: Bitmap) {
        assertEquals("$name width", 100, bitmap.width)
        assertEquals("$name height", 200, bitmap.height)
        fun at(x: Int, y: Int) = bitmap.getPixel(x, y)
        fun isRed(c: Int) = android.graphics.Color.red(c) > 200 && android.graphics.Color.green(c) < 80 && android.graphics.Color.blue(c) < 80
        fun isBlue(c: Int) = android.graphics.Color.blue(c) > 200 && android.graphics.Color.red(c) < 80 && android.graphics.Color.green(c) < 80
        assertTrue("$name: red should be top-right", isRed(at(75, 50)))
        assertTrue("$name: blue should be top-left", isBlue(at(25, 50)))
    }

    @Test fun rotationIsAppliedToThePixels() {
        for (name in listOf("rotated.jpg", "rotated.heic", "rotated.png")) {
            // A crop forces a write even if re-encoding saved nothing.
            val result = shrink(fixture(name), job(session = SessionFormat.JPEG, crop = CropTarget(100, 200, CropTarget.Mode.PIXELS)))
            assertUpright(name, decode(result.outputUri))
            val exif = ExifInterface(outputFile(result))
            assertEquals(
                "$name must not carry a rotation any more",
                ExifInterface.ORIENTATION_UNDEFINED,
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED),
            )
        }
    }

    /**
     * Every EXIF orientation, through each decode path (JPEG via ImageDecoder,
     * PNG and WebP via BitmapFactory plus an explicit rotation). The source is
     * a 200×100 marker: red top-left, green top-right, blue bottom-left, white
     * bottom-right. Each case says where red must end up once upright.
     */
    @Test fun everyExifOrientationIsAppliedOnEveryDecodePath() {
        val marker = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply {
            val canvas = android.graphics.Canvas(this)
            val paint = android.graphics.Paint()
            for ((rect, color) in listOf(
                android.graphics.Rect(0, 0, 100, 50) to android.graphics.Color.RED,
                android.graphics.Rect(100, 0, 200, 50) to android.graphics.Color.GREEN,
                android.graphics.Rect(0, 50, 100, 100) to android.graphics.Color.BLUE,
                android.graphics.Rect(100, 50, 200, 100) to android.graphics.Color.WHITE,
            )) { paint.color = color; canvas.drawRect(rect, paint) }
        }
        // orientation → (upright width, height, where red is: 0 TL, 1 TR, 2 BL, 3 BR)
        val expectations = mapOf(
            1 to Triple(200, 100, 0), 2 to Triple(200, 100, 1), 3 to Triple(200, 100, 3), 4 to Triple(200, 100, 2),
            5 to Triple(100, 200, 0), 6 to Triple(100, 200, 1), 7 to Triple(100, 200, 3), 8 to Triple(100, 200, 2),
        )
        val formats = listOf(
            Triple("jpg", Bitmap.CompressFormat.JPEG, SessionFormat.PNG),
            Triple("png", Bitmap.CompressFormat.PNG, SessionFormat.JPEG),
            Triple("webp", Bitmap.CompressFormat.WEBP_LOSSLESS, SessionFormat.JPEG),
        )
        for ((ext, compress, convertTo) in formats) for ((orientation, expected) in expectations) {
            val file = File(work, "o$orientation.$ext")
            file.outputStream().use { marker.compress(compress, 95, it) }
            ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
            val input = InputFile(Uri.fromFile(file), file.name, ext, file.length(), null, true)
            // Converting always writes a result, so the check never meets a kept original.
            val bitmap = decode(shrink(input, job(session = convertTo)).outputUri)
            val label = "$ext orientation $orientation"
            assertEquals("$label width", expected.first, bitmap.width)
            assertEquals("$label height", expected.second, bitmap.height)
            val (w, h) = bitmap.width to bitmap.height
            val corners = listOf(w / 4 to h / 4, 3 * w / 4 to h / 4, w / 4 to 3 * h / 4, 3 * w / 4 to 3 * h / 4)
            val (x, y) = corners[expected.third]
            val c = bitmap.getPixel(x, y)
            assertTrue(
                "$label: red expected in corner ${expected.third}, found #${Integer.toHexString(c)}",
                android.graphics.Color.red(c) > 200 && android.graphics.Color.green(c) < 80 && android.graphics.Color.blue(c) < 80,
            )
        }
    }

    /** Raw text of a tag. getAttribute() would show non-ASCII bytes as '?' even when the file is right. */
    private fun ExifInterface.text(tag: String): String? =
        getAttributeBytes(tag)?.toString(Charsets.UTF_8)?.trimEnd('\u0000')

    @Test fun metadataPoliciesOnJpegAndWebp() {
        val crop = CropTarget(100, 200, CropTarget.Mode.PIXELS)
        for (format in listOf(SessionFormat.JPEG, SessionFormat.WEBP)) {
            val all = ExifInterface(outputFile(shrink(fixture("rotated.jpg"), job(session = format, crop = crop, metadata = MetadataPolicy.ALL))))
            assertEquals("$format/all", "© 2026 Shrinker Pro Test", all.text(ExifInterface.TAG_COPYRIGHT))
            assertEquals("$format/all", "FixtureCam", all.getAttribute(ExifInterface.TAG_MAKE))

            val credit = ExifInterface(outputFile(shrink(fixture("rotated.jpg", "credit.jpg"), job(session = format, crop = crop, metadata = MetadataPolicy.COPYRIGHT))))
            assertEquals("$format/copyright", "© 2026 Shrinker Pro Test", credit.text(ExifInterface.TAG_COPYRIGHT))
            assertEquals("$format/copyright", "Fixture Generator", credit.getAttribute(ExifInterface.TAG_ARTIST))
            assertNull("$format/copyright drops camera", credit.getAttribute(ExifInterface.TAG_MAKE))

            val none = ExifInterface(outputFile(shrink(fixture("rotated.jpg", "none.jpg"), job(session = format, crop = crop, metadata = MetadataPolicy.NONE))))
            assertNull("$format/none", none.getAttribute(ExifInterface.TAG_COPYRIGHT))
            assertNull("$format/none", none.getAttribute(ExifInterface.TAG_MAKE))
        }
    }

    @Test fun avifCarriesExifWhenAsked() {
        val result = shrink(fixture("rotated.jpg"), job(session = SessionFormat.AVIF, metadata = MetadataPolicy.ALL))
        assertUpright("avif", decode(result.outputUri))
        // libavif stores EXIF in an 'Exif' item; look for the copyright text in the file.
        val text = String(outputFile(result).readBytes(), Charsets.ISO_8859_1)
        assertTrue("copyright should be embedded", text.contains("Shrinker Pro Test"))
        val stripped = shrink(fixture("rotated.jpg", "plain.jpg"), job(session = SessionFormat.AVIF, metadata = MetadataPolicy.NONE))
        assertFalse(String(outputFile(stripped).readBytes(), Charsets.ISO_8859_1).contains("Shrinker Pro Test"))
    }

    // ---- planning ----------------------------------------------------------------------------

    @Test fun planningNamesAndDestinations() {
        val folders = FolderCache(context)
        val input = fixture("sample.jpg", "photo.jpeg")
        val picturesPlan = engine.plan(input, job(), folders)
        assertEquals("photo.min.jpeg", picturesPlan.destination.name)
        assertNotNull(picturesPlan.destination.folder)

        val replacing = job().copy(location = SaveLocation.SAME_FOLDER, keepOriginal = false)
        val inPlace = engine.plan(input, replacing, folders)
        assertEquals("a writable single file is replaced in place", input.uri, inPlace.destination.inPlace)
        assertTrue(engine.overwritesInput(inPlace))

        val converting = engine.plan(fixture("sample.heic"), replacing, folders)
        assertNull("a conversion can't be written over the original", converting.destination.inPlace)
        assertEquals("sample.jpg", converting.destination.name)
    }

    @Test fun replacingInPlaceWritesOverTheOriginal() {
        val input = fixture("sample.png")
        val before = File(input.uri.path!!).length()
        val replacing = job().copy(location = SaveLocation.SAME_FOLDER, keepOriginal = false)
        val result = engine.shrink(engine.plan(input, replacing, FolderCache(context)), replacing)
        assertEquals(input.uri, result.outputUri)
        assertTrue(File(input.uri.path!!).length() < before)
        assertEquals(result.shrunkBytes, File(input.uri.path!!).length())
    }

    @Test fun mediaStoreFolderRoundTrip() {
        val folder = io.github.aliankarali.imageshrinker.engine.MediaStoreFolder.pictures(context, "test-${System.nanoTime()}")
        val name = "roundtrip.png"
        assertNull(folder.find(name))
        val source = File(work, "a.png").apply { writeBytes(File(fixture("sample.png").uri.path!!).readBytes()) }
        val uri = folder.write(name, "image/png", source, null)
        try {
            assertEquals(uri, folder.find(name))
            val smaller = File(work, "b.png").apply { writeBytes(source.readBytes().copyOf(1000)) }
            folder.write(name, "image/png", smaller, uri)
            val size = context.contentResolver.openFileDescriptor(uri, "r")!!.use { it.statSize }
            assertEquals("overwrite truncates", 1000L, size)
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }
}
