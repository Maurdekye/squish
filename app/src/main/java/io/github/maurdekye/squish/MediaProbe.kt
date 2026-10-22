package io.github.maurdekye.squish

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap

enum class Kind { IMAGE, VIDEO, OTHER }

/** What we know about a picked file. Width and height are as displayed, with rotation applied. */
data class MediaInfo(
    val uri: Uri,
    val name: String,
    val mime: String,
    /** Bytes, or -1 when the source doesn't say. */
    val size: Long,
    val kind: Kind,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    val frameRate: Float = 0f,
    val bitrate: Int = 0,
    val hasAudio: Boolean = false,
)

object MediaProbe {
    private const val TAG = "Squish"

    fun probe(context: Context, uri: Uri): MediaInfo {
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !c.isNull(nameIndex)) name = c.getString(nameIndex)
                    val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !c.isNull(sizeIndex)) size = c.getLong(sizeIndex)
                }
            }
        }.onFailure { Log.w(TAG, "query failed for $uri", it) }
        if (size < 0) {
            size = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L
        }
        val fileName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "file"
        val mime = resolver.getType(uri) ?: mimeFromName(fileName) ?: "application/octet-stream"
        val kind = when {
            mime.startsWith("image/") -> Kind.IMAGE
            mime.startsWith("video/") -> Kind.VIDEO
            else -> Kind.OTHER
        }
        val base = MediaInfo(uri, fileName, mime, size, kind)
        return when (kind) {
            Kind.VIDEO -> probeVideo(context, base)
            Kind.IMAGE -> probeImage(context, base)
            Kind.OTHER -> base
        }.also { Log.i(TAG, "probe: $it") }
    }

    private fun probeVideo(context: Context, base: MediaInfo): MediaInfo {
        val retriever = MediaMetadataRetriever()
        var info = base
        try {
            retriever.setDataSource(context, base.uri)
            fun int(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
            val w = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotated = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180 != 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frames = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            info = info.copy(
                width = if (rotated) h else w,
                height = if (rotated) w else h,
                durationMs = duration,
                bitrate = int(MediaMetadataRetriever.METADATA_KEY_BITRATE),
                hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
                frameRate = if (frames > 0 && duration > 0) frames * 1000f / duration else 0f,
            )
        } catch (e: Exception) {
            Log.w(TAG, "metadata failed for ${base.uri}", e)
        } finally {
            runCatching { retriever.release() }
        }
        // The container's frame rate is more reliable than counting frames, when it's there.
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, base.uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    val fps = format.getNumber(MediaFormat.KEY_FRAME_RATE)?.toFloat() ?: 0f
                    if (fps in 1f..480f) info = info.copy(frameRate = fps)
                }
                if (mime.startsWith("audio/")) info = info.copy(hasAudio = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractor failed for ${base.uri}", e)
        } finally {
            extractor.release()
        }
        return info
    }

    private fun probeImage(context: Context, base: MediaInfo): MediaInfo {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(base.uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
        return base.copy(width = options.outWidth.coerceAtLeast(0), height = options.outHeight.coerceAtLeast(0))
    }

    private fun mimeFromName(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return if (ext.isEmpty()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }
}
