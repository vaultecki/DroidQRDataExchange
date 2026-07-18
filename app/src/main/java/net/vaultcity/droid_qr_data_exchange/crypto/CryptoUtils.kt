// Copyright 2026 ecki
// SPDX-License-Identifier: Apache-2.0

package net.vaultcity.droid_qr_data_exchange.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.interfaces.SecretBox
import com.sun.jna.NativeLong
import java.nio.charset.StandardCharsets

/**
 * General crypto errors thrown by this module. Mirrors `crypt_utils.CryptoError` in the
 * Python reference implementation.
 */
class CryptoError(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Argon2i key derivation + NaCl SecretBox (XSalsa20-Poly1305) encryption, wire-compatible with
 * PyNaCl (`nacl.pwhash.argon2i` / `nacl.secret.SecretBox`) since both wrap the same libsodium C
 * library. Ported from `app/crypt_utils.py`.
 */
object CryptoUtils {

    private val lazySodium: LazySodiumAndroid by lazy { LazySodiumAndroid(SodiumAndroid()) }

    const val SALT_BYTES = PwHash.SALTBYTES // 16 -- same value for the argon2i-specific salt
    private const val KEY_BYTES = SecretBox.KEYBYTES // 32

    // lazysodium-android only exposes ARGON2ID-flavoured moderate constants; PyNaCl's
    // `nacl.pwhash.argon2i.OPSLIMIT_MODERATE`/`MEMLIMIT_MODERATE` map to the ARGON2I-specific
    // libsodium constants (crypto_pwhash_argon2i_OPSLIMIT_MODERATE = 6,
    // crypto_pwhash_argon2i_MEMLIMIT_MODERATE = 128 MiB), which must be hardcoded here to match.
    private const val ARGON2I_OPSLIMIT_MODERATE = 6L
    private const val ARGON2I_MEMLIMIT_MODERATE = 134_217_728L // 128 MiB

    /** Generates a secure salt with the length expected by Argon2i. */
    fun generateSalt(): ByteArray = lazySodium.randomBytesBuf(SALT_BYTES)

    private fun validateSalt(salt: ByteArray) {
        require(salt.size == SALT_BYTES) { "Salt must be $SALT_BYTES bytes long." }
    }

    /**
     * Derives a symmetric key from a password and salt using Argon2i13, with the same
     * opslimit/memlimit "moderate" defaults as the Python reference.
     */
    fun deriveKey(password: String, salt: ByteArray): ByteArray {
        require(password.isNotEmpty()) { "Password must be a non-empty string." }
        validateSalt(salt)

        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        val key = ByteArray(KEY_BYTES)
        val ok = lazySodium.cryptoPwHash(
            key,
            KEY_BYTES,
            passwordBytes,
            passwordBytes.size,
            salt,
            ARGON2I_OPSLIMIT_MODERATE,
            NativeLong(ARGON2I_MEMLIMIT_MODERATE),
            PwHash.Alg.PWHASH_ALG_ARGON2I13,
        )
        if (!ok) {
            throw CryptoError("Key derivation failed.")
        }
        return key
    }

    /** Encrypts raw bytes with SecretBox(key). Returns nonce + ciphertext (incl. MAC), combined. */
    fun encrypt(data: ByteArray, key: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "invalid key length" }

        val nonce = lazySodium.randomBytesBuf(SecretBox.NONCEBYTES)
        val cipherText = ByteArray(data.size + SecretBox.MACBYTES)
        val ok = lazySodium.cryptoSecretBoxEasy(cipherText, data, data.size.toLong(), nonce, key)
        if (!ok) {
            throw CryptoError("Encryption failed.")
        }
        return nonce + cipherText
    }

    /** Decrypts data that was created with [encrypt] (nonce + ciphertext, combined). */
    fun decrypt(encryptedData: ByteArray, key: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "invalid key length" }
        require(encryptedData.size >= SecretBox.NONCEBYTES + SecretBox.MACBYTES) {
            "encrypted data too short"
        }

        val nonce = encryptedData.copyOfRange(0, SecretBox.NONCEBYTES)
        val cipherText = encryptedData.copyOfRange(SecretBox.NONCEBYTES, encryptedData.size)
        val plainText = ByteArray(cipherText.size - SecretBox.MACBYTES)
        val ok = lazySodium.cryptoSecretBoxOpenEasy(plainText, cipherText, cipherText.size.toLong(), nonce, key)
        if (!ok) {
            throw CryptoError("Decryption failed (bad key or corrupted data).")
        }
        return plainText
    }

    fun encodeBase64(data: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(data)

    fun decodeBase64(dataStr: String): ByteArray =
        java.util.Base64.getDecoder().decode(dataStr)
}
