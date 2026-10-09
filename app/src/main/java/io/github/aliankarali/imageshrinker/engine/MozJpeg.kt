package io.github.aliankarali.imageshrinker.engine

import android.graphics.Bitmap
import android.util.Log

/**
 * mozjpeg 4.1.5, set up exactly as the desktop app's `cjpeg` — the same bytes
 * for the same pixels (see app/src/main/cpp/mozjpeg_encode.c).
 */
object MozJpeg {
    val isAvailable: Boolean by lazy {
        try {
            System.loadLibrary("shrinkerjpeg")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w("MozJpeg", "mozjpeg unavailable; JPEGs will use the platform encoder", e)
            false
        }
    }

    /**
     * Encodes an opaque ARGB_8888 [bitmap] (alpha is ignored). A null [quality]
     * means mozjpeg's default, as the desktop app's Standard level does.
     * [icc] is embedded as the colour profile when given.
     */
    fun encode(bitmap: Bitmap, quality: Int?, icc: ByteArray?): ByteArray {
        require(bitmap.config == Bitmap.Config.ARGB_8888) { "mozjpeg needs ARGB_8888, got ${bitmap.config}" }
        return nativeEncode(bitmap, quality ?: -1, icc)
    }

    @JvmStatic
    private external fun nativeEncode(bitmap: Bitmap, quality: Int, icc: ByteArray?): ByteArray
}
