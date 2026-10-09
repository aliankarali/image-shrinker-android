package io.github.aliankarali.imageshrinker.core

/**
 * Crop and downscale for ARGB pixel arrays (non-premultiplied). Downscaling is
 * area-averaged — each output pixel is the mean of exactly the source area it
 * covers — and done in premultiplied space so transparent pixels don't bleed
 * their invisible colour into the edges.
 */
object Resampler {

    fun crop(src: IntArray, width: Int, height: Int, rect: IntRect): IntArray {
        require(rect.x >= 0 && rect.y >= 0 && rect.x + rect.width <= width && rect.y + rect.height <= height)
        if (rect.x == 0 && rect.y == 0 && rect.width == width && rect.height == height) return src
        val out = IntArray(rect.width * rect.height)
        for (y in 0 until rect.height) {
            System.arraycopy(src, (rect.y + y) * width + rect.x, out, y * rect.width, rect.width)
        }
        return out
    }

    fun resize(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        if (sw == dw && sh == dh) return src
        // Separable: horizontal pass into premultiplied floats, then vertical.
        val xs = weights(sw, dw)
        val ys = weights(sh, dh)
        val tmp = FloatArray(dw * sh * 4)
        for (y in 0 until sh) {
            val row = y * sw
            for (x in 0 until dw) {
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                val c = xs[x]
                for (k in c.weights.indices) {
                    val p = src[row + c.start + k]
                    val w = c.weights[k]
                    val pa = (p ushr 24) / 255f
                    a += pa * w
                    r += ((p shr 16) and 0xFF) * pa * w
                    g += ((p shr 8) and 0xFF) * pa * w
                    b += (p and 0xFF) * pa * w
                }
                val o = (y * dw + x) * 4
                tmp[o] = a; tmp[o + 1] = r; tmp[o + 2] = g; tmp[o + 3] = b
            }
        }
        val out = IntArray(dw * dh)
        for (y in 0 until dh) {
            val c = ys[y]
            for (x in 0 until dw) {
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                for (k in c.weights.indices) {
                    val o = ((c.start + k) * dw + x) * 4
                    val w = c.weights[k]
                    a += tmp[o] * w; r += tmp[o + 1] * w; g += tmp[o + 2] * w; b += tmp[o + 3] * w
                }
                out[y * dw + x] = if (a <= 0f) 0 else {
                    val ai = Math.round(a * 255f).coerceIn(0, 255)
                    val ri = Math.round(r / a).coerceIn(0, 255)
                    val gi = Math.round(g / a).coerceIn(0, 255)
                    val bi = Math.round(b / a).coerceIn(0, 255)
                    (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
                }
            }
        }
        return out
    }

    private class Contribution(val start: Int, val weights: FloatArray)

    /** For each destination index, the source pixels it covers and by how much (summing to 1). */
    private fun weights(source: Int, destination: Int): Array<Contribution> {
        val scale = source.toDouble() / destination
        return Array(destination) { i ->
            val from = i * scale
            val to = minOf(source.toDouble(), (i + 1) * scale)
            val start = from.toInt().coerceIn(0, source - 1)
            val end = maxOf(start + 1, kotlin.math.ceil(to).toInt().coerceAtMost(source))
            val span = to - from
            val w = FloatArray(end - start) { k ->
                val px = start + k
                val overlap = minOf(to, px + 1.0) - maxOf(from, px.toDouble())
                (maxOf(0.0, overlap) / span).toFloat()
            }
            Contribution(start, w)
        }
    }
}
