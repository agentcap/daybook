package dev.daybook.core
import java.io.*
import org.junit.Assert.*
import org.junit.Test

class ArchiveCryptoTest {
    private val password = "long test passphrase".toCharArray()
    @Test fun encryptedArchiveRoundTripsAndIsRandomized() {
        val payload = ByteArray(180_000) { (it % 251).toByte() }
        fun encrypt() = ByteArrayOutputStream().also { ArchiveCrypto.encrypt(it, password).use { s -> s.write(payload) } }.toByteArray()
        val a = encrypt(); val b = encrypt()
        assertFalse(a.contentEquals(b))
        assertArrayEquals(payload, ArchiveCrypto.decrypt(a.inputStream(), password).use { it.readBytes() })
    }
    @Test fun wrongPasswordTamperingAndTruncationFail() {
        val a = ByteArrayOutputStream().also { ArchiveCrypto.encrypt(it, password).use { s -> s.write("private thoughts".toByteArray()) } }.toByteArray()
        assertThrows(Exception::class.java) { ArchiveCrypto.decrypt(a.inputStream(), "wrong password".toCharArray()).use { it.readBytes() } }
        val b = a.clone(); b[b.lastIndex] = (b.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { ArchiveCrypto.decrypt(b.inputStream(), password).use { it.readBytes() } }
        assertThrows(Exception::class.java) { ArchiveCrypto.decrypt(a.copyOf(a.size - 10).inputStream(), password).use { it.readBytes() } }
    }
}
