package net.vaultcity.droid_qr_data_exchange.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import net.vaultcity.droid_qr_data_exchange.format.TarEntryInput
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Walks Storage-Access-Framework `Uri`s (picked files or a picked folder tree) into
 * [TarEntryInput]s for [net.vaultcity.droid_qr_data_exchange.format.QrMultiPart.buildTar],
 * mirroring how the desktop app's "Browse Files"/"Browse Folder" feed `tar.add(...)`.
 */
object SafInput {

    /** One [TarEntryInput] per picked file, each becoming its own tar-root entry (no folder nesting). */
    fun fromDocuments(context: Context, uris: List<Uri>): List<TarEntryInput> {
        return uris.map { uri ->
            val name = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "file"
            val knownSize = querySize(context, uri)
            if (knownSize != null && knownSize >= 0) {
                TarEntryInput(
                    relativePath = name,
                    isDirectory = false,
                    size = knownSize,
                    openInputStream = { openOrThrow(context, uri) },
                )
            } else {
                // Rare fallback for providers that don't report a size: buffer once to measure it.
                val bytes = openOrThrow(context, uri).use { it.readBytes() }
                TarEntryInput(
                    relativePath = name,
                    isDirectory = false,
                    size = bytes.size.toLong(),
                    openInputStream = { ByteArrayInputStream(bytes) },
                )
            }
        }
    }

    /** Recursively walks a picked folder tree, preserving the folder's own name as the tar root. */
    fun fromTree(context: Context, treeUri: Uri): List<TarEntryInput> {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("Cannot open folder: $treeUri")
        val rootName = root.name ?: "folder"
        val entries = mutableListOf<TarEntryInput>()
        walk(context, root, rootName, entries)
        return entries
    }

    private fun walk(context: Context, doc: DocumentFile, path: String, out: MutableList<TarEntryInput>) {
        if (doc.isDirectory) {
            out.add(TarEntryInput(relativePath = path, isDirectory = true))
            for (child in doc.listFiles()) {
                val childName = child.name ?: continue
                walk(context, child, "$path/$childName", out)
            }
        } else {
            out.add(
                TarEntryInput(
                    relativePath = path,
                    isDirectory = false,
                    size = doc.length(),
                    openInputStream = { openOrThrow(context, doc.uri) },
                ),
            )
        }
    }

    /** Reads a picked `.txt` part file's raw QR text (as saved by "Save All"). */
    fun readTextFile(context: Context, uri: Uri): String =
        openOrThrow(context, uri).bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()

    private fun openOrThrow(context: Context, uri: Uri) =
        context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return null
    }

    private fun querySize(context: Context, uri: Uri): Long? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) return cursor.getLong(idx)
        }
        return null
    }
}
