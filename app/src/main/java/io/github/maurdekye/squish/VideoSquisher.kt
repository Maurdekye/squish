package io.github.maurdekye.squish

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Hardware transcode to H.264 (or H.265 when chosen) + AAC in MP4. H.264 plays in every Discord client. */
@OptIn(UnstableApi::class)
object VideoSquisher {
    private const val TAG = "Squish"
    private const val MAX_ATTEMPTS = 3

    /**
     * Writes a copy of the video into [out] that is at most [Plan.Video.maxBytes]. If the encoder
     * overshoots, it tries again with a lower bitrate, and on the last try one resolution step lower.
     */
    suspend fun squish(
        context: Context,
        info: MediaInfo,
        plan: Plan.Video,
        out: File,
        onProgress: (Float) -> Unit,
        onRetry: () -> Unit,
    ): String {
        var current = plan
        for (attempt in 1..MAX_ATTEMPTS) {
            val result = export(context, info, current, out, onProgress)
            val size = out.length()
            Log.i(
                TAG,
                "video attempt $attempt: $current -> $size bytes, ${result.width}x${result.height}, " +
                    "video ${result.averageVideoBitrate} bps by ${result.videoEncoderName}",
            )
            if (size in 1..current.maxBytes) return MimeTypes.VIDEO_MP4
            if (attempt == MAX_ATTEMPTS) break
            onRetry()
            val lower = current.copy(videoBitrate = (current.videoBitrate * current.maxBytes.toDouble() / size * 0.9).toInt())
            current = if (attempt == MAX_ATTEMPTS - 1) Planner.stepDown(lower) else lower
        }
        throw IOException("the video encoder kept going over the size limit")
    }

    private suspend fun export(
        context: Context,
        info: MediaInfo,
        plan: Plan.Video,
        out: File,
        onProgress: (Float) -> Unit,
    ): ExportResult = withContext(Dispatchers.Main) {
        out.delete()
        val app = context.applicationContext
        val done = CompletableDeferred<ExportResult>()
        val encoders = DefaultEncoderFactory.Builder(app)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(plan.videoBitrate).build())
            .apply {
                if (plan.audioBitrate > 0) {
                    setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(plan.audioBitrate).build())
                }
            }
            .setEnableFallback(true)
            .build()
        val transformer = Transformer.Builder(app)
            .setVideoMimeType(plan.codec.mimeType)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoders)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    done.complete(exportResult)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    done.completeExceptionally(exportException)
                }
            })
            .build()
        val effects = buildList<Effect> {
            plan.frameRate?.let { add(FrameDropEffect.createDefaultFrameDropEffect(it)) }
            add(Presentation.createForShortSide(plan.shortSide))
        }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(info.uri))
            .setEffects(Effects(emptyList(), effects))
            .build()
        val sequence = if (info.hasAudio) {
            EditedMediaItemSequence.withAudioAndVideoFrom(listOf(item))
        } else {
            EditedMediaItemSequence.withVideoFrom(listOf(item))
        }
        // The output is SDR, so HDR phone videos get tone-mapped instead of washed out.
        val composition = Composition.Builder(sequence)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
        transformer.start(composition, out.absolutePath)
        val poller = launch {
            val holder = ProgressHolder()
            while (true) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress / 100f)
                }
                delay(250)
            }
        }
        try {
            done.await()
        } catch (e: CancellationException) {
            transformer.cancel()
            out.delete()
            throw e
        } finally {
            poller.cancel()
        }
    }
}
