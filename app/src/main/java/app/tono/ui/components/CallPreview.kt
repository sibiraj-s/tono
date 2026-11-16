package app.tono.ui.components

import android.media.AudioManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import app.tono.R
import kotlinx.coroutines.delay

/** Playback that behaves like a real ringtone: ring volume, muted by silent/vibrate mode. */
val RingtoneAudioAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_NOTIFICATION_RINGTONE)
    .setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION)
    .build()

private val Decline = Color(0xFFE5484D)
private val Answer = Color(0xFF30A46C)

/**
 * A pretend incoming-call screen, shown while a tone plays through the ringtone channel.
 * The caller plays and stops the sound; both buttons (and back) just close the preview.
 */
@Composable
fun CallPreview(title: String, artUrl: String?, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Volume buttons change the ring volume while this is open, like during a real call.
        val view = LocalView.current
        DisposableEffect(Unit) {
            (view.parent as? DialogWindowProvider)?.window?.setVolumeControlStream(AudioManager.STREAM_RING)
            onDispose { }
        }
        val context = LocalContext.current
        val audio = remember { context.getSystemService(AudioManager::class.java) }
        var note by remember { mutableStateOf(ringNote(audio)) }
        LaunchedEffect(Unit) { while (true) { note = ringNote(audio); delay(400) } }

        Column(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF231B45), Color(0xFF0D0D0F))))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Text("Incoming call", style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.clip(Pill).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                Text("Preview", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
            }
            Spacer(Modifier.height(56.dp))
            PulsingArt(artUrl)
            Spacer(Modifier.height(40.dp))
            Text(
                title, style = MaterialTheme.typography.headlineMedium, color = Color.White,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text("Ringtone preview", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.weight(1f))
            Text(
                note.text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                color = if (note.muted) Color(0xFFFFC46B) else Color.White.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp),
            )
            Row(Modifier.fillMaxWidth().padding(bottom = 40.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                CallButton("Decline", Decline, rotate = 135f, onClose)
                CallButton("Answer", Answer, rotate = 0f, onClose)
            }
        }
    }
}

@Composable
private fun PulsingArt(artUrl: String?) {
    val t = rememberInfiniteTransition(label = "ring")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Box(contentAlignment = Alignment.Center) {
        // Two rings expanding outward, half a cycle apart.
        listOf(phase, (phase + 0.5f) % 1f).forEach { p ->
            Box(
                Modifier
                    .size(140.dp)
                    .scale(1f + p * 0.55f)
                    .graphicsLayer { alpha = (1f - p) * 0.35f }
                    .clip(CircleShape)
                    .background(Color(0xFFA59BFF)),
            )
        }
        Artwork(artUrl, size = 140.dp, shape = CircleShape, iconSize = 48.dp)
    }
}

@Composable
private fun CallButton(label: String, color: Color, rotate: Float, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(color)
                .clickable(role = Role.Button, onClickLabel = "End preview", onClick = onClick)
                .semantics { contentDescription = "$label (ends the preview)" },
            contentAlignment = Alignment.Center,
        ) { TonoIcon(R.drawable.ic_phone, tint = Color.White, size = 28.dp, modifier = Modifier.rotate(rotate)) }
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

private data class RingNote(val text: String, val muted: Boolean)

private fun ringNote(audio: AudioManager): RingNote {
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_RING).coerceAtLeast(1)
    val vol = audio.getStreamVolume(AudioManager.STREAM_RING)
    return when {
        audio.ringerMode == AudioManager.RINGER_MODE_SILENT ->
            RingNote("Your phone is on silent, so calls (and this preview) make no sound.", true)
        audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE ->
            RingNote("Your phone is on vibrate, so calls (and this preview) make no sound.", true)
        vol == 0 -> RingNote("Ring volume is off. Press volume up to hear it.", true)
        else -> RingNote("Ring volume ${vol * 100 / max}% · use the volume buttons to change it", false)
    }
}
