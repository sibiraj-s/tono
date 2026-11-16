package app.tono.ui.tones

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.tono.R
import app.tono.audio.SavedTone
import app.tono.container
import app.tono.data.RotationConfig
import app.tono.data.RotationInterval
import app.tono.data.ToneUse
import app.tono.data.formatMs
import app.tono.rotation.RingtoneRotation
import app.tono.ui.components.Artwork
import app.tono.ui.components.Card
import app.tono.ui.components.ChoiceChip
import app.tono.ui.components.Divider
import app.tono.ui.components.PrimaryButton
import app.tono.ui.components.Radius12
import app.tono.ui.components.Radius18
import app.tono.ui.components.SectionHeader
import app.tono.ui.components.SwitchRow
import app.tono.ui.components.TonoIcon
import app.tono.ui.components.TopBar
import app.tono.ui.theme.Tono
import app.tono.ui.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RotationUi(
    val config: RotationConfig = RotationConfig(),
    val tones: List<SavedTone> = emptyList(),
    val currentRingtone: Long? = null,
    val canChange: Boolean = true,
    val loaded: Boolean = false,
    val message: String? = null,
) {
    /** Selected tones that still exist. */
    val chosen: List<SavedTone> get() = tones.filter { it.id in config.toneIds }
}

/** One line for the card in My tones and the top of this screen. */
fun RotationConfig.summary(chosenCount: Int): String =
    if (!enabled) "Off · switch between your tones automatically"
    else "${interval.label} · $chosenCount tones · ${if (shuffle) "shuffled" else "in order"}"

class RotationViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val _ui = MutableStateFlow(RotationUi())
    val ui: StateFlow<RotationUi> = _ui.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val tones = runCatching { c.toneStore.list() }.getOrDefault(emptyList()).sortedBy { it.title.lowercase() }
            _ui.update {
                it.copy(
                    config = c.prefs.rotation.first(), tones = tones,
                    currentRingtone = c.toneStore.currentDefaultIds()[ToneUse.Ringtone],
                    canChange = c.toneStore.canChangeSystemSounds(), loaded = true,
                )
            }
        }
    }

    private fun edit(change: (RotationConfig) -> RotationConfig, after: (RotationConfig, RotationConfig) -> Unit = { _, _ -> }) {
        val old = _ui.value.config
        viewModelScope.launch {
            val n = c.prefs.updateRotation(change)
            _ui.update { it.copy(config = n) }
            after(old, n)
        }
    }

    fun setEnabled(on: Boolean) {
        if (on && _ui.value.chosen.size < 2) { say("Tick at least two tones to rotate between."); return }
        edit({ it.copy(enabled = on) }) { _, n ->
            if (on) {
                RingtoneRotation.schedule(getApplication(), n.interval)
                say("Your ringtone will change ${n.interval.label.lowercase()}, early in the morning.")
            } else {
                RingtoneRotation.cancel(getApplication())
                say("Rotation is off. Your current ringtone stays.")
            }
        }
    }

    fun toggleTone(id: Long) {
        val turningOff = _ui.value.config.enabled && id in _ui.value.config.toneIds && _ui.value.chosen.size <= 2
        edit({ cfg ->
            val ids = if (id in cfg.toneIds) cfg.toneIds - id else cfg.toneIds + id
            // Fewer than two tones left: nothing to rotate between.
            cfg.copy(toneIds = ids, enabled = cfg.enabled && !turningOff)
        }) { _, _ ->
            if (turningOff) { RingtoneRotation.cancel(getApplication()); say("Rotation turned off: it needs at least two tones.") }
        }
    }

    fun setInterval(i: RotationInterval) = edit({ it.copy(interval = i) }) { old, n ->
        if (n.enabled && old.interval != n.interval) RingtoneRotation.schedule(getApplication(), n.interval)
    }

    fun setShuffle(on: Boolean) = edit({ it.copy(shuffle = on) })

    fun changeNow() {
        if (!c.toneStore.canChangeSystemSounds()) {
            refresh() // shows the "Allow Tono to change system sounds" card
            say("Allow Tono to change system sounds first.")
            return
        }
        viewModelScope.launch {
            runCatching { RingtoneRotation.rotate(getApplication()) }
                .onSuccess { t -> say(if (t != null) "Your ringtone is now “${t.title}”." else "Tick a tone that’s still in My tones first.") }
                .onFailure { e -> say(e.userMessage()) }
            refresh()
        }
    }

    private fun say(m: String) = _ui.update { it.copy(message = m) }
    fun clearMessage() = _ui.update { it.copy(message = null) }
    fun manageWriteSettingsIntent() = c.toneStore.manageWriteSettingsIntent()
}

@Composable
fun RotationScreen(vm: RotationViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val c = Tono.colors
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refresh() }

    Column(Modifier.fillMaxSize()) {
        TopBar("Ringtone rotation", R.drawable.ic_back, "Back", onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Text(
                    "Tono switches your ringtone between the tones you tick, at about 4 AM so it never changes during a call.",
                    style = MaterialTheme.typography.bodyMedium, color = c.muted,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp),
                )
            }
            if (ui.loaded && !ui.canChange) item {
                Card(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Allow Tono to change system sounds", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Rotation needs “Allow modifying system settings” to switch your ringtone.",
                            style = MaterialTheme.typography.bodySmall, color = c.muted,
                        )
                        PrimaryButton("Open settings", { settings.launch(vm.manageWriteSettingsIntent()) }, Modifier.fillMaxWidth())
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                    Column {
                        SwitchRow("Rotate my ringtone", ui.config.summary(ui.chosen.size), ui.config.enabled, vm::setEnabled)
                        Divider()
                        SwitchRow("Shuffle", if (ui.config.shuffle) "Random order, never the same twice in a row" else "A to Z, one after another", ui.config.shuffle, vm::setShuffle)
                    }
                }
            }
            ui.message?.let { msg ->
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp).clip(Radius18)
                            .background(c.accentSoft).padding(horizontal = 16.dp, vertical = 12.dp),
                    ) { Text(msg, style = MaterialTheme.typography.bodySmall, color = c.accent) }
                }
            }
            item {
                SectionHeader("How often", Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 10.dp))
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RotationInterval.entries.forEach { i -> ChoiceChip(i.label, ui.config.interval == i) { vm.setInterval(i) } }
                }
            }
            item {
                SectionHeader(
                    "Tones · ${ui.chosen.size} ticked",
                    Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 6.dp),
                )
            }
            if (ui.loaded && ui.tones.size < 2) item {
                Text(
                    "Make at least two tones to rotate between.",
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium, color = c.muted,
                )
            }
            items(ui.tones, key = { it.id }) { t ->
                RotationToneRow(t, checked = t.id in ui.config.toneIds, current = t.id == ui.currentRingtone) { vm.toggleTone(t.id) }
            }
        }
        PrimaryButton(
            "Change ringtone now", vm::changeNow,
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
            enabled = ui.chosen.isNotEmpty() && ui.canChange,
            trailingIcon = R.drawable.ic_shuffle,
        )
    }
}

@Composable
private fun RotationToneRow(t: SavedTone, checked: Boolean, current: Boolean, onToggle: () -> Unit) {
    val c = Tono.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .heightIn(min = 60.dp)
            .clip(Radius18)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(t.artUrl, size = 44.dp, shape = Radius12, iconSize = 18.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(formatMs(t.durationMs), if (current) "Current ringtone" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = if (current) c.accent else c.muted,
            )
        }
        val box = RoundedCornerShape(7.dp)
        Box(
            Modifier.size(24.dp).clip(box).background(if (checked) c.accent else Color.Transparent)
                .border(1.5.dp, if (checked) c.accent else c.off, box),
            contentAlignment = Alignment.Center,
        ) { if (checked) TonoIcon(R.drawable.ic_check, tint = c.onAccent, size = 16.dp) }
    }
}
