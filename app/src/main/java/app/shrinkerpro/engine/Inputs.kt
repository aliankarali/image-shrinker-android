package app.shrinkerpro.engine

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.shrinkerpro.core.FileNames
import app.shrinkerpro.core.SupportedFormats

/** What one shrink failed on. The UI turns these into localized messages. */
class ShrinkException(
    val kind: Kind,
    val fileName: String,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception("$kind: $fileName${detail?.let { " ($it)" } ?: ""}", cause) {
    enum class Kind { UNSUPPORTED, READ, DECODE, ENCODE, WRITE, SVG, FOLDER_UNAVAILABLE, OTHER }
}

/** A folder reached through a document tree the user granted access to. */
data class TreeFolderRef(val treeUri: Uri, val documentId: String)

/** One file to shrink. */
data class InputFile(
    val uri: Uri,
    val name: String,
    val extension: String,
    /** Bytes, or -1 when the provider doesn't say. */
    val size: Long,
    /** The folder holding it, when it was found by walking a picked folder. */
    val parent: TreeFolderRef?,
    /** Whether the provider allows writing over this document in place. */
    val canWrite: Boolean,
    /** False when the app was given no access to it (e.g. a share without a read grant). */
    val readable: Boolean = true,
)

/** How files arrived: picked one by one, a picked folder, or shared/dropped from another app. */
sealed interface InputSource {
    data class Documents(val uris: List<Uri>) : InputSource
    data class Tree(val treeUri: Uri) : InputSource
    data class Shared(val uris: List<Uri>) : InputSource
}

class InputResolver(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * Expands every source into files. A folder includes its subfolders; hidden
     * entries are skipped, and inside a folder only supported formats are
     * picked up. Individually chosen files are kept whatever they are, so an
     * unsupported one is reported rather than silently ignored.
     */
    fun resolve(sources: List<InputSource>): List<InputFile> {
        val files = ArrayList<InputFile>()
        for (source in sources) when (source) {
            is InputSource.Documents -> source.uris.mapNotNullTo(files) { single(it) }
            is InputSource.Shared -> source.uris.mapNotNullTo(files) { single(it) }
            is InputSource.Tree -> walkTree(source.treeUri, files)
        }
        return files.distinctBy { it.uri }
    }

    private fun single(uri: Uri): InputFile? {
        var name: String? = null
        var size = -1L
        var flags = 0
        val isDocument = runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false)
        val projection = if (isDocument) {
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_FLAGS)
        } else {
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        }
        val queried = runCatching {
            resolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                    if (isDocument && !c.isNull(2)) flags = c.getInt(2)
                }
            }
        }.isSuccess
        // A failed lookup usually means no access at all; say so rather than
        // reporting the file as an unsupported format.
        val readable = queried || runCatching { resolver.openInputStream(uri)?.close() != null }.getOrDefault(false)
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            name = name ?: uri.lastPathSegment
        }
        val displayName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: return null
        val ext = FileNames.extension(displayName).lowercase().ifEmpty {
            SupportedFormats.extensionForMime(runCatching { resolver.getType(uri) }.getOrNull()) ?: ""
        }
        val canWrite = isDocument && flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0
        return InputFile(uri, displayName, ext, size, parent = null, canWrite = canWrite, readable = readable)
    }

    private fun walkTree(treeUri: Uri, into: MutableList<InputFile>) {
        runCatching {
            resolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val pending = ArrayDeque<String>().apply { add(rootId) }
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        while (pending.isNotEmpty()) {
            val folderId = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
            resolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending += id
                        continue
                    }
                    val ext = FileNames.extension(name).lowercase()
                    if (!SupportedFormats.isSupported(ext)) continue
                    into += InputFile(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                        name = name,
                        extension = ext,
                        size = if (c.isNull(3)) -1 else c.getLong(3),
                        parent = TreeFolderRef(treeUri, folderId),
                        canWrite = c.getInt(4) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0,
                    )
                }
            }
        }
    }
}
