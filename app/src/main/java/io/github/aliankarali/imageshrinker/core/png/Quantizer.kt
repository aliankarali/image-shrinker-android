package io.github.aliankarali.imageshrinker.core.png

/**
 * A paletted image: [indices] holds one palette slot per pixel, row-major.
 * Palette entries are non-premultiplied ARGB.
 */
class IndexedImage(val width: Int, val height: Int, val palette: IntArray, val indices: ByteArray)

/**
 * Reduces an ARGB image to at most [maxColors] colours — the job pngquant does
 * for the desktop app.
 *
 * An image that already has few enough colours is palettised exactly, so the
 * result is lossless. Otherwise the palette is built by weighted median cut,
 * refined with k-means, and pixels are mapped with Floyd–Steinberg dithering.
 * Colours are compared premultiplied, so fully transparent pixels collapse to
 * one entry whatever RGB they carried, and translucent ones are matched by what
 * they actually look like.
 */
object Quantizer {

    // Perceptual weights: the eye is most sensitive to green, least to blue.
    private const val WA = 1.0f
    private const val WR = 0.5f
    private const val WG = 1.0f
    private const val WB = 0.45f

    private const val MAX_HISTOGRAM_BUCKETS = 120_000

    fun quantize(argb: IntArray, width: Int, height: Int, maxColors: Int = 256, dither: Boolean = true): IndexedImage {
        require(argb.size == width * height) { "pixel count ${argb.size} != ${width}x$height" }
        require(maxColors in 2..256)
        val pixels = IntArray(argb.size) { normalize(argb[it]) }

        exactPalette(pixels, maxColors)?.let { (palette, lookup) ->
            val indices = ByteArray(pixels.size) { lookup.get(pixels[it]).toByte() }
            return ordered(IndexedImage(width, height, palette, indices))
        }

        val histogram = Histogram.build(pixels)
        val hasClear = pixels.any { it == 0 }
        var palette = medianCut(histogram, maxColors)
        palette = refine(histogram, palette, iterations = 3, keepClear = hasClear)
        val indices = map(pixels, width, height, palette, dither)
        return ordered(IndexedImage(width, height, palette.toArgb(), indices))
    }

    /** Maps [argb] onto a fixed [palette] (no dithering): used for GIF frames sharing one palette. */
    fun mapToPalette(argb: IntArray, palette: IntArray): IntArray {
        val p = Palette.fromArgb(palette)
        val cache = NearestCache(p)
        return IntArray(argb.size) { i ->
            val c = normalize(argb[i])
            palette[cache.nearest(c)]
        }
    }

    /** Builds a palette of at most [maxColors] entries for pixels gathered from several images. */
    fun buildPalette(argb: IntArray, maxColors: Int): IntArray {
        val pixels = IntArray(argb.size) { normalize(argb[it]) }
        exactPalette(pixels, maxColors)?.let { return it.first }
        val histogram = Histogram.build(pixels)
        return refine(histogram, medianCut(histogram, maxColors), iterations = 3, keepClear = pixels.any { it == 0 }).toArgb()
    }

    /** Fully transparent pixels all become 0x00000000; their RGB is invisible. */
    internal fun normalize(c: Int): Int = if (c ushr 24 == 0) 0 else c

    // ---- exact path -------------------------------------------------------------

    private fun exactPalette(pixels: IntArray, maxColors: Int): Pair<IntArray, IntIntMap>? {
        val map = IntIntMap(512)
        var count = 0
        for (c in pixels) {
            if (map.get(c) >= 0) continue
            if (count == maxColors) return null
            map.put(c, count++)
        }
        val palette = IntArray(count)
        map.forEach { color, index -> palette[index] = color }
        return palette to map
    }

    // ---- palette construction ---------------------------------------------------

    /** Weighted colour histogram over slightly reduced precision, with exact centroids per bucket. */
    internal class Histogram(
        val count: Int,
        val weight: FloatArray,
        val a: FloatArray, val r: FloatArray, val g: FloatArray, val b: FloatArray, // premultiplied, 0..255
    ) {
        companion object {
            fun build(pixels: IntArray): Histogram {
                // Start at 6 bits per channel; fall back to 5 if the image is so
                // varied that the histogram would make refinement slow.
                for (shift in intArrayOf(2, 3, 4)) {
                    val h = tryBuild(pixels, shift, MAX_HISTOGRAM_BUCKETS)
                    if (h != null) return h
                }
                return tryBuild(pixels, 4, Int.MAX_VALUE)!!
            }

            private fun tryBuild(pixels: IntArray, shift: Int, limit: Int): Histogram? {
                val slots = IntIntMap(4096)
                var n = 0
                var weight = FloatArray(4096)
                var sa = DoubleArray(4096); var sr = DoubleArray(4096); var sg = DoubleArray(4096); var sb = DoubleArray(4096)
                for (c in pixels) {
                    val ca = c ushr 24
                    val key = ((ca shr shift) shl 24) or ((((c shr 16) and 0xFF) shr shift) shl 16) or
                        ((((c shr 8) and 0xFF) shr shift) shl 8) or ((c and 0xFF) shr shift)
                    var slot = slots.get(key)
                    if (slot < 0) {
                        if (n == limit) return null
                        slot = n++
                        slots.put(key, slot)
                        if (slot == weight.size) {
                            val size = weight.size * 2
                            weight = weight.copyOf(size)
                            sa = sa.copyOf(size); sr = sr.copyOf(size); sg = sg.copyOf(size); sb = sb.copyOf(size)
                        }
                    }
                    val alpha = ca / 255.0
                    weight[slot] += 1f
                    sa[slot] += ca.toDouble()
                    sr[slot] += ((c shr 16) and 0xFF) * alpha
                    sg[slot] += ((c shr 8) and 0xFF) * alpha
                    sb[slot] += (c and 0xFF) * alpha
                }
                val a = FloatArray(n); val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
                for (i in 0 until n) {
                    val w = weight[i].toDouble()
                    a[i] = (sa[i] / w).toFloat(); r[i] = (sr[i] / w).toFloat()
                    g[i] = (sg[i] / w).toFloat(); b[i] = (sb[i] / w).toFloat()
                }
                return Histogram(n, weight.copyOf(n), a, r, g, b)
            }
        }
    }

    /** Premultiplied float palette. */
    internal class Palette(val size: Int, val a: FloatArray, val r: FloatArray, val g: FloatArray, val b: FloatArray) {
        fun toArgb(): IntArray = IntArray(size) { i ->
            val alpha = a[i].coerceIn(0f, 255f)
            val ai = Math.round(alpha)
            if (ai == 0) return@IntArray 0
            val k = 255f / alpha
            val ri = Math.round((r[i] * k).coerceIn(0f, 255f))
            val gi = Math.round((g[i] * k).coerceIn(0f, 255f))
            val bi = Math.round((b[i] * k).coerceIn(0f, 255f))
            (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
        }

        fun nearest(pa: Float, pr: Float, pg: Float, pb: Float): Int {
            var best = 0
            var bestDistance = Float.MAX_VALUE
            for (i in 0 until size) {
                val da = a[i] - pa; val dr = r[i] - pr; val dg = g[i] - pg; val db = b[i] - pb
                val d = WA * da * da + WR * dr * dr + WG * dg * dg + WB * db * db
                if (d < bestDistance) { bestDistance = d; best = i }
            }
            return best
        }

        companion object {
            fun fromArgb(colors: IntArray): Palette {
                val n = colors.size
                val a = FloatArray(n); val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
                for (i in 0 until n) {
                    val c = colors[i]
                    val ca = (c ushr 24).toFloat()
                    val k = ca / 255f
                    a[i] = ca
                    r[i] = ((c shr 16) and 0xFF) * k; g[i] = ((c shr 8) and 0xFF) * k; b[i] = (c and 0xFF) * k
                }
                return Palette(n, a, r, g, b)
            }
        }
    }

    private class Box(val from: Int, val to: Int, val score: Double)

    private fun medianCut(h: Histogram, maxColors: Int): Palette {
        val order = IntArray(h.count) { it }
        val channels = arrayOf(h.a, h.r, h.g, h.b)
        val weights = floatArrayOf(WA, WR, WG, WB)

        fun score(from: Int, to: Int): Double {
            if (to - from < 2) return 0.0
            var total = 0.0
            for (ch in 0 until 4) total += weights[ch] * variance(order, from, to, channels[ch], h.weight)
            var w = 0.0
            for (i in from until to) w += h.weight[order[i]]
            return total * w
        }

        val boxes = ArrayList<Box>()
        boxes += Box(0, h.count, score(0, h.count))
        while (boxes.size < maxColors) {
            val index = boxes.indices.maxByOrNull { boxes[it].score } ?: break
            val box = boxes[index]
            if (box.score <= 0.0) break

            // Split along the channel with the largest weighted spread.
            var axis = 0
            var axisVariance = -1.0
            for (ch in 0 until 4) {
                val v = weights[ch] * variance(order, box.from, box.to, channels[ch], h.weight)
                if (v > axisVariance) { axisVariance = v; axis = ch }
            }
            val key = channels[axis]
            sortRange(order, box.from, box.to, key)

            var total = 0.0
            for (i in box.from until box.to) total += h.weight[order[i]]
            // Weighted median: the first position where half the weight lies before it.
            var running = 0.0
            var split = box.to - 1
            for (i in box.from until box.to) {
                running += h.weight[order[i]]
                if (running >= total / 2) { split = i + 1; break }
            }
            split = split.coerceIn(box.from + 1, box.to - 1)

            boxes.removeAt(index)
            boxes += Box(box.from, split, score(box.from, split))
            boxes += Box(split, box.to, score(split, box.to))
        }

        val n = boxes.size
        val a = FloatArray(n); val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
        for ((i, box) in boxes.withIndex()) {
            var w = 0.0; var sa = 0.0; var sr = 0.0; var sg = 0.0; var sb = 0.0
            for (j in box.from until box.to) {
                val s = order[j]
                val ws = h.weight[s].toDouble()
                w += ws; sa += h.a[s] * ws; sr += h.r[s] * ws; sg += h.g[s] * ws; sb += h.b[s] * ws
            }
            a[i] = (sa / w).toFloat(); r[i] = (sr / w).toFloat(); g[i] = (sg / w).toFloat(); b[i] = (sb / w).toFloat()
        }
        return Palette(n, a, r, g, b)
    }

    private fun variance(order: IntArray, from: Int, to: Int, values: FloatArray, weight: FloatArray): Double {
        var w = 0.0; var sum = 0.0; var sumSq = 0.0
        for (i in from until to) {
            val s = order[i]
            val ws = weight[s].toDouble()
            val v = values[s].toDouble()
            w += ws; sum += v * ws; sumSq += v * v * ws
        }
        if (w == 0.0) return 0.0
        val mean = sum / w
        return maxOf(0.0, sumSq / w - mean * mean)
    }

    private fun sortRange(order: IntArray, from: Int, to: Int, key: FloatArray) {
        val slice = order.copyOfRange(from, to).sortedBy { key[it] }
        for (i in slice.indices) order[from + i] = slice[i]
    }

    /** Lloyd iterations: move each palette entry to the centroid of the colours nearest it. */
    private fun refine(h: Histogram, start: Palette, iterations: Int, keepClear: Boolean): Palette {
        var p = start
        repeat(iterations) {
            val n = p.size
            val w = DoubleArray(n); val sa = DoubleArray(n); val sr = DoubleArray(n); val sg = DoubleArray(n); val sb = DoubleArray(n)
            for (i in 0 until h.count) {
                val k = p.nearest(h.a[i], h.r[i], h.g[i], h.b[i])
                val ws = h.weight[i].toDouble()
                w[k] += ws; sa[k] += h.a[i] * ws; sr[k] += h.r[i] * ws; sg[k] += h.g[i] * ws; sb[k] += h.b[i] * ws
            }
            val a = p.a.copyOf(); val r = p.r.copyOf(); val g = p.g.copyOf(); val b = p.b.copyOf()
            for (k in 0 until n) if (w[k] > 0) {
                a[k] = (sa[k] / w[k]).toFloat(); r[k] = (sr[k] / w[k]).toFloat()
                g[k] = (sg[k] / w[k]).toFloat(); b[k] = (sb[k] / w[k]).toFloat()
            }
            p = Palette(n, a, r, g, b)
        }
        // Keep a true transparent entry when the image has fully transparent
        // pixels, so they stay exactly invisible rather than "almost".
        val nearlyClear = (0 until p.size).minByOrNull { p.a[it] }
        if (keepClear && nearlyClear != null && p.a[nearlyClear] < 4f) {
            p.a[nearlyClear] = 0f; p.r[nearlyClear] = 0f; p.g[nearlyClear] = 0f; p.b[nearlyClear] = 0f
        }
        return p
    }

    // ---- remapping --------------------------------------------------------------

    /** Memoises nearest-palette lookups by colour at 5 bits per channel. */
    private class NearestCache(private val palette: Palette) {
        private val cache = IntArray(1 shl 20) { -1 }

        fun nearest(c: Int): Int {
            val a = c ushr 24
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            return nearest(a, r, g, b)
        }

        fun nearest(a: Int, r: Int, g: Int, b: Int): Int {
            val key = ((a shr 3) shl 15) or ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            val hit = cache[key]
            if (hit >= 0) return hit
            val k = a / 255f
            val found = palette.nearest(a.toFloat(), r * k, g * k, b * k)
            cache[key] = found
            return found
        }
    }

    private fun map(pixels: IntArray, width: Int, height: Int, palette: Palette, dither: Boolean): ByteArray {
        val out = ByteArray(pixels.size)
        val cache = NearestCache(palette)
        val argb = palette.toArgb()
        val exact = IntIntMap(512).also { m -> argb.forEachIndexed { i, c -> if (m.get(c) < 0) m.put(c, i) } }
        val transparent = argb.indexOfFirst { it == 0 }

        if (!dither) {
            for (i in pixels.indices) {
                val c = pixels[i]
                val e = exact.get(c)
                out[i] = (if (e >= 0) e else cache.nearest(c)).toByte()
            }
            return out
        }

        // Serpentine Floyd–Steinberg on straight (non-premultiplied) channels.
        // Errors are damped slightly: full-strength diffusion adds noise that
        // costs bytes for little visible gain.
        val damping = 0.85f
        var errCur = FloatArray((width + 2) * 4)
        var errNext = FloatArray((width + 2) * 4)
        for (y in 0 until height) {
            val leftToRight = y % 2 == 0
            java.util.Arrays.fill(errNext, 0f)
            var x = if (leftToRight) 0 else width - 1
            val step = if (leftToRight) 1 else -1
            while (x in 0 until width) {
                val i = y * width + x
                val c = pixels[i]
                val e = (x + 1) * 4
                val ca = c ushr 24
                if (ca == 0 && transparent >= 0) {
                    // Never dither into or out of fully transparent areas.
                    out[i] = transparent.toByte()
                    x += step
                    continue
                }
                val a = (ca + errCur[e]).coerceIn(0f, 255f)
                val r = (((c shr 16) and 0xFF) + errCur[e + 1]).coerceIn(0f, 255f)
                val g = (((c shr 8) and 0xFF) + errCur[e + 2]).coerceIn(0f, 255f)
                val b = ((c and 0xFF) + errCur[e + 3]).coerceIn(0f, 255f)
                val k = cache.nearest(Math.round(a), Math.round(r), Math.round(g), Math.round(b))
                out[i] = k.toByte()

                val pc = argb[k]
                val da = (a - (pc ushr 24)) * damping
                val dr = (r - ((pc shr 16) and 0xFF)) * damping
                val dg = (g - ((pc shr 8) and 0xFF)) * damping
                val db = (b - (pc and 0xFF)) * damping
                val ahead = e + step * 4
                val behind = e - step * 4
                spread(errCur, ahead, da, dr, dg, db, 7f / 16f)
                spread(errNext, behind, da, dr, dg, db, 3f / 16f)
                spread(errNext, e, da, dr, dg, db, 5f / 16f)
                spread(errNext, ahead, da, dr, dg, db, 1f / 16f)
                x += step
            }
            val swap = errCur; errCur = errNext; errNext = swap
        }
        return out
    }

    private fun spread(err: FloatArray, at: Int, da: Float, dr: Float, dg: Float, db: Float, f: Float) {
        if (at < 0 || at + 3 >= err.size) return
        err[at] += da * f; err[at + 1] += dr * f; err[at + 2] += dg * f; err[at + 3] += db * f
    }

    /**
     * Puts translucent entries first (so the tRNS chunk can stop early) and
     * sorts the rest by luminance, which helps deflate a little.
     */
    private fun ordered(image: IndexedImage): IndexedImage {
        val palette = image.palette
        val order = palette.indices.sortedWith(
            compareBy<Int> { if (palette[it] ushr 24 == 255) 1 else 0 }
                .thenBy { palette[it] ushr 24 }
                .thenBy { luminance(palette[it]) },
        )
        val remap = IntArray(palette.size)
        order.forEachIndexed { newIndex, oldIndex -> remap[oldIndex] = newIndex }
        val newPalette = IntArray(palette.size) { palette[order[it]] }
        val indices = ByteArray(image.indices.size) { remap[image.indices[it].toInt() and 0xFF].toByte() }
        return IndexedImage(image.width, image.height, newPalette, indices)
    }

    private fun luminance(c: Int): Int =
        (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114)
}

/** Open-addressing Int→Int map with -1 as "absent"; avoids boxing on hot paths. */
internal class IntIntMap(initialCapacity: Int) {
    private var keys = IntArray(tableSize(initialCapacity))
    private var values = IntArray(keys.size)
    private var used = BooleanArray(keys.size)
    var size = 0
        private set

    fun get(key: Int): Int {
        val mask = keys.size - 1
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) return values[i]
            i = (i + 1) and mask
        }
        return -1
    }

    fun put(key: Int, value: Int) {
        if ((size + 1) * 2 > keys.size) grow()
        val mask = keys.size - 1
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) { values[i] = value; return }
            i = (i + 1) and mask
        }
        used[i] = true; keys[i] = key; values[i] = value; size++
    }

    fun forEach(action: (Int, Int) -> Unit) {
        for (i in keys.indices) if (used[i]) action(keys[i], values[i])
    }

    private fun grow() {
        val oldKeys = keys; val oldValues = values; val oldUsed = used
        keys = IntArray(oldKeys.size * 2); values = IntArray(keys.size); used = BooleanArray(keys.size)
        size = 0
        for (i in oldKeys.indices) if (oldUsed[i]) put(oldKeys[i], oldValues[i])
    }

    private fun mix(key: Int): Int {
        val h = key * -0x61c88647
        return h xor (h ushr 16)
    }

    private companion object {
        fun tableSize(capacity: Int): Int {
            var n = 16
            while (n < capacity * 2) n = n shl 1
            return n
        }
    }
}
