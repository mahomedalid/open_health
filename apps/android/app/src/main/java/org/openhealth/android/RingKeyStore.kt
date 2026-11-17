package org.openhealth.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A ring the app has paired with, plus the auth key that unlocks it. */
internal data class RingCredential(val serial: String, val address: String, val keyHex: String) {
    override fun toString() = "RingCredential(serial=$serial, address=$address, key=<redacted>)"
}

internal class CredentialException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Encrypts key material at rest. Production uses the Android Keystore. */
internal interface KeyCipher {
    fun encrypt(plain: ByteArray): Sealed
    fun decrypt(sealed: Sealed): ByteArray

    class Sealed(val iv: ByteArray, val data: ByteArray)
}

/**
 * Stores the ring auth key. The ring never reveals its key, so losing this key
 * means factory-resetting the ring again. Two slots enforce the PRD's ordering:
 *
 * - `pending`: written durably *before* a key is sent to the ring, so a crash at any
 *   later point leaves a recoverable copy of whatever the ring may now hold.
 * - `active`: written only after the ring authenticated with that key.
 *
 * Unreadable files are reported, never overwritten: they may be the only copy.
 */
internal class RingKeyStore(
    private val directory: File,
    private val cipher: KeyCipher,
    private val random: SecureRandom = SecureRandom(),
) {
    private val pendingFile = File(directory, "ring-key-pending")
    private val activeFile = File(directory, "ring-key-active")

    @Synchronized
    fun active(): RingCredential? {
        val fields = read(activeFile) ?: return null
        val serial = fields["serial"] ?: throw CredentialException("Saved ring record is missing its serial")
        val address = fields["address"] ?: throw CredentialException("Saved ring record is missing its address")
        return RingCredential(serial, address, unseal(fields))
    }

    @Synchronized
    fun pendingKey(): String? = read(pendingFile)?.let(::unseal)

    /** Returns the unfinished pairing key, or durably saves a new one first. */
    @Synchronized
    fun getOrCreatePendingKey(): String {
        pendingKey()?.let { return it }
        val key = ByteArray(16).also(random::nextBytes)
        write(pendingFile, seal(key))
        return key.toHex()
    }

    /** Records [keyHex] as the active credential once the ring authenticated with it. */
    @Synchronized
    fun promote(keyHex: String, serial: String, address: String) {
        val key = keyHex.hexToBytes()
            ?: throw CredentialException("Refusing to save a malformed ring key")
        write(activeFile, seal(key) + mapOf("serial" to serial, "address" to address))
        if (pendingKey() == keyHex) pendingFile.delete()
    }

    private fun seal(key: ByteArray): Map<String, String> {
        val sealed = cipher.encrypt(key)
        val encoder = Base64.getEncoder()
        return mapOf("iv" to encoder.encodeToString(sealed.iv), "data" to encoder.encodeToString(sealed.data))
    }

    private fun unseal(fields: Map<String, String>): String {
        try {
            val decoder = Base64.getDecoder()
            val iv = decoder.decode(fields["iv"] ?: throw CredentialException("Saved key is incomplete"))
            val data = decoder.decode(fields["data"] ?: throw CredentialException("Saved key is incomplete"))
            val key = cipher.decrypt(KeyCipher.Sealed(iv, data))
            if (key.size != 16) throw CredentialException("Saved key has the wrong length")
            return key.toHex()
        } catch (e: CredentialException) {
            throw e
        } catch (e: Exception) {
            throw CredentialException("Saved ring key could not be decrypted on this phone", e)
        }
    }

    private fun read(file: File): Map<String, String>? {
        if (!file.exists()) return null
        return try {
            decodeRecord(file.readText())
        } catch (e: IOException) {
            throw CredentialException("Saved ring key could not be read", e)
        }
    }

    private fun write(file: File, fields: Map<String, String>) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw CredentialException("Could not create the key directory")
        }
        val temp = File(directory, "${file.name}.tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(encodeRecord(fields).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            temp.delete()
            throw CredentialException("Could not save the ring key", e)
        }
    }
}

internal fun encodeRecord(fields: Map<String, String>): String {
    require(fields.all { (k, v) -> '=' !in k && '\n' !in k && '\n' !in v }) { "invalid record field" }
    return fields.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" }
}

internal fun decodeRecord(text: String): Map<String, String> =
    text.lineSequence().filter { it.isNotBlank() }.associate { line ->
        val split = line.indexOf('=')
        if (split <= 0) throw CredentialException("Saved ring key file is corrupt")
        line.substring(0, split) to line.substring(split + 1)
    }

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray? {
    if (length != 32 || any { it !in "0123456789abcdefABCDEF" }) return null
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/** AES-GCM key generated inside, and never exported from, the Android Keystore. */
internal class AndroidKeystoreCipher(private val alias: String = "open-health-ring-key-wrap") : KeyCipher {
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): KeyCipher.Sealed {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return KeyCipher.Sealed(cipher.iv, cipher.doFinal(plain))
    }

    override fun decrypt(sealed: KeyCipher.Sealed): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, sealed.iv))
        return cipher.doFinal(sealed.data)
    }
}
