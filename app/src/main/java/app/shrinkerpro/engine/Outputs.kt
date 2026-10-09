package app.shrinkerpro.engine

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import app.shrinkerpro.core.DestinationKey
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** A place results can be written to, with a view of what's already there. */
interface OutputFolder {
    /** Stable identity, for telling destinations apart within a batch. */
    val key: String

    /** The folder's name as a person would recognise it, for dialogs. */
    val displayName: String?

    /** The existing file called [name], if any. */
    fun find(name: String): Uri?

    /** Writes [source] as [name], over [existing] when given, and returns where it went. */
    fun write(name: String, mimeType: String, source: File, existing: Uri?): Uri
}

/** Where a plan writes: a name in a folder, or straight over the input document itself. */
data class Destination(val folder: OutputFolder?, val name: String, val inPlace: Uri?) {
    val key: DestinationKey get() = DestinationKey(folder?.key ?: "inplace:$inPlace", name)
}

internal fun ContentResolver.overwrite(uri: Uri, source: File) {
    // "rwt" truncates where providers honour it; truncating explicitly after the
    // copy covers the ones that don't, so a shorter result never leaves the tail
    // of the old file behind.
    openFileDescriptor(uri, "rwt")?.use { pfd ->
        FileOutputStream(pfd.fileDescriptor).use { out ->
            FileInputStream(source).use { it.copyTo(out) }
            out.flush()
            runCatching { out.channel.truncate(source.length()) }
        }
    } ?: throw java.io.IOException("could not open $uri for writing")
}

/**
 * A folder in shared storage written through MediaStore, which needs no
 * permission on Android 10+ for files the app creates itself.
 */
class MediaStoreFolder(private val context: Context, val relativePath: String) : OutputFolder {
    override val key: String get() = "mediastore:$relativePath"
    override val displayName: String get() = relativePath.trimEnd('/').substringAfterLast('/')

    private val resolver get() = context.contentResolver

    private fun collections(): List<Uri> = listOf(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
    )

    override fun find(name: String): Uri? {
        for (collection in collections()) {
            val found = runCatching {
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf(relativePath, name),
                    null,
                )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }
            }.getOrNull()
            if (found != null) return found
        }
        return null
    }

    override fun write(name: String, mimeType: String, source: File, existing: Uri?): Uri {
        if (existing != null) {
            try {
                resolver.overwrite(existing, source)
                return existing
            } catch (_: SecurityException) {
                // Not ours (e.g. left by an earlier install): fall through and
                // add a new entry beside it instead.
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        // The Images collection only takes formats MediaStore treats as images;
        // anything it refuses goes to Downloads under the same folder name.
        val uri = runCatching {
            resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        }.getOrNull() ?: run {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath.replaceFirst(Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_DOWNLOADS))
            resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        } ?: throw java.io.IOException("MediaStore refused $name")
        try {
            resolver.openOutputStream(uri, "w")!!.use { out -> FileInputStream(source).use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    companion object {
        const val APP_FOLDER = "Shrinker Pro"

        fun pictures(context: Context, subfolder: String?): MediaStoreFolder {
            val base = "${Environment.DIRECTORY_PICTURES}/$APP_FOLDER/"
            return MediaStoreFolder(context, if (subfolder == null) base else "$base$subfolder/")
        }
    }
}

/**
 * A folder inside a document tree the user granted (a picked input folder, or
 * the custom output folder). [subfolder] is created on first write.
 */
class TreeFolder(
    private val context: Context,
    private val ref: TreeFolderRef,
    private val subfolder: String?,
) : OutputFolder {
    override val key: String get() = "tree:${ref.treeUri}#${ref.documentId}/${subfolder ?: ""}"

    override val displayName: String?
        get() = subfolder ?: resolver.query(
            DocumentsContract.buildDocumentUriUsingTree(ref.treeUri, ref.documentId),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }

    private val resolver get() = context.contentResolver

    /** Display name → document id, read once per batch and kept current as files are written. */
    private var listing: MutableMap<String, String>? = null
    private var targetId: String? = null
    private var targetResolved = false

    private fun target(create: Boolean): String? {
        if (subfolder == null) return ref.documentId
        if (!targetResolved) {
            targetId = children(ref.documentId).entries.firstOrNull { it.key == subfolder && isDirectory(it.value) }?.value
            targetResolved = true
        }
        if (targetId == null && create) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(ref.treeUri, ref.documentId)
            val made = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, subfolder)
                ?: throw java.io.IOException("could not create $subfolder")
            targetId = DocumentsContract.getDocumentId(made)
            listing = mutableMapOf()
        }
        return targetId
    }

    private fun isDirectory(id: String): Boolean {
        val uri = DocumentsContract.buildDocumentUriUsingTree(ref.treeUri, id)
        return resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use {
            it.moveToFirst() && it.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR
        } ?: false
    }

    private fun children(folderId: String): MutableMap<String, String> {
        val result = mutableMapOf<String, String>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(ref.treeUri, folderId)
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                result.putIfAbsent(name, id)
            }
        }
        return result
    }

    private fun currentListing(): MutableMap<String, String>? {
        val id = target(create = false) ?: return null
        return listing ?: children(id).also { listing = it }
    }

    override fun find(name: String): Uri? =
        currentListing()?.get(name)?.let { DocumentsContract.buildDocumentUriUsingTree(ref.treeUri, it) }

    override fun write(name: String, mimeType: String, source: File, existing: Uri?): Uri {
        if (existing != null) {
            resolver.overwrite(existing, source)
            return existing
        }
        val folderId = target(create = true)!!
        val parent = DocumentsContract.buildDocumentUriUsingTree(ref.treeUri, folderId)
        val created = DocumentsContract.createDocument(resolver, parent, mimeType, name)
            ?: throw java.io.IOException("could not create $name")
        try {
            resolver.openOutputStream(created, "w")!!.use { out -> FileInputStream(source).use { it.copyTo(out) } }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw e
        }
        // Providers may adjust the name (e.g. to avoid a clash); record what they chose.
        val actualName = resolver.query(created, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: name
        (listing ?: mutableMapOf<String, String>().also { listing = it })[actualName] = DocumentsContract.getDocumentId(created)
        return created
    }

    companion object {
        fun forTreeRoot(context: Context, treeUri: Uri, subfolder: String?): TreeFolder =
            TreeFolder(context, TreeFolderRef(treeUri, DocumentsContract.getTreeDocumentId(treeUri)), subfolder)
    }
}

/** Shares folder objects across a batch, so each folder is listed only once. */
class FolderCache(private val context: Context) {
    private val folders = HashMap<String, OutputFolder>()

    fun pictures(subfolder: String?): OutputFolder =
        MediaStoreFolder.pictures(context, subfolder).let { folders.getOrPut(it.key) { it } }

    fun tree(ref: TreeFolderRef, subfolder: String?): OutputFolder =
        TreeFolder(context, ref, subfolder).let { folders.getOrPut(it.key) { it } }

    fun treeRoot(treeUri: Uri, subfolder: String?): OutputFolder =
        TreeFolder.forTreeRoot(context, treeUri, subfolder).let { folders.getOrPut(it.key) { it } }
}
