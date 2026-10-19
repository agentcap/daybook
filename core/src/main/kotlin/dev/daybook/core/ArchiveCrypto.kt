package dev.daybook.core

import java.io.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.*

/** Version 1: PBKDF2-HMAC-SHA256, 600k rounds, random 128-bit salt; independently authenticated 64 KiB AES-GCM segments.
 * A mandatory authenticated empty terminal segment detects truncation. Segment counters are authenticated and never reused.
 * Decrypting callers must consume through EOF before accepting an archive.
 */
object ArchiveCrypto {
    private val magic = "DAYBOOK1".toByteArray(Charsets.US_ASCII)
    private const val chunk = 65536
    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, 600_000, 256)
        val bytes = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        return try { SecretKeySpec(bytes, "AES") } finally { bytes.fill(0) }
    }
    private fun crypt(mode: Int, key: SecretKeySpec, prefix: ByteArray, counter: Int, bytes: ByteArray): ByteArray {
        require(counter >= 0) { "Backup segment limit exceeded." }
        val sequence = ByteBuffer.allocate(4).putInt(counter).array()
        val iv = prefix + sequence
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, key, GCMParameterSpec(128, iv)); updateAAD(magic); updateAAD(sequence); doFinal(bytes)
        }
    }
    fun encrypt(output: OutputStream, password: CharArray): OutputStream {
        require(password.size >= 10) { "Use a backup passphrase of at least 10 characters." }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val prefix = ByteArray(8).also(SecureRandom()::nextBytes)
        val out = DataOutputStream(output)
        out.write(magic); out.write(salt); out.write(prefix)
        val key = key(password, salt)
        return object : OutputStream() {
            val buffer = ByteArray(chunk); var count = 0; var sequence = 0; var closed = false
            fun segment(bytes: ByteArray) {
                val encrypted = crypt(Cipher.ENCRYPT_MODE, key, prefix, sequence++, bytes)
                out.writeInt(encrypted.size); out.write(encrypted)
            }
            override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }
            override fun write(b: ByteArray, off: Int, len: Int) {
                check(!closed); require(off >= 0 && len >= 0 && off <= b.size - len)
                var pos = off; var remaining = len
                while (remaining > 0) {
                    val n = minOf(chunk - count, remaining); b.copyInto(buffer, count, pos, pos + n)
                    count += n; pos += n; remaining -= n
                    if (count == chunk) { segment(buffer); count = 0 }
                }
            }
            override fun flush() { out.flush() }
            override fun close() {
                if (closed) return
                try { if (count > 0) segment(buffer.copyOf(count)); segment(byteArrayOf()); out.flush() }
                finally { closed = true; buffer.fill(0); out.close() }
            }
        }
    }
    fun decrypt(input: InputStream, password: CharArray): InputStream {
        val head = DataInputStream(input)
        val found = ByteArray(8); head.readFully(found)
        require(found.contentEquals(magic)) { "This is not a supported Daybook backup." }
        val salt = ByteArray(16); head.readFully(salt)
        val prefix = ByteArray(8); head.readFully(prefix)
        val key = key(password, salt)
        return object : InputStream() {
            var buffer = ByteArray(0); var offset = 0; var sequence = 0; var ended = false
            private fun next(): Boolean {
                if (ended) return false
                val size = head.readInt()
                require(size in 16..(chunk + 16)) { "Invalid encrypted backup segment." }
                val encrypted = ByteArray(size); head.readFully(encrypted)
                buffer = crypt(Cipher.DECRYPT_MODE, key, prefix, sequence++, encrypted); offset = 0
                if (buffer.isEmpty()) { require(head.read() == -1) { "Unexpected data after backup." }; ended = true; return false }
                return true
            }
            override fun read(): Int {
                if (offset == buffer.size && !next()) return -1
                return buffer[offset++].toInt() and 255
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                require(off >= 0 && len >= 0 && off <= b.size - len)
                if (len == 0) return 0
                if (offset == buffer.size && !next()) return -1
                val n = minOf(len, buffer.size - offset); buffer.copyInto(b, off, offset, offset + n); offset += n
                return n
            }
            override fun close() { buffer.fill(0); head.close() }
        }
    }
}
