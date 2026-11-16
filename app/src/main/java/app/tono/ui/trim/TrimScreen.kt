package app.tono.ui.trim

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tono.R
import app.tono.data.formatDuration
import app.tono.data.formatMs
import app.tono.ui.components.CallPreview
import app.tono.ui.components.Card
import app.tono.ui.components.Divider
import app.tono.ui.components.IconButton44
import app.tono.ui.components.Artwork
import app.tono.ui.components.Pill
import app.tono.ui.components.PrimaryButton
import app.tono.ui.components.Radius12
import app.tono.ui.components.Radius24
import app.tono.ui.components.SwitchRow
import app.tono.ui.components.TonoIcon
import app.tono.ui.components.TopBar
import app.tono.ui.theme.Tono

@Composable
fun TrimScreen(vm: EditorViewModel, onBack: () -> Unit, onContinue: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val c = Tono.colors

    // Stop the preview when leaving the screen.
    DisposableEffect(Unit) { onDispose { vm.stopCallPreview(); vm.stopPlayback() } }
    // Pause (don't keep playing) when the app goes to the background.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.stopCallPreview(); vm.pausePlayback() }

    if (ui.callPreview) {
        CallPreview(ui.name.ifBlank { ui.song?.title.orEmpty() }, ui.song?.thumbnailUrl, onClose = vm::stopCallPreview)
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Trim", R.drawable.ic_back, "Back", onBack)

        val song = ui.song
        if (song != null) {
            Row(
                Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Artwork(song.thumbnailUrl, size = 48.dp, iconSize = 20.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(song.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val dur = if (ui.durationMs > 0) ui.durationMs / 1000 else song.durationSec
                    Text(
                        listOf(song.artist, song.source.label, if (dur > 0) formatDuration(dur) else "")
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = c.muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        when (val load = ui.load) {
            is LoadState.Loading -> LoadingPanel(load)
            is LoadState.Failed -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(load.message, style = MaterialTheme.typography.bodyMedium, color = c.danger)
                PrimaryButton("Try again", vm::retry, Modifier.fillMaxWidth())
            }
            LoadState.Ready -> Editor(ui, vm, onContinue)
        }
    }
}

@Composable
private fun LoadingPanel(load: LoadState.Loading) {
    val c = Tono.colors
    Card(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp), shape = Radius24) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(load.stage, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                load.progress?.let {
                    Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.titleSmall, color = c.muted)
                }
            }
            val progress = load.progress
            if (progress != null) {
                val shown by androidx.compose.animation.core.animateFloatAsState(progress, label = "load")
                LinearProgressIndicator(
                    progress = { shown },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(Pill),
                    color = c.accent, trackColor = c.raised, drawStopIndicator = {},
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(Pill), color = c.accent, trackColor = c.raised)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Editor(ui: EditorUi, vm: EditorViewModel, onContinue: () -> Unit) {
    val c = Tono.colors
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        // Waveform card
        Card(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp), shape = Radius24) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TimeTicks(ui.windowStartMs, ui.windowLenMs)
                ZoomWaveform(ui, vm::setStart, vm::setEnd, vm::moveBy, vm::endDrag, vm::seekTo)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Full song", style = MaterialTheme.typography.bodySmall.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize), color = c.muted)
                    OverviewStrip(ui, vm::centerSelectionAt)
                }
            }
        }

        // Range + length
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${formatMs(ui.startMs)} – ${formatMs(ui.endMs)}", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            // Where the preview is right now (shown while playing, or paused mid-clip).
            if (ui.showPlayhead) {
                Row(
                    Modifier.height(32.dp).clip(Pill).background(c.raised).padding(start = 8.dp, end = 12.dp)
                        .semantics { contentDescription = "Playing at ${formatMs(ui.playheadMs)}" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TonoIcon(if (ui.playing) R.drawable.ic_play else R.drawable.ic_pause, size = 12.dp, tint = c.text)
                    Text(formatMs(ui.playheadMs, tenths = true), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
                }
                Spacer(Modifier.width(8.dp))
            }
            Box(Modifier.height(32.dp).clip(Pill).background(c.accentSoft).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text("${(ui.clipMs + 500) / 1000} s", style = MaterialTheme.typography.labelMedium, color = c.accent)
            }
        }

        // Fine nudges
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Nudge("Start", formatMs(ui.startMs, tenths = true), { vm.nudgeStart(-EditorViewModel.NUDGE_MS) }, { vm.nudgeStart(EditorViewModel.NUDGE_MS) }, Modifier.weight(1f))
            Nudge("End", formatMs(ui.endMs, tenths = true), { vm.nudgeEnd(-EditorViewModel.NUDGE_MS) }, { vm.nudgeEnd(EditorViewModel.NUDGE_MS) }, Modifier.weight(1f))
        }

        // Fades
        Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp)) {
            SwitchRow("Fade in", "1 second", ui.fadeIn, vm::setFadeIn)
            Divider()
            SwitchRow("Fade out", "2 seconds", ui.fadeOut, vm::setFadeOut)
        }

        // Pretend incoming call
        Card(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = vm::startCallPreview)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                    TonoIcon(R.drawable.ic_phone, tint = c.accent, size = 18.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Hear it as a call", style = MaterialTheme.typography.titleSmall)
                    Text("Rings at your ring volume, on repeat", style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
                TonoIcon(R.drawable.ic_arrow_right, tint = c.muted, size = 18.dp)
            }
        }
    }

    // Play + Continue
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val label = if (ui.playing) "Pause preview" else "Play selection"
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(c.surface)
                .border(1.dp, c.line, CircleShape)
                .clickable(role = Role.Button, onClickLabel = label, onClick = vm::togglePlay)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { TonoIcon(if (ui.playing) R.drawable.ic_pause else R.drawable.ic_play, size = 20.dp) }
        val loopLabel = if (ui.loop) "Loop on" else "Loop off"
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (ui.loop) c.accentSoft else c.surface)
                .border(1.dp, if (ui.loop) c.accent else c.line, CircleShape)
                .toggleable(value = ui.loop, role = Role.Switch, onValueChange = { vm.toggleLoop() })
                .semantics { contentDescription = "Loop preview, $loopLabel" },
            contentAlignment = Alignment.Center,
        ) { TonoIcon(R.drawable.ic_loop, tint = if (ui.loop) c.accent else c.text, size = 20.dp) }
        PrimaryButton("Continue", onContinue, Modifier.weight(1f), trailingIcon = R.drawable.ic_arrow_right)
    }
}

@Composable
private fun Nudge(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier) {
    val c = Tono.colors
    Card(modifier) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize), color = c.muted)
                Text(value, style = MaterialTheme.typography.titleMedium)
            }
            IconButton44(R.drawable.ic_minus, "$label earlier", onMinus, iconSize = 18.dp, shape = Radius12, modifier = Modifier.size(40.dp, 44.dp))
            IconButton44(R.drawable.ic_plus, "$label later", onPlus, iconSize = 18.dp, shape = Radius12, modifier = Modifier.size(40.dp, 44.dp))
        }
    }
}
