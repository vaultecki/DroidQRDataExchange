package net.vaultcity.droid_qr_data_exchange.format

import net.vaultcity.droid_qr_data_exchange.crypto.CryptoUtils
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.floor

/** Raised when a part cannot be decrypted (wrong password or corrupted data). Mirrors `qr_multi_part.DecryptionError`. */
class DecryptionError(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One file or directory to bundle into the tar archive, sourced lazily so callers can stream from a `Uri`. */
data class TarEntryInput(
    val relativePath: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val openInputStream: (() -> InputStream)? = null,
)

/** One recovered tar entry, fully materialized in memory (payloads here are QR-code sized). */
data class TarEntryOutput(
    val relativePath: String,
    val isDirectory: Boolean,
    val bytes: ByteArray,
)

data class DecryptedPart(
    val version: Long,
    val partNumber: Int,
    val totalParts: Int,
    val data: ByteArray,
)

/**
 * Packs one or more files/directories into a set of QR-code strings and back. Ported from
 * `app/qr_multi_part.py`. Every QR code is fully self-contained: its own random salt, its own
 * independently derived Argon2i key, its own NaCl SecretBox encryption. The part/total-part
 * bookkeeping ('v', 'p', 't', 'd') lives *inside* the ciphertext, not next to it.
 */
object QrMultiPart {

    fun buildTar(entries: List<TarEntryInput>): ByteArray {
        val buffer = ByteArrayOutputStream()
        TarArchiveOutputStream(buffer).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            for (entry in entries) {
                val name = if (entry.isDirectory) "${entry.relativePath}/" else entry.relativePath
                val tarEntry = TarArchiveEntry(name)
                if (!entry.isDirectory) {
                    tarEntry.size = entry.size
                }
                tar.putArchiveEntry(tarEntry)
                if (!entry.isDirectory) {
                    checkNotNull(entry.openInputStream) { "file entry '${entry.relativePath}' has no content source" }
                        .invoke().use { input -> input.copyTo(tar) }
                }
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
        return buffer.toByteArray()
    }

    /** Safely extracts a tar archive's entries into memory. Directories are included but empty. */
    fun extractTarEntries(tarBytes: ByteArray): List<TarEntryOutput> {
        val results = mutableListOf<TarEntryOutput>()
        TarArchiveInputStream(ByteArrayInputStream(tarBytes)).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                validateSafePath(entry.name)
                val content = if (entry.isDirectory) ByteArray(0) else tar.readBytes()
                results.add(TarEntryOutput(entry.name.trimEnd('/'), entry.isDirectory, content))
                entry = tar.nextEntry
            }
        }
        return results
    }

    private fun validateSafePath(name: String) {
        val normalized = name.trimEnd('/')
        val escapes = normalized.startsWith("/") || normalized.split("/").any { it == ".." }
        if (escapes) {
            throw DecryptionError("Unsafe path in archive: $name")
        }
    }

    // Python's `preset 9 | PRESET_EXTREME` uses a 64 MiB dictionary, whose BT4 match-finder
    // needs ~9.5x that in match-finder buffers (~600 MB) -- fine on a desktop, an instant
    // OutOfMemoryError on a phone's ~192-512 MB heap. Size the dictionary to the actual data
    // instead (QR-transfer payloads are realistically small), capped well within mobile limits.
    private const val MAX_LZMA_DICT_SIZE = 8 * 1024 * 1024 // 8 MiB

    private fun lzmaCompress(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val dictSize = data.size.coerceIn(LZMA2Options.DICT_SIZE_MIN, MAX_LZMA_DICT_SIZE)
        val options = LZMA2Options()
        options.setDictSize(dictSize)
        options.niceLen = LZMA2Options.NICE_LEN_MAX
        XZOutputStream(out, options).use { it.write(data) }
        return out.toByteArray()
    }

    private fun lzmaDecompress(data: ByteArray): ByteArray {
        return try {
            XZInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
        } catch (e: IOException) {
            throw DecryptionError(
                "Decompression failed. Parts may be corrupted or come from different encryption runs.",
                e,
            )
        }
    }

    /**
     * Computes how many raw (compressed) bytes fit into a single QR code's 'd' field, accounting
     * for msgpack + NaCl + base64 overhead. Mirrors `_max_chunk_size`.
     */
    private fun maxChunkSize(maxQrBytes: Int): Int {
        val dummyKey = ByteArray(32) // SecretBox key size; content is irrelevant for size measurement
        val dummySalt = CryptoUtils.generateSalt()
        // Worst-case placeholder p/t so msgpack's int encoding overhead isn't underestimated.
        val placeholderInner = packInner(v = 1, p = 999_999_999, t = 999_999_999, d = ByteArray(0))
        val dummyEncrypted = CryptoUtils.encrypt(placeholderInner, dummyKey)
        val dummyOuter = packOuter(dummySalt, dummyEncrypted)
        val overheadB64Len = CryptoUtils.encodeBase64(dummyOuter).length

        val budget = maxQrBytes - overheadB64Len
        require(budget > 0) { "max_qr_bytes=$maxQrBytes is too small to fit even an empty part" }

        // base64 expands raw bytes ~4/3; leave a safety margin rather than solving the exact byte boundary.
        val maxChunk = floor(budget * 0.70).toInt()
        require(maxChunk > 0) { "max_qr_bytes=$maxQrBytes is too small to fit any data" }
        return maxChunk
    }

    /** Packs the given files/directories into one or more QR code strings. */
    fun serializeToParts(entries: List<TarEntryInput>, password: String, maxQrBytes: Int): List<String> {
        require(entries.isNotEmpty()) { "No input paths provided" }

        val tarBytes = buildTar(entries)
        val compressed = lzmaCompress(tarBytes)

        val maxChunk = maxChunkSize(maxQrBytes)
        val totalParts = maxOf(1, ceil(compressed.size.toDouble() / maxChunk).toInt())

        val qrStrings = mutableListOf<String>()
        for (i in 0 until totalParts) {
            val start = i * maxChunk
            val end = minOf(start + maxChunk, compressed.size)
            val chunk = compressed.copyOfRange(start, end)
            val partNumber = i + 1

            val inner = packInner(v = 1, p = partNumber, t = totalParts, d = chunk)

            val salt = CryptoUtils.generateSalt()
            val key = CryptoUtils.deriveKey(password, salt)
            val encrypted = CryptoUtils.encrypt(inner, key)
            val outer = packOuter(salt, encrypted)

            val qrString = CryptoUtils.encodeBase64(outer)
            check(qrString.length <= maxQrBytes) {
                "Part $partNumber is too large at ${qrString.length} bytes. Maximum size: $maxQrBytes bytes"
            }
            qrStrings.add(qrString)
        }
        return qrStrings
    }

    /** Structural check only (no password needed): does this look like one of our QR codes? */
    fun isValidQrPart(qrString: String): Boolean {
        return try {
            val bytes = CryptoUtils.decodeBase64(qrString)
            val unpacker = MessagePack.newDefaultUnpacker(bytes)
            val mapSize = unpacker.unpackMapHeader()
            var hasS = false
            var hasE = false
            repeat(mapSize) {
                when (unpacker.unpackString()) {
                    "s" -> hasS = true
                    "e" -> hasE = true
                }
                unpacker.unpackValue()
            }
            unpacker.close()
            hasS && hasE
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Decrypts a single QR code string on its own, returning its inner {'v', 'p', 't', 'd'}
     * fields. Useful to learn a part's number/total-part count (or just to validate a password)
     * without needing every part.
     */
    fun decryptPart(qrString: String, password: String): DecryptedPart {
        try {
            val outerBytes = CryptoUtils.decodeBase64(qrString)
            val (salt, encrypted) = unpackOuter(outerBytes)
            val key = CryptoUtils.deriveKey(password, salt)
            val innerBytes = CryptoUtils.decrypt(encrypted, key)
            return unpackInner(innerBytes)
        } catch (e: Exception) {
            throw DecryptionError("Decryption failed. Wrong password or corrupted data.", e)
        }
    }

    /** Reassembles QR code strings (in any order) back into the original tar bytes. */
    fun deserializeToBytes(qrTexts: List<String>, password: String): ByteArray {
        require(qrTexts.isNotEmpty()) { "No QR codes provided" }

        val parts = qrTexts.map { decryptPart(it, password) }

        val totalParts = parts[0].totalParts
        for (part in parts) {
            require(part.totalParts == totalParts) { "Inconsistent total_parts across parts" }
        }

        val partNumbers = parts.map { it.partNumber }.toSet()
        val expectedParts = (1..totalParts).toSet()
        if (partNumbers != expectedParts) {
            val missing = (expectedParts - partNumbers).sorted()
            throw IllegalArgumentException("Missing parts: $missing")
        }

        val compressed = ByteArrayOutputStream().apply {
            parts.sortedBy { it.partNumber }.forEach { write(it.data) }
        }.toByteArray()

        return lzmaDecompress(compressed)
    }

    // --- msgpack wire format helpers -------------------------------------------------------

    private fun packInner(v: Int, p: Int, t: Int, d: ByteArray): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(4)
        packer.packString("v"); packer.packInt(v)
        packer.packString("p"); packer.packInt(p)
        packer.packString("t"); packer.packInt(t)
        packer.packString("d"); packer.packBinaryHeader(d.size); packer.writePayload(d)
        packer.close()
        return packer.toByteArray()
    }

    private fun unpackInner(bytes: ByteArray): DecryptedPart {
        val unpacker = MessagePack.newDefaultUnpacker(bytes)
        val mapSize = unpacker.unpackMapHeader()
        var v = 1L
        var p: Int? = null
        var t: Int? = null
        var d: ByteArray? = null
        repeat(mapSize) {
            when (unpacker.unpackString()) {
                "v" -> v = unpacker.unpackLong()
                "p" -> p = unpacker.unpackInt()
                "t" -> t = unpacker.unpackInt()
                "d" -> {
                    val len = unpacker.unpackBinaryHeader()
                    d = unpacker.readPayload(len)
                }
                else -> unpacker.unpackValue()
            }
        }
        unpacker.close()
        return DecryptedPart(
            version = v,
            partNumber = p ?: throw DecryptionError("Malformed part data: missing 'p'"),
            totalParts = t ?: throw DecryptionError("Malformed part data: missing 't'"),
            data = d ?: throw DecryptionError("Malformed part data: missing 'd'"),
        )
    }

    private fun packOuter(salt: ByteArray, encrypted: ByteArray): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(2)
        packer.packString("s"); packer.packBinaryHeader(salt.size); packer.writePayload(salt)
        packer.packString("e"); packer.packBinaryHeader(encrypted.size); packer.writePayload(encrypted)
        packer.close()
        return packer.toByteArray()
    }

    private fun unpackOuter(bytes: ByteArray): Pair<ByteArray, ByteArray> {
        val unpacker = MessagePack.newDefaultUnpacker(bytes)
        val mapSize = unpacker.unpackMapHeader()
        var s: ByteArray? = null
        var e: ByteArray? = null
        repeat(mapSize) {
            readOuterField(unpacker) { key, value -> if (key == "s") s = value else if (key == "e") e = value }
        }
        unpacker.close()
        return Pair(
            s ?: throw DecryptionError("Malformed QR part: missing 's'"),
            e ?: throw DecryptionError("Malformed QR part: missing 'e'"),
        )
    }

    private inline fun readOuterField(unpacker: MessageUnpacker, onBinary: (String, ByteArray) -> Unit) {
        val key = unpacker.unpackString()
        if (key == "s" || key == "e") {
            val len = unpacker.unpackBinaryHeader()
            onBinary(key, unpacker.readPayload(len))
        } else {
            unpacker.unpackValue()
        }
    }
}
