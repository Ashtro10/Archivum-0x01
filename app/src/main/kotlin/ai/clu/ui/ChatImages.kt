package ai.clu.ui

import ai.clu.core.conversation.Attachment
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import java.io.File

/**
 * Images attached in the chat: copied into CLU's private storage (scaled to at most 1600 px, JPEG), so the conversation
 * keeps them even if the original is deleted; thumbnails decoded at the size shown and cached.
 */
object ChatImages {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }

    private fun open(ctx: Context, uri: Uri) = if (uri.scheme == "file") File(uri.path!!).inputStream() else ctx.contentResolver.openInputStream(uri)!!

    fun importImage(ctx: Context, uri: Uri): Attachment {
        val name = if (uri.scheme == "file") uri.lastPathSegment ?: "image.jpg" else runCatching { ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }
            .getOrNull() ?: "image.jpg"
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(ctx, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not an image" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        val bmp = open(ctx, uri).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
        val scale = minOf(1f, 1600f / maxOf(bmp.width, bmp.height))
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val dir = File(ctx.filesDir, "attachments").apply { mkdirs() }
        val f = File(dir, java.util.UUID.randomUUID().toString() + ".jpg")
        f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return Attachment(kind = "image", uri = f.absolutePath, name = name, mime = "image/jpeg")
    }

    /** A thumbnail about [px] on its longer side, or null when the file is gone. */
    fun thumb(path: String, px: Int): Bitmap? {
        val key = "$path@$px"
        cache.get(key)?.let { return it }
        val f = File(path); if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; f.inputStream().use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= px) s *= 2
        return f.inputStream().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }?.also { cache.put(key, it) }
    }
}
