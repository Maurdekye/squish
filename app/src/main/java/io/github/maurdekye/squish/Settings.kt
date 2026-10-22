package io.github.maurdekye.squish

import android.content.Context
import android.media.MediaCodecList
import androidx.media3.common.MimeTypes

enum class VideoCodec(val mimeType: String, val label: String) {
    /** Plays everywhere Discord shows video. */
    H264(MimeTypes.VIDEO_H264, "H.264"),

    /** Sharper at the same size, but not every device plays it inline. */
    H265(MimeTypes.VIDEO_H265, "H.265");

    /** How many bits this codec needs for the picture H.264 gives, roughly. */
    val bitsFactor: Double get() = if (this == H265) 0.7 else 1.0

    companion object {
        fun hasEncoder(codec: VideoCodec): Boolean =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(codec.mimeType, ignoreCase = true) }
            }
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var limitMb: Int
        get() = prefs.getInt(KEY_LIMIT, DEFAULT_LIMIT_MB)
        set(value) = prefs.edit().putInt(KEY_LIMIT, value.coerceIn(MIN_LIMIT_MB, MAX_LIMIT_MB)).apply()

    /** Decimal megabytes: a little under Discord's limit whether it counts MB or MiB. */
    val limitBytes: Long get() = limitMb * 1_000_000L

    var videoCodec: VideoCodec
        get() = runCatching { VideoCodec.valueOf(prefs.getString(KEY_CODEC, null) ?: "") }.getOrDefault(VideoCodec.H264)
        set(value) = prefs.edit().putString(KEY_CODEC, value.name).apply()

    companion object {
        private const val KEY_LIMIT = "limit_mb"
        private const val KEY_CODEC = "video_codec"

        /** Discord's free upload limit, checked 2026-10-22 (raised from 10 MB to 20 MB on 2026-08-13). */
        const val DEFAULT_LIMIT_MB = 20
        const val NITRO_BASIC_MB = 50
        const val NITRO_MB = 500
        const val MIN_LIMIT_MB = 1
        const val MAX_LIMIT_MB = 10_000
    }
}
