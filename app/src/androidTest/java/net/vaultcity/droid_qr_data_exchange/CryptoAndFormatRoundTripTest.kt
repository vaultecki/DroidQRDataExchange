// Copyright 2026 ecki
// SPDX-License-Identifier: Apache-2.0

package net.vaultcity.droid_qr_data_exchange

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.vaultcity.droid_qr_data_exchange.crypto.CryptoUtils
import net.vaultcity.droid_qr_data_exchange.format.DecryptionError
import net.vaultcity.droid_qr_data_exchange.format.QrMultiPart
import net.vaultcity.droid_qr_data_exchange.format.TarEntryInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import kotlin.random.Random

/**
 * Instrumented (needs a real Android runtime for the bundled libsodium native lib) round-trip
 * tests for the crypto + wire-format layer, mirroring the Python reference's `__main__`
 * self-tests in `crypt_utils.py` / `qr_multi_part.py`.
 */
@RunWith(AndroidJUnit4::class)
class CryptoAndFormatRoundTripTest {

    private fun fileEntry(path: String, content: ByteArray) = TarEntryInput(
        relativePath = path,
        isDirectory = false,
        size = content.size.toLong(),
        openInputStream = { ByteArrayInputStream(content) },
    )

    @Test
    fun cryptoRoundTrip() {
        val salt = CryptoUtils.generateSalt()
        assertEquals(16, salt.size)
        val key = CryptoUtils.deriveKey("hello", salt)
        assertEquals(32, key.size)

        val data = "These are secret bytes.".toByteArray()
        val encrypted = CryptoUtils.encrypt(data, key)
        val decrypted = CryptoUtils.decrypt(encrypted, key)
        assertEquals("These are secret bytes.", String(decrypted))
    }

    @Test
    fun singleSmallFileSinglePart() {
        val password = "test123"
        val entries = listOf(fileEntry("small.txt", "hello world".toByteArray()))

        val qrStrings = QrMultiPart.serializeToParts(entries, password, maxQrBytes = 2953)
        assertEquals(1, qrStrings.size)
        assertTrue(qrStrings.all { QrMultiPart.isValidQrPart(it) })

        val tarBytes = QrMultiPart.deserializeToBytes(qrStrings, password)
        val extracted = QrMultiPart.extractTarEntries(tarBytes)
        assertEquals(1, extracted.size)
        assertEquals("hello world", String(extracted[0].bytes))
    }

    @Test
    fun largeFileMultiPartShuffledReassembly() {
        val password = "test123"
        // Kept deliberately small: every part runs its own full Argon2i derivation, so a part
        // count in the single digits is plenty to exercise the multi-part path.
        val content = Random.nextBytes(2_500)
        val entries = listOf(fileEntry("large.bin", content))

        val qrStrings = QrMultiPart.serializeToParts(entries, password, maxQrBytes = 800)
        assertTrue("expected multiple parts", qrStrings.size > 1)

        val tarBytes = QrMultiPart.deserializeToBytes(qrStrings.shuffled(), password)
        val extracted = QrMultiPart.extractTarEntries(tarBytes)
        assertEquals(1, extracted.size)
        assertTrue(content.contentEquals(extracted[0].bytes))
    }

    @Test
    fun multipleFiles() {
        val password = "test123"
        val entries = listOf(
            fileEntry("a.txt", "file a".toByteArray()),
            fileEntry("b.txt", "file b".toByteArray()),
        )

        val qrStrings = QrMultiPart.serializeToParts(entries, password, maxQrBytes = 2953)
        val tarBytes = QrMultiPart.deserializeToBytes(qrStrings, password)
        val extracted = QrMultiPart.extractTarEntries(tarBytes)
        assertEquals(setOf("a.txt", "b.txt"), extracted.map { it.relativePath }.toSet())
    }

    @Test
    fun wholeFolderNested() {
        val password = "test123"
        val entries = listOf(
            TarEntryInput(relativePath = "myfolder", isDirectory = true),
            fileEntry("myfolder/root.txt", "root file".toByteArray()),
            TarEntryInput(relativePath = "myfolder/subdir", isDirectory = true),
            fileEntry("myfolder/subdir/nested.txt", "nested file".toByteArray()),
        )

        val qrStrings = QrMultiPart.serializeToParts(entries, password, maxQrBytes = 2953)
        val tarBytes = QrMultiPart.deserializeToBytes(qrStrings, password)
        val extracted = QrMultiPart.extractTarEntries(tarBytes)
        val files = extracted.filter { !it.isDirectory }.map { it.relativePath }.toSet()
        assertEquals(setOf("myfolder/root.txt", "myfolder/subdir/nested.txt"), files)
    }

    @Test
    fun missingPartsDetection() {
        val password = "test123"
        val entries = listOf(fileEntry("large.bin", Random.nextBytes(2_500)))
        val qrStrings = QrMultiPart.serializeToParts(entries, password, maxQrBytes = 800)
        assertTrue(qrStrings.size > 1)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            QrMultiPart.deserializeToBytes(qrStrings.dropLast(1), password)
        }
        assertTrue(exception.message!!.contains("Missing parts"))
    }

    @Test
    fun wrongPasswordDetection() {
        val password = "test123"
        val entries = listOf(fileEntry("large.bin", Random.nextBytes(2_500)))
        val qrStrings = QrMultiPart.serializeToParts(entries, password, maxQrBytes = 800)

        assertThrows(DecryptionError::class.java) {
            QrMultiPart.deserializeToBytes(qrStrings, "wrong password")
        }
    }
}
