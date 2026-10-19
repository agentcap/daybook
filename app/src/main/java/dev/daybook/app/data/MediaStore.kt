package dev.daybook.app.data

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dev.daybook.core.*
import java.io.*
import java.security.MessageDigest

class MediaStore(private val context: Context) {
    val directory = File(context.filesDir, "media").apply { mkdirs() }
    fun file(path: String): File {
        require(path.matches(Regex("media/[a-zA-Z0-9_-]+\\.(wav|jpg|jpeg|png|webp|heic)"))) { "Invalid media path." }
        val f = File(context.filesDir, path)
        require(f.canonicalFile.parentFile == directory.canonicalFile)
        return f
    }
    fun newPath(extension: String) = "media/${newId()}.$extension"
    suspend fun importPhoto(uri: Uri, day: String, repo: JournalRepository) {
        val path = newPath("jpg"); val target = file(path)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out -> copyLimited(input, out, 100L * 1024 * 1024) }
            } ?: error("Could not read this photo.")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This image format cannot be opened. Choose a JPEG, PNG, WebP, or HEIC photo." }
            val entry = Entry(day = day, kind = "photo")
            val attachment = Attachment(entryId = entry.id, path = path, kind = "photo", bytes = target.length(), digest = digest(target))
            repo.change { it.copy(entries = it.entries + entry, attachments = it.attachments + attachment) }
        } catch (e: Exception) { target.delete(); throw e }
    }
    fun uploadPhoto(attachment: Attachment): ByteArray {
        val f = file(attachment.path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        var bitmap = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("A journal photo cannot be decoded.")
        val orientation = ExifInterface(f).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
            }
        }
        if (!matrix.isIdentity) {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        if (maxOf(bitmap.width, bitmap.height) > 1280) {
            val factor = 1280.0 / maxOf(bitmap.width, bitmap.height)
            val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).toInt(), (bitmap.height * factor).toInt(), true)
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        return try { ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out); out.toByteArray() } }
            finally { bitmap.recycle() }
    }
    fun usage(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0
    companion object {
        fun copyLimited(input: InputStream, output: OutputStream, limit: Long) {
            val buffer = ByteArray(64 * 1024); var total = 0L
            while (true) {
                val n = input.read(buffer); if (n == -1) break
                total += n; require(total <= limit) { "The selected file is too large." }; output.write(buffer, 0, n)
            }
        }
        fun digest(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val b = ByteArray(65536); while (true) { val n = input.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
