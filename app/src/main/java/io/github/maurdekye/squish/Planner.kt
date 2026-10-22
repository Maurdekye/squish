package io.github.maurdekye.squish

import kotlin.math.min
import kotlin.math.roundToInt

sealed interface Plan {
    /** Already fits: hand over the original. */
    data object Keep : Plan

    /** Too big, but there's no way to shrink it. It goes as is. */
    data class CantShrink(val reason: Reason) : Plan

    data class Image(val maxBytes: Long) : Plan

    data class Video(
        /** Short side of the output, in pixels. */
        val shortSide: Int,
        /** Output frame rate when it's lowered, or null to keep the source's. */
        val frameRate: Float?,
        val videoBitrate: Int,
        val audioBitrate: Int,
        /** Output must not be bigger than this. */
        val maxBytes: Long,
        /** True when the result will look noticeably bad, so the user gets a warning first. */
        val poor: Boolean,
    ) : Plan

    enum class Reason { GIF, NOT_MEDIA, UNREADABLE, TOO_LONG }
}

object Planner {
    /** Bits per pixel per frame that H.264 needs for a decent picture of phone camera footage. */
    private const val GOOD_BPP = 0.075

    /** The least we aim for even when the source itself was encoded very leanly. */
    private const val LEAN_BPP = 0.045

    /** Below this, the picture falls apart into blocks whatever the resolution. */
    private const val POOR_BPP = 0.04

    private const val MAX_SHORT_SIDE = 1080
    private val SHORT_SIDES = intArrayOf(1080, 720, 540, 480, 360, 240, 144)
    private const val MIN_VIDEO_BITRATE = 60_000
    private const val MAX_VIDEO_BITRATE = 16_000_000

    fun plan(info: MediaInfo, limitBytes: Long): Plan {
        if (info.size in 0..limitBytes) return Plan.Keep
        return when (info.kind) {
            Kind.IMAGE ->
                if (info.mime == "image/gif") Plan.CantShrink(Plan.Reason.GIF)
                else Plan.Image(maxBytes = (limitBytes * 0.98).toLong())
            Kind.VIDEO -> planVideo(info, limitBytes)
            Kind.OTHER -> Plan.CantShrink(Plan.Reason.NOT_MEDIA)
        }
    }

    private fun planVideo(info: MediaInfo, limitBytes: Long): Plan {
        if (info.durationMs <= 0 || info.width <= 0 || info.height <= 0) {
            return Plan.CantShrink(Plan.Reason.UNREADABLE)
        }
        // Aim a bit low: the MP4 container adds some overhead and encoders overshoot a little.
        val targetBytes = (limitBytes * 0.93).toLong() - 64_000
        val seconds = info.durationMs / 1000.0
        val totalBitrate = targetBytes * 8 / seconds
        val audio = if (info.hasAudio) (totalBitrate * 0.1).toInt().coerceIn(48_000, 128_000) else 0
        var video = (totalBitrate - audio).toInt().coerceAtMost(MAX_VIDEO_BITRATE)
        if (info.bitrate > 0) video = min(video, info.bitrate)
        if (video < MIN_VIDEO_BITRATE) return Plan.CantShrink(Plan.Reason.TOO_LONG)

        val sourceFps = info.frameRate.takeIf { it in 1f..480f } ?: 30f
        val fps = if (sourceFps > 32f) 30f else sourceFps
        val sourceShort = min(info.width, info.height)
        val top = min(sourceShort, MAX_SHORT_SIDE)
        val candidates = (listOf(top) + SHORT_SIDES.filter { it < top }).distinct()

        fun bpp(shortSide: Int): Double {
            val (w, h) = scaledSize(info.width, info.height, shortSide)
            return video / (w.toDouble() * h * fps)
        }

        // A source that already looks fine on few bits (simple content, or an efficient encoder, as
        // with downloaded clips) keeps more resolution than fresh camera footage would.
        val sourceBpp = if (info.bitrate > 0) info.bitrate / (info.width.toDouble() * info.height * sourceFps) else GOOD_BPP
        val neededBpp = (sourceBpp * 1.3).coerceIn(LEAN_BPP, GOOD_BPP)
        val shortSide = candidates.firstOrNull { bpp(it) >= neededBpp } ?: candidates.last()
        val poor = (shortSide < 480 && shortSide < sourceShort) || bpp(shortSide) < POOR_BPP
        return Plan.Video(
            shortSide = shortSide,
            frameRate = if (fps < sourceFps) fps else null,
            videoBitrate = video,
            audioBitrate = audio,
            maxBytes = (limitBytes * 0.985).toLong(),
            poor = poor,
        )
    }

    /** One resolution step down from [plan], for when the encoder can't hit the size. */
    fun stepDown(plan: Plan.Video): Plan.Video {
        val next = SHORT_SIDES.firstOrNull { it < plan.shortSide } ?: return plan
        return plan.copy(shortSide = next)
    }

    /** (w, h) scaled so the short side is [shortSide], keeping the aspect ratio and even sizes. */
    fun scaledSize(w: Int, h: Int, shortSide: Int): Pair<Int, Int> {
        val scale = shortSide.toDouble() / min(w, h)
        fun even(x: Double) = ((x / 2).roundToInt() * 2).coerceAtLeast(2)
        return even(w * scale) to even(h * scale)
    }
}
