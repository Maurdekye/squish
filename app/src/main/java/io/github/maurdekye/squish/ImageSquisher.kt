package io.github.maurdekye.squish

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.sqrt

object ImageSquisher {
    private const val TAG = "Squish"

    /** Pictures bigger than this are scaled down while decoding; Discord never shows them that big. */
    private const val MAX_PIXELS = 24_000_000L
    private val QUALITIES = intArrayOf(92, 85, 78, 70)
    private const val MAX_ROUNDS = 6

    /** Re-encodes the picture into [out] until it fits [maxBytes]. Returns the MIME type written. */
    suspend fun squish(context: Context, uri: Uri, maxBytes: Long, out: File, onProgress: (Float) -> Unit): String =
        withContext(Dispatchers.Default) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            // ImageDecoder applies the EXIF rotation, so the pixels come out the right way up.
            var bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val pixels = w.toLong() * h
                if (pixels > MAX_PIXELS) {
                    val s = sqrt(MAX_PIXELS.toDouble() / pixels)
                    decoder.setTargetSize(max(1, (w * s).toInt()), max(1, (h * s).toInt()))
                }
            }
            val format = if (bitmap.hasAlpha()) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.JPEG
            val mime = if (format == Bitmap.CompressFormat.JPEG) "image/jpeg" else "image/webp"
            val steps = MAX_ROUNDS * QUALITIES.size
            var step = 0
            try {
                repeat(MAX_ROUNDS) {
                    for (quality in QUALITIES) {
                        coroutineContext.ensureActive()
                        out.outputStream().buffered().use { bitmap.compress(format, quality, it) }
                        Log.i(TAG, "image ${bitmap.width}x${bitmap.height} q$quality -> ${out.length()} (max $maxBytes)")
                        onProgress(++step / steps.toFloat())
                        if (out.length() <= maxBytes) return@withContext mime
                    }
                    // Still too big at the lowest quality: shrink the picture and go again.
                    val factor = sqrt(maxBytes.toDouble() / out.length()) * 0.9
                    val smaller = Bitmap.createScaledBitmap(
                        bitmap,
                        max(1, (bitmap.width * factor).toInt()),
                        max(1, (bitmap.height * factor).toInt()),
                        true,
                    )
                    if (smaller !== bitmap) bitmap.recycle()
                    bitmap = smaller
                }
            } finally {
                bitmap.recycle()
            }
            throw IOException("couldn't get the picture under the limit")
        }
}
