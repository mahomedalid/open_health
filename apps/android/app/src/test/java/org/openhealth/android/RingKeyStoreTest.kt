package org.openhealth.android

import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RingKeyStoreTest {
    /** Reversible stand-in for the Android Keystore; flips bits so files never hold plaintext. */
    private class XorCipher : KeyCipher {
        var broken = false
        override fun encrypt(plain: ByteArray) =
            KeyCipher.Sealed(byteArrayOf(7), ByteArray(plain.size) { (plain[it].toInt() xor 0x5a).toByte() })

        override fun decrypt(sealed: KeyCipher.Sealed): ByteArray {
            check(!broken) { "keystore key lost" }
            return ByteArray(sealed.data.size) { (sealed.data[it].toInt() xor 0x5a).toByte() }
        }
    }

    private val dir: File = Files.createTempDirectory("ring-keys").toFile()
    private val cipher = XorCipher()
    private fun store() = RingKeyStore(dir, cipher, SecureRandom())

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun pendingKeyIsDurableAndReusedUntilPromoted() {
        val key = store().getOrCreatePendingKey()

        assertEquals(32, key.length)
        assertEquals(key, store().getOrCreatePendingKey())
        assertNull(store().active())
        assertFalse(dir.listFiles()!!.any { it.readText().contains(key) })
    }

    @Test
    fun promotionActivatesTheKeyAndClearsThePendingSlot() {
        val key = store().getOrCreatePendingKey()

        store().promote(key, "SERIAL1", "AA:BB:CC:DD:EE:FF")

        assertEquals(RingCredential("SERIAL1", "AA:BB:CC:DD:EE:FF", key), store().active())
        assertNull(store().pendingKey())
    }

    @Test
    fun promotingADifferentKeyKeepsTheUnfinishedPendingKey() {
        val pending = store().getOrCreatePendingKey()
        val other = "00112233445566778899aabbccddeeff"

        store().promote(other, "SERIAL1", "AA:BB:CC:DD:EE:FF")

        assertEquals(pending, store().pendingKey())
        assertEquals(other, store().active()!!.keyHex)
    }

    @Test
    fun unreadableKeysAreReportedAndNeverReplaced() {
        val key = store().getOrCreatePendingKey()
        val saved = File(dir, "ring-key-pending").readText()
        cipher.broken = true

        assertThrows(CredentialException::class.java) { store().getOrCreatePendingKey() }
        assertEquals(saved, File(dir, "ring-key-pending").readText())
        cipher.broken = false
        assertEquals(key, store().pendingKey())
    }

    @Test
    fun corruptRecordsAreReported() {
        dir.mkdirs()
        File(dir, "ring-key-active").writeText("garbage")
        assertThrows(CredentialException::class.java) { store().active() }
    }

    @Test
    fun malformedKeysAreNotSaved() {
        assertThrows(CredentialException::class.java) { store().promote("abc", "S", "A") }
        assertNull(store().active())
    }

    @Test
    fun freshKeysDiffer() {
        val first = store().getOrCreatePendingKey()
        File(dir, "ring-key-pending").delete()
        assertNotEquals(first, store().getOrCreatePendingKey())
    }

    @Test
    fun credentialToStringRedactsTheKey() {
        val text = RingCredential("S", "A", "00112233445566778899aabbccddeeff").toString()
        assertFalse(text.contains("0011"))
        assertTrue(text.contains("redacted"))
    }

    @Test
    fun hexRoundTripsAndRejectsBadInput() {
        val bytes = ByteArray(16) { (it * 17 - 128).toByte() }
        assertEquals(bytes.toList(), bytes.toHex().hexToBytes()!!.toList())
        assertNull("xyz".hexToBytes())
        assertNull("zz112233445566778899aabbccddeeff".hexToBytes())
    }

    @Test
    fun backupKeysAcceptCommonFormattingAndRoundTripThroughDisplay() {
        val key = "00112233445566778899aabbccddeeff"
        val shown = formatKeyForDisplay(key)

        assertEquals("0011 2233 4455 6677 8899 aabb ccdd eeff", shown)
        assertEquals(key, normalizeKeyInput(shown))
        assertEquals(key, normalizeKeyInput(" 0011-2233-4455-6677-8899-AABB-CCDD-EEFF\n"))
        assertEquals(key, normalizeKeyInput("00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff"))
        assertNull(normalizeKeyInput("0011 2233"))
        assertNull(normalizeKeyInput("0011 2233 4455 6677 8899 aabb ccdd eefg"))
    }
}
