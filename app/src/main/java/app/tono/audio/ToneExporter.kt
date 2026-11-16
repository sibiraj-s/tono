package app.tono.audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ExportSpec(
    val startMs: Long,
    val endMs: Long,
    val fadeInMs: Long,
    val fadeOutMs: Long,
    val gain: Float,
)

/** Cuts [ExportSpec.startMs]..[ExportSpec.endMs] out of a song and writes an AAC .m4a. */
@OptIn(UnstableApi::class)
class ToneExporter(private val context: Context) {

    suspend fun export(input: File, spec: ExportSpec, output: File): File = withContext(Dispatchers.Main) {
        output.parentFile?.mkdirs()
        output.delete()

        val clipped = MediaItem.Builder()
            .setUri(Uri.fromFile(input))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(spec.startMs)
                    .setEndPositionMs(spec.endMs)
                    .build(),
            )
            .build()

        val processor = FadeGainProcessor(
            clipDurationMs = spec.endMs - spec.startMs,
            fadeInMs = spec.fadeInMs,
            fadeOutMs = spec.fadeOutMs,
            gain = spec.gain,
        )
        val edited = EditedMediaItem.Builder(clipped)
            .setRemoveVideo(true)
            .setEffects(Effects(listOf(processor), emptyList()))
            .build()

        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(output)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            transformer.start(edited, output.absolutePath)
            // Transformer must be touched only from the thread it was built on (main).
            cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel() } }
        }
    }
}
