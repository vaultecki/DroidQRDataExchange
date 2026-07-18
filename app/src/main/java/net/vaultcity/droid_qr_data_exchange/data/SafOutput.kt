package net.vaultcity.droid_qr_data_exchange.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import net.vaultcity.droid_qr_data_exchange.format.TarEntryOutput
import java.io.IOException

/**
 * Materializes decrypted content back onto Storage-Access-Framework `Uri`s: extracted tar
 * entries into a picked output folder, and generated QR images/texts via "Save Current"/"Save All".
 */
object SafOutput {

    /** Recursively writes [entries] (as produced by `QrMultiPart.extractTarEntries`) under [outputTreeUri]. */
    fun writeTarEntries(context: Context, outputTreeUri: Uri, entries: List<TarEntryOutput>): List<String> {
        val root = DocumentFile.fromTreeUri(context, outputTreeUri)
            ?: throw IOException("Cannot open output folder: $outputTreeUri")

        val dirCache = HashMap<String, DocumentFile>().apply { put("", root) }

        fun ensureDir(path: String): DocumentFile {
            dirCache[path]?.let { return it }
            val parentPath = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            val parent = if (parentPath.isEmpty()) root else ensureDir(parentPath)
            val existing = parent.findFile(name)
            val dir = if (existing != null && existing.isDirectory) {
                existing
            } else {
                parent.createDirectory(name) ?: throw IOException("Cannot create directory: $path")
            }
            dirCache[path] = dir
            return dir
        }

        val written = mutableListOf<String>()
        for (entry in entries) {
            if (entry.isDirectory) {
                ensureDir(entry.relativePath)
                continue
            }
            val parentPath = entry.relativePath.substringBeforeLast('/', "")
            val fileName = entry.relativePath.substringAfterLast('/')
            val parentDir = if (parentPath.isEmpty()) root else ensureDir(parentPath)

            val file = parentDir.findFile(fileName)
                ?: parentDir.createFile("application/octet-stream", fileName)
                ?: throw IOException("Cannot create file: ${entry.relativePath}")

            context.contentResolver.openOutputStream(file.uri, "wt")?.use { out ->
                out.write(entry.bytes)
            } ?: throw IOException("Cannot open output stream for: ${entry.relativePath}")

            written.add(entry.relativePath)
        }
        return written
    }

    fun writeBitmapPng(context: Context, uri: Uri, bitmap: Bitmap) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: throw IOException("Cannot open output stream for: $uri")
    }

    fun writeText(context: Context, uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("Cannot open output stream for: $uri")
    }

    /** "Save All": writes every part as `qr_part_{i}_of_{total}.png` + matching `.txt` under [outputTreeUri]. */
    fun saveAllQrCodes(context: Context, outputTreeUri: Uri, images: List<Bitmap>, texts: List<String>) {
        val root = DocumentFile.fromTreeUri(context, outputTreeUri)
            ?: throw IOException("Cannot open output folder: $outputTreeUri")
        val total = images.size
        for (i in images.indices) {
            val partNumber = i + 1
            val pngFile = root.createFile("image/png", "qr_part_${partNumber}_of_${total}.png")
                ?: throw IOException("Cannot create PNG file for part $partNumber")
            writeBitmapPng(context, pngFile.uri, images[i])

            val txtFile = root.createFile("text/plain", "qr_part_${partNumber}_of_${total}.txt")
                ?: throw IOException("Cannot create text file for part $partNumber")
            writeText(context, txtFile.uri, texts[i])
        }
    }
}
