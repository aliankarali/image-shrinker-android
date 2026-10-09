package app.shrinkerpro.core

/** Text-field rules for the session bar's Max size field. */
object MaxSizeField {
    const val MAXIMUM = 20_000
    const val MINIMUM = 1
    const val MAXIMUM_DIGITS = 5

    /** Digits only, no leading zeros, at most five of them. Applied on every keystroke. */
    fun filter(text: String): String =
        text.filter { it in '0'..'9' }.trimStart('0').take(MAXIMUM_DIGITS)

    /** The cap in force, or `null` for none. Clamped to [MINIMUM]…[MAXIMUM]. */
    fun dimension(text: String): Int? {
        val value = filter(text).toIntOrNull() ?: return null
        if (value <= 0) return null
        return value.coerceIn(MINIMUM, MAXIMUM)
    }

    /** What the field shows once the panel closes: the clamped value, or empty. */
    fun committed(text: String): String = dimension(text)?.toString() ?: ""
}

object CropField {
    fun filter(text: String): String = MaxSizeField.filter(text)
    fun committed(text: String): String = MaxSizeField.committed(text)

    /**
     * Exactly one side filled in. Nothing can be cropped from one number, so a
     * half-filled crop blocks both closing the panel and shrinking — running
     * the batch as though the number had never been typed is not undoable once
     * originals are replaced.
     */
    fun isHalfFilled(width: String, height: String): Boolean =
        (MaxSizeField.dimension(width) == null) != (MaxSizeField.dimension(height) == null)

    fun target(width: String, height: String, mode: CropTarget.Mode): CropTarget? {
        val w = MaxSizeField.dimension(width) ?: return null
        val h = MaxSizeField.dimension(height) ?: return null
        return CropTarget(w, h, mode)
    }
}

object SessionState {
    /** Whether anything in the session bar differs from what the app would do on its own. */
    fun isModified(
        format: SessionFormat?,
        quality: QualityLevel?,
        storedQuality: QualityLevel,
        maxDimension: Int?,
        crop: CropTarget?,
    ): Boolean = format != null ||
        (quality != null && quality != storedQuality) ||
        maxDimension != null ||
        crop != null

    /** A pixel crop sets the output size outright, so it supersedes the cap. */
    fun maxSizeIsSupersededByCrop(crop: CropTarget?): Boolean = crop?.exactSize != null
}
