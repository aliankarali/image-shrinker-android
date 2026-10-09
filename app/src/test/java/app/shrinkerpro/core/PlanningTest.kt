package app.shrinkerpro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropGeometryTest {
    private fun crop(w: Int, h: Int, aw: Int, ah: Int) = CropGeometry.centeredCrop(PixelSize(w, h), AspectRatio(aw, ah))

    @Test fun nilAspectReturnsTheWholeFrame() {
        assertEquals(IntRect(0, 0, 400, 300), CropGeometry.centeredCrop(PixelSize(400, 300), null))
    }

    @Test fun sourceAlreadyTheTargetShapeIsUntouched() {
        assertEquals(IntRect(0, 0, 600, 600), crop(600, 600, 1, 1))
        assertEquals(IntRect(0, 0, 1920, 1080), crop(1920, 1080, 16, 9))
        assertEquals(IntRect(0, 0, 1920, 1080), crop(1920, 1080, 1920, 1080))
    }

    @Test fun landscapeCroppedSquareLosesItsSides() {
        assertEquals(IntRect(250, 0, 500, 500), crop(1000, 500, 1, 1))
    }

    @Test fun portraitCroppedSquareLosesTopAndBottom() {
        assertEquals(IntRect(0, 250, 500, 500), crop(500, 1000, 1, 1))
    }

    @Test fun portraitCroppedTo16By9ComesOutLandscape() {
        val rect = crop(500, 1000, 16, 9)
        assertEquals(500, rect.width)
        assertEquals(281, rect.height)
        assertEquals(0, rect.x)
        assertEquals(359, rect.y)
    }

    @Test fun landscapeCroppedTo9By16ComesOutPortrait() {
        val rect = crop(1000, 500, 9, 16)
        assertEquals(500, rect.height)
        assertEquals(281, rect.width)
    }

    @Test fun shapeIsExactRatherThanRounded() {
        assertEquals(IntRect(1040, 0, 1920, 1080), crop(4000, 1080, 16, 9))
        assertEquals(IntRect(0, 1040, 1080, 1920), crop(1080, 4000, 9, 16))
    }

    @Test fun oddLeftoverFallsToTheRightAndBottom() {
        assertEquals(IntRect(0, 0, 100, 100), crop(101, 100, 1, 1))
        assertEquals(IntRect(0, 0, 100, 100), crop(100, 101, 1, 1))
    }

    @Test fun nonsenseAspectIsTreatedAsNoCrop() {
        val whole = IntRect(0, 0, 400, 300)
        assertEquals(whole, crop(400, 300, 0, 1))
        assertEquals(whole, crop(400, 300, 1, 0))
        assertEquals(whole, crop(400, 300, -16, 9))
    }

    @Test fun degenerateShapesYieldAtLeastOnePixelAndStayInside() {
        for (w in listOf(1, 2, 3, 7, 100, 4001)) for (h in listOf(1, 2, 5, 99, 3000)) {
            for ((aw, ah) in listOf(1 to 1, 1 to 20000, 20000 to 1, 16 to 9, 3 to 2)) {
                val r = crop(w, h, aw, ah)
                assertTrue(r.width >= 1 && r.height >= 1)
                assertTrue(r.x >= 0 && r.y >= 0 && r.x + r.width <= w && r.y + r.height <= h)
                val again = CropGeometry.centeredCrop(PixelSize(r.width, r.height), AspectRatio(aw, ah))
                assertEquals("idempotent for ${w}x$h @ $aw:$ah", r.width, again.width)
                assertEquals(r.height, again.height)
            }
        }
    }
}

class SizePlannerTest {
    @Test fun nothingAskedMeansNoPlan() {
        assertNull(SizePlanner.plan(PixelSize(4000, 3000), null, null))
        assertNull(SizePlanner.plan(PixelSize(4000, 3000), 0, null))
    }

    @Test fun capScalesTheLongestSide() {
        assertEquals(PixelSize(2000, 1500), SizePlanner.plan(PixelSize(4000, 3000), 2000, null)!!.outputSize)
        assertEquals(PixelSize(1500, 2000), SizePlanner.plan(PixelSize(3000, 4000), 2000, null)!!.outputSize)
    }

    @Test fun imageInsideTheCapIsLeftAlone() {
        assertNull(SizePlanner.plan(PixelSize(800, 600), 2000, null))
    }

    @Test fun pixelCropNeverEnlarges() {
        val plan = SizePlanner.plan(PixelSize(800, 600), null, CropTarget(1200, 1200, CropTarget.Mode.PIXELS))!!
        assertEquals(PixelSize(600, 600), plan.outputSize)
        assertEquals(IntRect(100, 0, 600, 600), plan.cropRect)
    }

    @Test fun pixelCropSetsTheSizeAndIgnoresTheCap() {
        val plan = SizePlanner.plan(PixelSize(1000, 800), 100, CropTarget(400, 400, CropTarget.Mode.PIXELS))!!
        assertEquals(PixelSize(400, 400), plan.outputSize)
    }

    @Test fun nonSquarePixelCrop() {
        val plan = SizePlanner.plan(PixelSize(548, 547), null, CropTarget(320, 180, CropTarget.Mode.PIXELS))!!
        assertEquals(PixelSize(320, 180), plan.outputSize)
    }

    @Test fun ratioCropKeepsResolution() {
        val plan = SizePlanner.plan(PixelSize(548, 600), null, CropTarget(1, 1, CropTarget.Mode.RATIO))!!
        assertEquals(PixelSize(548, 548), plan.outputSize)
        assertTrue(plan.isCropping)
        assertEquals(1.0, plan.decodeScale, 0.0)
    }

    @Test fun ratioCropAndCapCompose() {
        val plan = SizePlanner.plan(PixelSize(1000, 1000), 300, CropTarget(2, 1, CropTarget.Mode.RATIO))!!
        assertEquals(PixelSize(300, 150), plan.outputSize)
    }

    @Test fun cropMatchingTheSourceIsNoPlan() {
        assertNull(SizePlanner.plan(PixelSize(600, 600), null, CropTarget(1, 1, CropTarget.Mode.RATIO)))
    }
}

class ConversionRouterTest {
    private val defaults = ConversionRules()

    @Test fun defaultsKeepEveryFormatExceptHeic() {
        assertEquals(OutputFormat.PNG, ConversionRouter.destination("png", defaults, null))
        assertEquals(OutputFormat.JPEG, ConversionRouter.destination("JPG", defaults, null))
        assertEquals(OutputFormat.JPEG, ConversionRouter.destination("jpeg", defaults, null))
        assertEquals(OutputFormat.WEBP, ConversionRouter.destination("webp", defaults, null))
        assertEquals(OutputFormat.AVIF, ConversionRouter.destination("avif", defaults, null))
        assertEquals(OutputFormat.JPEG, ConversionRouter.destination("heic", defaults, null))
        assertEquals(OutputFormat.JPEG, ConversionRouter.destination("heif", defaults, null))
    }

    @Test fun svgAndGifIgnoreRulesAndOverride() {
        val rules = ConversionRules(png = ConversionTarget.WEBP)
        for (session in listOf(null) + SessionFormat.entries) {
            assertEquals(OutputFormat.SVG, ConversionRouter.destination("svg", rules, session))
            assertEquals(OutputFormat.GIF, ConversionRouter.destination("gif", rules, session))
        }
    }

    @Test fun perFormatRulesApplyIndependently() {
        val rules = ConversionRules(png = ConversionTarget.WEBP, heic = ConversionFormat.AVIF)
        assertEquals(OutputFormat.WEBP, ConversionRouter.destination("png", rules, null))
        assertEquals(OutputFormat.JPEG, ConversionRouter.destination("jpg", rules, null))
        assertEquals(OutputFormat.AVIF, ConversionRouter.destination("heic", rules, null))
    }

    @Test fun sessionOverrideReplacesEveryRule() {
        val rules = ConversionRules(png = ConversionTarget.WEBP)
        for (ext in listOf("png", "jpg", "webp", "avif", "heic")) {
            assertEquals(OutputFormat.PNG, ConversionRouter.destination(ext, rules, SessionFormat.PNG))
        }
    }

    @Test fun unsupportedIsNull() {
        assertNull(ConversionRouter.destination("bmp", defaults, null))
        assertNull(ConversionRouter.destination("", defaults, null))
    }

    @Test fun sameFormatDetection() {
        assertTrue(ConversionRouter.isSameFormat("jpeg", OutputFormat.JPEG))
        assertTrue(ConversionRouter.isSameFormat("png", OutputFormat.PNG))
        assertTrue(ConversionRouter.isSameFormat("gif", OutputFormat.GIF))
        assertFalse(ConversionRouter.isSameFormat("png", OutputFormat.WEBP))
        assertFalse("HEIC is always a conversion", ConversionRouter.isSameFormat("heic", OutputFormat.JPEG))
    }
}

class SessionFieldsTest {
    @Test fun filterKeepsFiveDigitsWithoutLeadingZeros() {
        assertEquals("12345", MaxSizeField.filter("00123abc456"))
        assertEquals("", MaxSizeField.filter("000"))
        assertEquals("2000", MaxSizeField.filter("2,000"))
    }

    @Test fun dimensionClamps() {
        assertNull(MaxSizeField.dimension(""))
        assertNull(MaxSizeField.dimension("0"))
        assertEquals(20_000, MaxSizeField.dimension("99999"))
        assertEquals(1, MaxSizeField.dimension("1"))
        assertEquals("20000", MaxSizeField.committed("25000"))
        assertEquals("", MaxSizeField.committed("abc"))
    }

    @Test fun halfFilledCrop() {
        assertTrue(CropField.isHalfFilled("16", ""))
        assertTrue(CropField.isHalfFilled("", "9"))
        assertFalse(CropField.isHalfFilled("16", "9"))
        assertFalse(CropField.isHalfFilled("", ""))
        assertNull(CropField.target("16", "", CropTarget.Mode.RATIO))
        assertEquals(CropTarget(16, 9, CropTarget.Mode.RATIO), CropField.target("16", "9", CropTarget.Mode.RATIO))
    }

    @Test fun modifiedState() {
        val q = QualityLevel.STANDARD
        assertFalse(SessionState.isModified(null, null, q, null, null))
        assertFalse("choosing the stored quality is not a change", SessionState.isModified(null, q, q, null, null))
        assertTrue(SessionState.isModified(null, QualityLevel.HIGH, q, null, null))
        assertTrue(SessionState.isModified(SessionFormat.WEBP, null, q, null, null))
        assertTrue(SessionState.isModified(null, null, q, 2000, null))
        assertTrue(SessionState.isModified(null, null, q, null, CropTarget(1, 1, CropTarget.Mode.RATIO)))
    }

    @Test fun pixelCropSupersedesMaxSize() {
        assertTrue(SessionState.maxSizeIsSupersededByCrop(CropTarget(1, 1, CropTarget.Mode.PIXELS)))
        assertFalse(SessionState.maxSizeIsSupersededByCrop(CropTarget(1, 1, CropTarget.Mode.RATIO)))
        assertFalse(SessionState.maxSizeIsSupersededByCrop(null))
    }
}

class NamingTest {
    @Test fun outputNames() {
        assertEquals("photo.min.png", FileNames.outputName("photo.png", keepOriginal = true, targetExtension = null))
        assertEquals("photo.png", FileNames.outputName("photo.png", keepOriginal = false, targetExtension = null))
        assertEquals("photo.min.webp", FileNames.outputName("photo.png", keepOriginal = true, targetExtension = "webp"))
        assertEquals("my.photo.min.jpg", FileNames.outputName("my.photo.heic", keepOriginal = true, targetExtension = "jpg"))
    }

    @Test fun numberedAndUnique() {
        assertEquals("photo.min 2.jpg", FileNames.numbered("photo.min.jpg", 2))
        val taken = setOf("a.png", "a 2.png")
        assertEquals("a 3.png", FileNames.unique("a.png") { it in taken })
        assertEquals("b.png", FileNames.unique("b.png") { it in taken })
    }

    @Test fun namingStyle() {
        assertEquals(OutputNaming.BESIDE_ORIGINALS, OutputNaming.style(SaveLocation.SAME_FOLDER, useSubfolder = false))
        assertEquals(OutputNaming.SEPARATE_DESTINATION, OutputNaming.style(SaveLocation.SAME_FOLDER, useSubfolder = true))
        assertEquals(OutputNaming.SEPARATE_DESTINATION, OutputNaming.style(SaveLocation.PICTURES, useSubfolder = false))
        assertEquals(OutputNaming.SEPARATE_DESTINATION, OutputNaming.style(SaveLocation.CUSTOM, useSubfolder = false))
    }

    @Test fun replacesOriginalsOnlyBesideThemWithoutKeeping() {
        assertTrue(OutputWarning.replacesOriginals(false, SaveLocation.SAME_FOLDER, false))
        assertFalse(OutputWarning.replacesOriginals(true, SaveLocation.SAME_FOLDER, false))
        assertFalse(OutputWarning.replacesOriginals(false, SaveLocation.SAME_FOLDER, true))
        assertFalse(OutputWarning.replacesOriginals(false, SaveLocation.PICTURES, false))
    }
}

class OverwritePlannerTest {
    private data class Plan(val input: String, val key: DestinationKey)

    private fun apply(
        answer: OverwriteAnswer,
        plans: List<Plan>,
        group: List<Plan>,
        onDisk: Set<String> = emptySet(),
        claims: MutableMap<DestinationKey, Int> = plans.groupingBy { it.key }.eachCount().toMutableMap(),
    ) = OverwritePlanner.apply(answer, plans, group, { it.key }, { it.name in onDisk }, claims) { p, k -> p.copy(key = k) }

    @Test fun classifySplitsOriginalsFromExisting() {
        val plans = listOf(
            Plan("a.png", DestinationKey("f", "a.png")),
            Plan("b.png", DestinationKey("f", "b.min.png")),
            Plan("c.png", DestinationKey("f", "c.min.png")),
        )
        val onDisk = setOf("a.png", "b.min.png")
        val (originals, existing) = OverwritePlanner.classify(
            plans, existsOnDisk = { it.key.name in onDisk }, isOriginal = { it.key.name == it.input },
        )
        assertEquals(listOf("a.png"), originals.map { it.input })
        assertEquals(listOf("b.png"), existing.map { it.input })
    }

    @Test fun skipDropsOnlyThePlansAskedAbout() {
        val plans = listOf(Plan("a", DestinationKey("f", "a.min.png")), Plan("b", DestinationKey("f", "b.min.png")))
        assertEquals(listOf("b"), apply(OverwriteAnswer.SKIP, plans, listOf(plans[0])).map { it.input })
    }

    @Test fun keepBothNeverRenamesTwoPlansOntoEachOther() {
        // a.png and a.jpg both convert to a.min.webp, which already exists.
        val dest = DestinationKey("f", "a.min.webp")
        val plans = listOf(Plan("a.png", dest), Plan("a.jpg", dest))
        val result = apply(OverwriteAnswer.KEEP_BOTH, plans, plans, onDisk = setOf("a.min.webp"))
        assertEquals(listOf("a.min 2.webp", "a.min 3.webp"), result.map { it.key.name })
    }

    @Test fun keepBothSkipsNamesAlreadyOnDisk() {
        val plans = listOf(Plan("a", DestinationKey("f", "a.min.png")))
        val result = apply(OverwriteAnswer.KEEP_BOTH, plans, plans, onDisk = setOf("a.min.png", "a.min 2.png"))
        assertEquals("a.min 3.png", result.single().key.name)
    }

    // photo.jpg (replacing itself) and photo.heic (converting to photo.jpg) share a
    // destination but are asked about in different groups.
    private val shared = DestinationKey("f", "photo.jpg")
    private val original = Plan("photo.jpg", shared)
    private val conversion = Plan("photo.heic", shared)

    @Test fun skippingTheConversionKeepsTheReplacedOriginal() {
        val result = apply(OverwriteAnswer.SKIP, listOf(original, conversion), listOf(conversion), onDisk = setOf("photo.jpg"))
        assertEquals(listOf(original), result)
    }

    @Test fun keepingBothForTheConversionDoesNotMoveTheReplacedOriginal() {
        val result = apply(OverwriteAnswer.KEEP_BOTH, listOf(original, conversion), listOf(conversion), onDisk = setOf("photo.jpg"))
        assertEquals("photo.jpg", result[0].key.name)
        assertEquals("photo 2.jpg", result[1].key.name)
    }
}
