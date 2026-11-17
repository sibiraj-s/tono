package app.tono.ui.save

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tono.R
import app.tono.audio.ClockApp
import app.tono.container
import app.tono.data.ToneUse
import app.tono.data.formatMs
import app.tono.ui.components.Card
import app.tono.ui.components.IconButton44
import app.tono.ui.components.PrimaryButton
import app.tono.ui.components.Radius12
import app.tono.ui.components.Radius18
import app.tono.ui.components.Radius24
import app.tono.ui.components.TonoIcon
import app.tono.ui.components.TopBar
import app.tono.ui.theme.Tono
import app.tono.ui.trim.EditorUi
import app.tono.ui.trim.EditorViewModel
import app.tono.ui.trim.SaveState
import kotlin.math.max

@Composable
fun SaveScreen(vm: EditorViewModel, onBack: () -> Unit, onDone: () -> Unit, onMyTones: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val c = Tono.colors
    val context = LocalContext.current
    var canWrite by remember { mutableStateOf(context.container.toneStore.canChangeSystemSounds()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canWrite = context.container.toneStore.canChangeSystemSounds() }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.onPermissionResult()
    }
    DisposableEffect(Unit) { onDispose { vm.stopPlayback() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pausePlayback() }

    var hasContacts by remember { mutableStateOf(vm.hasContactsPermission()) }
    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { vm.onContactPicked(it) }
    // Contacts access is asked for before the picker opens: some pickers don't share the chosen
    // contact without it, and it's needed to set the ringtone anyway.
    var afterGrant by remember { mutableStateOf<(() -> Unit)?>(null) }
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasContacts = result.values.all { it }
        if (hasContacts) afterGrant?.invoke() else vm.contactsPermissionDenied()
        afterGrant = null
    }
    fun pickContact() {
        if (hasContacts) contactPicker.launch(null)
        else { afterGrant = { contactPicker.launch(null) }; contactsPermission.launch(app.tono.audio.ToneStore.CONTACTS_PERMISSIONS) }
    }

    val save = ui.save
    if (save is SaveState.Done) {
        DoneScreen(ui, save, onDone, onMyTones)
        return
    }

    if (save is SaveState.NeedsPermission) {
        AlertDialog(
            onDismissRequest = vm::onPermissionResult,
            title = { Text("Allow Tono to change system sounds") },
            text = {
                Text(
                    "Your tone is saved. To make it your ${ui.use.label.lowercase()}, turn on " +
                        "“Allow modifying system settings” for Tono on the next screen, then come back.",
                )
            },
            confirmButton = {
                TextButton(onClick = { settingsLauncher.launch(vm.manageWriteSettingsIntent()) }) {
                    Text("Open settings", color = c.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = vm::onPermissionResult) { Text("Not now", color = c.muted) }
            },
            containerColor = c.surface,
            titleContentColor = c.text,
            textContentColor = c.muted,
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Save tone", R.drawable.ic_back, "Back", onBack)

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            PreviewCard(ui, vm::togglePlay)

            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Name", style = MaterialTheme.typography.bodySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.muted, modifier = Modifier.padding(start = 4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(Radius18)
                        .background(c.surface)
                        .border(1.dp, c.line, Radius18)
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = ui.name,
                        onValueChange = vm::setName,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Name" },
                    )
                }
            }

            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Use as", style = MaterialTheme.typography.bodySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.muted, modifier = Modifier.padding(start = 4.dp))
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToneUse.entries.forEach { use ->
                        UseOption(use.icon, use.label, use.hint, !ui.forContact && ui.use == use) { vm.setUse(use) }
                    }
                    UseOption(
                        R.drawable.ic_person,
                        "Contact",
                        ui.contact?.let { "When ${it.name} calls · tap to change" } ?: "Ring for one person",
                        ui.forContact,
                    ) {
                        // First tap (or tapping again) opens the picker; otherwise reselect the chosen contact.
                        if (ui.contact == null || ui.forContact) pickContact() else vm.selectContactTarget()
                    }
                }
            }
        }

        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (save is SaveState.Failed) {
                Text(save.message, style = MaterialTheme.typography.bodySmall, color = c.danger)
            }
            val hint = when {
                ui.forContact && !hasContacts -> "Android will ask once for access to your contacts."
                !ui.forContact && !canWrite -> "Android will ask once for permission to change system sounds."
                else -> null
            }
            if (hint != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TonoIcon(R.drawable.ic_info, tint = c.muted, size = 16.dp, modifier = Modifier.padding(top = 2.dp))
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
            }
            val working = save as? SaveState.Working
            val contact = ui.contact
            PrimaryButton(
                working?.stage ?: when {
                    !ui.forContact -> ui.use.buttonLabel
                    contact == null -> "Choose a contact"
                    else -> "Set for ${contact.name}"
                },
                {
                    when {
                        !ui.forContact -> vm.save()
                        contact == null -> pickContact()
                        else -> vm.save()
                    }
                },
                Modifier.fillMaxWidth(),
                enabled = working == null,
            )
        }
    }
}

@Composable
private fun PreviewCard(ui: EditorUi, onPlay: () -> Unit) {
    val c = Tono.colors
    Card(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp), shape = Radius24) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconButton44(
                    if (ui.playing) R.drawable.ic_pause else R.drawable.ic_play,
                    if (ui.playing) "Stop" else "Play tone",
                    onPlay,
                    tint = c.onAccent, background = c.accent, iconSize = 20.dp, shape = CircleShape,
                    modifier = Modifier.size(52.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(ui.song?.title.orEmpty(), style = MaterialTheme.typography.titleLarge.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize * 0.95f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val fades = when {
                        ui.fadeIn && ui.fadeOut -> "Fades on"
                        ui.fadeIn -> "Fade in"
                        ui.fadeOut -> "Fade out"
                        else -> "No fades"
                    }
                    Text(
                        "${formatMs(ui.startMs)} – ${formatMs(ui.endMs)} · ${(ui.clipMs + 500) / 1000} s · $fades",
                        style = MaterialTheme.typography.bodySmall, color = c.muted,
                    )
                }
            }
            SelectionBars(ui, c.accent)
        }
    }
}

/** The selected part of the waveform, with fades drawn into the bar heights. */
@Composable
private fun SelectionBars(ui: EditorUi, color: Color) {
    val wf = ui.waveform ?: return
    Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        val bars = 54
        val step = size.width / bars
        var maxP = 0.01f
        val vals = FloatArray(bars) { i ->
            val from = ui.startMs + ui.clipMs * i / bars
            val to = ui.startMs + ui.clipMs * (i + 1) / bars
            var p = 0f
            for (k in wf.indexAt(from)..wf.indexAt(to)) p = max(p, wf.peak[k])
            maxP = max(maxP, p)
            p
        }
        for (i in 0 until bars) {
            val t = ui.clipMs * (i + 0.5f) / bars
            var edge = 1f
            if (ui.fadeIn) edge = minOf(edge, t / EditorViewModel.FADE_IN_MS)
            if (ui.fadeOut) edge = minOf(edge, (ui.clipMs - t) / EditorViewModel.FADE_OUT_MS)
            val h = max(4.dp.toPx(), vals[i] / maxP * size.height * edge.coerceIn(0.08f, 1f))
            val x = step * i + step / 2
            drawLine(color, Offset(x, size.height / 2 - h / 2), Offset(x, size.height / 2 + h / 2), 3.dp.toPx(), StrokeCap.Round)
        }
    }
}

private val ToneUse.icon: Int
    get() = when (this) {
        ToneUse.Ringtone -> R.drawable.ic_phone
        ToneUse.Notification -> R.drawable.ic_bell
        ToneUse.Alarm -> R.drawable.ic_alarm
    }

@Composable
private fun UseOption(icon: Int, label: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    val c = Tono.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(Radius18)
            .background(if (selected) c.accentSoft else c.surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) c.accent else c.line, Radius18)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(Radius12).background(if (selected) c.accent else c.raised),
            contentAlignment = Alignment.Center,
        ) {
            TonoIcon(icon, tint = if (selected) c.onAccent else c.text, size = 20.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        Box(
            Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (selected) c.accent else c.muted, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(c.accent))
        }
    }
}

@Composable
private fun DoneScreen(ui: EditorUi, done: SaveState.Done, onDone: () -> Unit, onMyTones: () -> Unit) {
    val c = Tono.colors
    val context = LocalContext.current
    val clock = context.container.clock
    val alarm = done.use == ToneUse.Alarm && done.contactName == null
    // Samsung Clock ignores the default alarm sound: the tone has to be picked on each alarm.
    val samsungClock = remember(alarm) { alarm && clock.isSamsungClock() }
    val alarmHint = if (alarm) ClockApp.alarmHint(ui.name, done.setAsDefault, samsungClock) else null
    var clockFailed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
            TonoIcon(R.drawable.ic_check, tint = c.accent, size = 30.dp)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            when {
                done.contactName != null -> "Set for ${done.contactName}"
                samsungClock -> "Tone saved"
                alarm && done.setAsDefault -> "It’s your alarm sound now"
                done.setAsDefault -> "It’s your ${done.use.label.lowercase()} now"
                else -> "Tone saved"
            },
            style = MaterialTheme.typography.displaySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (done.contactName != null) {
                "“${ui.name}” will play when ${done.contactName} calls. Everyone else keeps your usual ringtone."
            } else if (alarmHint != null) {
                alarmHint
            } else if (done.setAsDefault) {
                "“${ui.name}” will play for ${done.use.hint.lowercase()}."
            } else {
                "“${ui.name}” is in your phone’s sound list. Set it any time from My tones or your phone’s Sound settings."
            },
            style = MaterialTheme.typography.bodyMedium, color = c.muted,
        )
        if (clockFailed) {
            Spacer(Modifier.height(16.dp))
            Text("Couldn’t open Clock. Open it from your home screen.", style = MaterialTheme.typography.bodySmall, color = c.danger)
        }
        Spacer(Modifier.height(32.dp))
        if (samsungClock) {
            PrimaryButton("Open Clock", { clockFailed = !clock.openSamsungAlarms(context) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlineButton("Make another", onDone)
        } else {
            PrimaryButton("Make another", onDone, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        OutlineButton("Go to My tones", onMyTones)
    }
}

@Composable
private fun OutlineButton(text: String, onClick: () -> Unit) {
    val c = Tono.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(Radius18)
            .border(1.dp, c.line, Radius18)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}
