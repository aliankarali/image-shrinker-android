package app.shrinkerpro.ui

import android.content.res.Resources
import app.shrinkerpro.R
import app.shrinkerpro.core.ConversionTarget
import app.shrinkerpro.core.MetadataPolicy
import app.shrinkerpro.core.QualityLevel
import java.text.NumberFormat
import kotlin.math.abs

object Formatting {
    /**
     * File sizes in decimal units the way Finder writes them — "163 bytes",
     * "244 KB", "27.1 MB" — so the numbers match what the desktop app shows.
     */
    fun bytes(res: Resources, count: Long): String {
        val locale = res.configuration.locales[0]
        val sign = if (count < 0) "−" else ""
        val n = abs(count).toDouble()
        fun fmt(value: Double, digits: Int) = NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = digits
            minimumFractionDigits = 0
        }.format(value)
        return sign + when {
            n < 1_000 -> res.getQuantityString(R.plurals.byte_count, n.toInt(), n.toLong())
            n < 1_000_000 -> "${fmt(n / 1_000, 0)} KB"
            n < 1_000_000_000 -> "${fmt(n / 1_000_000, 1)} MB"
            else -> "${fmt(n / 1_000_000_000, 2)} GB"
        }
    }

    fun quality(res: Resources, level: QualityLevel): String = res.getString(
        when (level) {
            QualityLevel.SUPER_LOW -> R.string.quality_super_low
            QualityLevel.LOW -> R.string.quality_low
            QualityLevel.STANDARD -> R.string.quality_standard
            QualityLevel.HIGH -> R.string.quality_high
        },
    )

    fun metadata(res: Resources, policy: MetadataPolicy): String = res.getString(
        when (policy) {
            MetadataPolicy.ALL -> R.string.metadata_all
            MetadataPolicy.COPYRIGHT -> R.string.metadata_copyright
            MetadataPolicy.NONE -> R.string.metadata_none
        },
    )

    /** "Keep PNG" for the keep option, the format name otherwise. */
    fun conversionTarget(res: Resources, target: ConversionTarget, formatName: String): String =
        if (target == ConversionTarget.KEEP) res.getString(R.string.conversion_keep, formatName) else target.label
}
