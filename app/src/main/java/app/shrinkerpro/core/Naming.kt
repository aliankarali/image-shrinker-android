package app.shrinkerpro.core

/** Where results are written. */
enum class SaveLocation(val key: String) {
    /** Pictures/Shrinker Pro, through MediaStore. Needs no permission. */
    PICTURES("pictures"),

    /** Beside each original, when the app can write to the original's folder. */
    SAME_FOLDER("same"),

    /** A folder the user picked through the system folder picker. */
    CUSTOM("custom");

    companion object {
        fun fromKey(key: String?): SaveLocation? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Whether outputs land beside the originals or somewhere separate. Decides how
 * the "keep original" choice is worded: beside the originals it is about the
 * files themselves (keep or replace); elsewhere it is only about the filename.
 */
enum class OutputNaming {
    BESIDE_ORIGINALS,
    SEPARATE_DESTINATION;

    companion object {
        fun style(location: SaveLocation, useSubfolder: Boolean): OutputNaming = when {
            useSubfolder -> SEPARATE_DESTINATION
            location != SaveLocation.SAME_FOLDER -> SEPARATE_DESTINATION
            else -> BESIDE_ORIGINALS
        }
    }
}

object OutputWarning {
    /** True exactly when the settings as chosen write over the user's own files. */
    fun replacesOriginals(keepOriginal: Boolean, location: SaveLocation, useSubfolder: Boolean): Boolean =
        !keepOriginal && OutputNaming.style(location, useSubfolder) == OutputNaming.BESIDE_ORIGINALS
}

object FileNames {
    const val SUBFOLDER = "minified"

    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1)
    }

    fun stem(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }

    /**
     * `photo.png` → `photo.min.png` when keeping the original (or adding the
     * suffix), `photo.png` otherwise. [targetExtension] replaces the input's
     * extension when the file is converted.
     */
    fun outputName(inputName: String, keepOriginal: Boolean, targetExtension: String?): String {
        val ext = targetExtension ?: extension(inputName)
        val base = if (keepOriginal) stem(inputName) + ".min" else stem(inputName)
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    /** `photo.min.jpg` → `photo.min 2.jpg`, matching what Finder's "Keep Both" does. */
    fun numbered(name: String, counter: Int): String {
        val ext = extension(name)
        val stem = stem(name)
        return if (ext.isEmpty()) "$stem $counter" else "$stem $counter.$ext"
    }

    /** The first of `name`, `name 2`, `name 3`… for which [isTaken] is false. */
    fun unique(name: String, isTaken: (String) -> Boolean): String {
        if (!isTaken(name)) return name
        var counter = 2
        while (true) {
            val candidate = numbered(name, counter)
            if (!isTaken(candidate)) return candidate
            counter++
        }
    }
}

/** A destination inside one output folder, comparable across a batch. */
data class DestinationKey(val folder: String, val name: String)

enum class OverwriteCategory { ORIGINAL, EXISTING_FILE }

enum class OverwriteAnswer { SKIP, KEEP_BOTH, REPLACE }

object OverwritePlanner {

    /** Splits the plans that would overwrite something into originals and other existing files. */
    fun <T> classify(
        plans: List<T>,
        existsOnDisk: (T) -> Boolean,
        isOriginal: (T) -> Boolean,
    ): Pair<List<T>, List<T>> {
        val originals = mutableListOf<T>()
        val existing = mutableListOf<T>()
        for (plan in plans) {
            if (!existsOnDisk(plan)) continue
            if (isOriginal(plan)) originals += plan else existing += plan
        }
        return originals to existing
    }

    /**
     * Applies one answer to the plans it was asked about ([group]); every other
     * plan is left as it is, even one that targets the same name — it was
     * either answered in the other group or never collided.
     *
     * "Keep Both" renames each to the first numbered name that is neither on
     * disk nor claimed by another plan in the batch, so two inputs are never
     * renamed onto each other. [claims] counts how many plans currently target
     * each key and is updated in place.
     */
    fun <T> apply(
        answer: OverwriteAnswer,
        plans: List<T>,
        group: Collection<T>,
        keyOf: (T) -> DestinationKey,
        existsOnDisk: (DestinationKey) -> Boolean,
        claims: MutableMap<DestinationKey, Int>,
        renamed: (T, DestinationKey) -> T,
    ): List<T> {
        val asked = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<T, Boolean>()).apply { addAll(group) }
        return when (answer) {
            OverwriteAnswer.REPLACE -> plans
            OverwriteAnswer.SKIP -> plans.filter { plan ->
                if (plan !in asked) return@filter true
                val key = keyOf(plan)
                claims[key] = (claims[key] ?: 1) - 1
                false
            }
            OverwriteAnswer.KEEP_BOTH -> plans.map { plan ->
                if (plan !in asked) return@map plan
                val own = keyOf(plan)
                val freeName = FileNames.unique(own.name) { candidate ->
                    val key = DestinationKey(own.folder, candidate)
                    existsOnDisk(key) || (claims[key] ?: 0) > (if (key == own) 1 else 0)
                }
                val free = DestinationKey(own.folder, freeName)
                claims[own] = (claims[own] ?: 1) - 1
                claims[free] = (claims[free] ?: 0) + 1
                renamed(plan, free)
            }
        }
    }
}
