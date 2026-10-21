package dev.daybook.app.capture

import dev.daybook.core.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class SpeechTransferTest {
    private lateinit var server: MockWebServer
    private lateinit var root: File
    @Before fun setup() { server = MockWebServer(); server.start(); root = Files.createTempDirectory("speech-transfer").toFile() }
    @After fun cleanup() { server.shutdown(); root.deleteRecursively() }
    private val bytes = "verified speech model fixture".toByteArray()
    private fun asset(sha: String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }) =
        SpeechAsset("model.bin", server.url("/model").toString(), bytes.size.toLong(), sha)
    private suspend fun fetch(a: SpeechAsset = asset()) = SpeechAssetTransfer(OkHttpClient()).fetch(a, File(root, "model.bin"), File(root, "model.bin.partial")) {}
    @Test fun resumesVerifiedPartialAndReportsCompletion() = runBlocking {
        File(root, "model.bin.partial").writeBytes(bytes.take(7).toByteArray())
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 7-${bytes.lastIndex}/${bytes.size}").setBody(Buffer().write(bytes.copyOfRange(7, bytes.size))))
        fetch()
        assertEquals("bytes=7-", server.takeRequest().getHeader("Range"))
        assertArrayEquals(bytes, File(root, "model.bin").readBytes())
        assertFalse(File(root, "model.bin.partial").exists())
    }
    @Test fun serverIgnoringRangeRestartsWithoutDuplicatingBytes() = runBlocking {
        File(root, "model.bin.partial").writeBytes(bytes.take(7).toByteArray())
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        fetch()
        assertArrayEquals(bytes, File(root, "model.bin").readBytes())
    }
    @Test fun rejectsCorruptionWithoutReplacingPreviouslySavedFile() = runBlocking {
        val target = File(root, "model.bin").apply { writeText("previous file") }
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        try { fetch(asset("0".repeat(64))); fail("Checksum must be checked") } catch (_: IllegalStateException) {}
        assertEquals("previous file", target.readText())
        assertFalse(File(root, "model.bin.partial").exists())
    }
    @Test fun rejectsInvalidContentRangeAndExcessBytes() = runBlocking {
        File(root, "model.bin.partial").writeText("partial")
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-99/100").setBody("wrong"))
        try { fetch(); fail("Range must match") } catch (_: IllegalStateException) {}
        File(root, "model.bin.partial").delete()
        server.enqueue(MockResponse().setBody(Buffer().write(bytes + byteArrayOf(1))))
        try { fetch(); fail("Size must be bounded") } catch (_: IllegalStateException) {}
        assertFalse(File(root, "model.bin").exists())
    }
    @Test fun cancellationStopsBlockedDownloadAndKeepsResumableData() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = launch(Dispatchers.IO) { fetch() }
        assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS))
        withTimeout(5000) { pending.cancelAndJoin() }
        assertTrue(pending.isCancelled)
        assertFalse(File(root, "model.bin").exists())
    }
}
