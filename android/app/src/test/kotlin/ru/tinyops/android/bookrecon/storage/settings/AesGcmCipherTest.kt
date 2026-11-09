package ru.tinyops.android.bookrecon.storage.settings

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AesGcmCipherTest {
    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = newKey()
    private val cipher = AesGcmCipher { key }

    @Test
    fun roundTrip() {
        for (secret in listOf("", "p", "пароль с пробелами и ёлками 🎄", "x".repeat(10_000))) {
            val sealed = cipher.encrypt(secret.toByteArray())
            assertEquals(secret, String(cipher.decrypt(sealed)))
        }
    }

    @Test
    fun everyEncryptionUsesAFreshIv() {
        val first = cipher.encrypt("secret".toByteArray())
        val second = cipher.encrypt("secret".toByteArray())
        assertFalse(first.contentEquals(second))
        assertArrayEquals(cipher.decrypt(first), cipher.decrypt(second))
    }

    @Test
    fun ciphertextDoesNotContainThePlainText() {
        val sealed = cipher.encrypt("hunter2hunter2".toByteArray())
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("hunter2"))
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val sealed = cipher.encrypt("secret".toByteArray())
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertThrows<GeneralSecurityException> { cipher.decrypt(sealed) }
    }

    @Test
    fun anotherKeyCannotDecrypt() {
        val sealed = cipher.encrypt("secret".toByteArray())
        val other = newKey()
        assertThrows<GeneralSecurityException> { AesGcmCipher { other }.decrypt(sealed) }
    }

    @Test
    fun malformedInputIsRejected() {
        assertThrows<GeneralSecurityException> { cipher.decrypt(ByteArray(0)) }
        assertThrows<GeneralSecurityException> { cipher.decrypt(byteArrayOf(1, 2, 3)) }
        val sealed = cipher.encrypt("secret".toByteArray())
        sealed[0] = 99
        assertThrows<GeneralSecurityException> { cipher.decrypt(sealed) }
    }
}
