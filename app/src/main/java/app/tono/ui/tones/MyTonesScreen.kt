package app.tono.ui.tones

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import app.tono.data.RotationConfig
import app.tono.rotation.RingtoneRotation
import app.tono.ui.components.CallPreview
import app.tono.ui.components.RingtoneAudioAttributes
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.tono.R
import app.tono.audio.ClockApp
import app.tono.audio.ContactTone
import app.tono.audio.SavedTone
import app.tono.audio.ToneStore
import app.tono.container
import app.tono.data.DownloadedSong
import app.tono.data.Song
import app.tono.data.ToneUse
import app.tono.data.formatDuration
import app.tono.data.formatMs
import app.tono.ui.components.Artwork
import app.tono.ui.components.Card
import app.tono.ui.components.IconButton44
import app.tono.ui.components.Pill
import app.tono.ui.components.PrimaryButton
import app.tono.ui.components.Radius12
import app.tono.ui.components.Radius18
import app.tono.ui.components.TabPill
import app.tono.ui.components.TonoIcon
import app.tono.ui.theme.Tono
import app.tono.ui.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TonesTab(val label: String) { Tones("Tones"), Contacts("Contacts"), Downloads("Downloads") }

data class TonesUi(
    val tab: TonesTab = TonesTab.Tones,
    val tones: List<SavedTone> = emptyList(),
    val defaults: Map<ToneUse, Long?> = emptyMap(),
    val contacts: List<ContactTone> = emptyList(),
    val downloads: List<DownloadedSong> = emptyList(),
    val canReadContacts: Boolean = false,
    val playing: Uri? = null,
    val loaded: Boolean = false,
    val message: String? = null,
    /** Set-as-default waiting for the "Modify system settings" grant. */
    val pending: Pair<SavedTone, ToneUse>? = null,
    /** Delete waiting for the same grant (the tone is a current system sound). */
    val pendingDelete: SavedTone? = null,
    /** Tone shown on the pretend incoming-call screen. */
    val callPreview: SavedTone? = null,
    val rotation: RotationConfig = RotationConfig(),
)

class MyTonesViewModel(app: Application) : AndroidViewModel(app) {
    private val store = app.container.toneStore
    private val clock = app.container.clock
    private val youTube = app.container.youTube
    private val prefs = app.container.prefs
    private val _ui = MutableStateFlow(TonesUi())
    val ui: StateFlow<TonesUi> = _ui.asStateFlow()

    private val player by lazy {
        ExoPlayer.Builder(app).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) _ui.update { it.copy(playing = null) }
                }
            })
        }
    }

    /** Separate player for the call preview: ringtone channel, looping. */
    private val ringPlayer by lazy {
        ExoPlayer.Builder(app).build().apply {
            setAudioAttributes(RingtoneAudioAttributes, false)
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val tones = runCatching { store.list() }.getOrDefault(emptyList())
            val canRead = store.hasContactsPermission()
            val contacts = if (canRead) runCatching { store.contactsWithCustomRingtones() }.getOrDefault(emptyList()) else emptyList()
            val downloads = runCatching { youTube.listDownloads() }.getOrDefault(emptyList())
            val rotation = prefs.rotation.first()
            _ui.update {
                it.copy(
                    rotation = rotation,
                    tones = tones, defaults = store.currentDefaultIds(), contacts = contacts, downloads = downloads,
                    canReadContacts = canRead, loaded = true,
                )
            }
        }
    }

    fun setTab(tab: TonesTab) = _ui.update { it.copy(tab = tab) }

    fun togglePlay(uri: Uri) {
        if (_ui.value.playing == uri) {
            stop(); return
        }
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.play()
        _ui.update { it.copy(playing = uri) }
    }

    fun stop() {
        player.stop()
        _ui.update { it.copy(playing = null) }
        stopCallPreview()
    }

    fun startCallPreview(t: SavedTone) {
        player.stop()
        ringPlayer.setMediaItem(MediaItem.fromUri(t.uri))
        ringPlayer.prepare()
        ringPlayer.play()
        _ui.update { it.copy(playing = null, callPreview = t) }
    }

    fun stopCallPreview() {
        if (_ui.value.callPreview == null) return
        ringPlayer.stop()
        _ui.update { it.copy(callPreview = null) }
    }

    fun rename(t: SavedTone, name: String) {
        if (name.isBlank() || name.trim() == t.title) return
        viewModelScope.launch {
            runCatching { store.rename(t, name) }
                .onSuccess { renamed ->
                    // It's a new file with a new id; keep it in the rotation.
                    if (renamed.id != t.id) prefs.updateRotation { cfg ->
                        cfg.copy(
                            toneIds = if (t.id in cfg.toneIds) cfg.toneIds - t.id + renamed.id else cfg.toneIds,
                            lastToneId = if (cfg.lastToneId == t.id) renamed.id else cfg.lastToneId,
                        )
                    }
                    say("Renamed to “${renamed.title}”.")
                }
                .onFailure { e -> say(e.userMessage()) }
            refresh()
        }
    }

    fun showMessage(msg: String) = say(msg)

    fun setAs(t: SavedTone, use: ToneUse) {
        if (!store.canChangeSystemSounds()) {
            _ui.update { it.copy(pending = t to use) }
            return
        }
        viewModelScope.launch {
            runCatching { store.setAsDefault(t.uri, use) }
                .onSuccess {
                    val alarmHint = if (use == ToneUse.Alarm) ClockApp.alarmHint(t.title, isDefault = true, clock.isSamsungClock()) else null
                    say(alarmHint ?: "“${t.title}” is your ${use.label.lowercase()} now.")
                }
                .onFailure { e -> say(e.userMessage()) }
            refresh()
        }
    }

    fun onPermissionResult() {
        _ui.value.pendingDelete?.let { t ->
            _ui.update { it.copy(pendingDelete = null) }
            if (store.canChangeSystemSounds()) delete(t) else say("“${t.title}” wasn’t deleted.")
            return
        }
        val (t, use) = _ui.value.pending ?: return
        _ui.update { it.copy(pending = null) }
        if (store.canChangeSystemSounds()) setAs(t, use)
    }

    fun cancelPending() = _ui.update { it.copy(pending = null, pendingDelete = null) }

    fun hasContactsPermission() = store.hasContactsPermission()

    fun changeContactTone(contact: ContactTone, t: SavedTone) {
        viewModelScope.launch {
            runCatching { store.setForContact(contact.contactId, t.uri) }
                .onSuccess { say("“${t.title}” will play when ${contact.name} calls.") }
                .onFailure { e -> say(e.userMessage()) }
            refresh()
        }
    }

    fun clearContactTone(contact: ContactTone) {
        if (_ui.value.playing == contact.toneUri) stop()
        viewModelScope.launch {
            runCatching { store.clearContactRingtone(contact.contactId) }
                .onSuccess { say("${contact.name} uses your normal ringtone again.") }
                .onFailure { e -> say(e.userMessage()) }
            refresh()
        }
    }

    fun contactsPermissionDenied() = say("Tono needs contacts access to set a ringtone for one person.")

    fun deleteDownload(d: DownloadedSong) {
        viewModelScope.launch {
            runCatching { youTube.deleteDownload(d) }
            refresh()
        }
    }

    fun deleteAllDownloads() {
        viewModelScope.launch {
            _ui.value.downloads.forEach { runCatching { youTube.deleteDownload(it) } }
            say("Downloads cleared.")
            refresh()
        }
    }

    /** Deletes [t] and repoints its uses (system defaults, contacts) so nothing is left silent. */
    fun delete(t: SavedTone) {
        if (_ui.value.playing == t.uri) stop()
        val usedAs = _ui.value.defaults.filterValues { it == t.id }.keys
        if (usedAs.isNotEmpty() && !store.canChangeSystemSounds()) {
            _ui.update { it.copy(pendingDelete = t) } // ask first; see onPermissionResult
            return
        }
        val contactIds = _ui.value.contacts.filter { it.toneId == t.id }.map { it.contactId }
        viewModelScope.launch {
            runCatching { store.deleteTone(t, usedAs, contactIds) }
                .onSuccess { notes ->
                    val remaining = _ui.value.tones.map { it.id }.toSet() - t.id
                    val wasOn = _ui.value.rotation.enabled
                    val rotation = prefs.updateRotation { cfg ->
                        val ids = cfg.toneIds - t.id
                        // Rotation needs two tones that still exist; with fewer left, turn it off.
                        cfg.copy(toneIds = ids, enabled = cfg.enabled && (ids intersect remaining).size >= 2)
                    }
                    val all = notes.toMutableList()
                    if (wasOn && !rotation.enabled) {
                        RingtoneRotation.cancel(getApplication())
                        all += "ringtone rotation is off (it needs two tones)"
                    }
                    say(if (all.isEmpty()) "“${t.title}” deleted." else "“${t.title}” deleted; " + all.joinToString(", ") + ".")
                }
                .onFailure { e -> say(e.userMessage()) }
            refresh()
        }
    }

    private fun say(msg: String) = _ui.update { it.copy(message = msg) }
    fun clearMessage() = _ui.update { it.copy(message = null) }
    fun manageWriteSettingsIntent() = store.manageWriteSettingsIntent()

    override fun onCleared() {
        player.release()
        ringPlayer.release()
    }
}

@Composable
fun MyTonesScreen(
    vm: MyTonesViewModel,
    onOpenSong: (Song) -> Unit,
    /** Multi-select contact picker; the tone is null when it's chosen afterwards. */
    onPickContacts: (SavedTone?) -> Unit,
    onOpenRotation: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val c = Tono.colors
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    DisposableEffect(Unit) { onDispose { vm.stop() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.stop() }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.onPermissionResult()
    }

    // Contact flows ask for contacts access first (once), then open Tono's contact picker.
    var changing by remember { mutableStateOf<ContactTone?>(null) }
    var afterGrant by remember { mutableStateOf<(() -> Unit)?>(null) }
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) { vm.refresh(); afterGrant?.invoke() } else vm.contactsPermissionDenied()
        afterGrant = null
    }
    fun withContacts(action: () -> Unit) {
        if (vm.hasContactsPermission()) action()
        else { afterGrant = action; contactsPermission.launch(ToneStore.CONTACTS_PERMISSIONS) }
    }

    ui.pendingDelete?.let { t ->
        val use = ui.defaults.filterValues { it == t.id }.keys.firstOrNull() ?: ToneUse.Ringtone
        AlertDialog(
            onDismissRequest = vm::cancelPending,
            title = { Text("Allow Tono to change system sounds") },
            text = {
                Text(
                    "“${t.title}” is your ${use.label.lowercase()}. To delete it, Tono first switches your " +
                        "${use.label.lowercase()} back to a built-in sound, which needs “Allow modifying system settings”.",
                )
            },
            confirmButton = {
                TextButton(onClick = { settingsLauncher.launch(vm.manageWriteSettingsIntent()) }) { Text("Open settings", color = c.accent) }
            },
            dismissButton = { TextButton(onClick = vm::cancelPending) { Text("Keep it", color = c.muted) } },
            containerColor = c.surface, titleContentColor = c.text, textContentColor = c.muted,
        )
    }
    ui.pending?.let { (_, use) ->
        AlertDialog(
            onDismissRequest = vm::cancelPending,
            title = { Text("Allow Tono to change system sounds") },
            text = { Text("To set your ${use.label.lowercase()}, turn on “Allow modifying system settings” for Tono, then come back.") },
            confirmButton = {
                TextButton(onClick = { settingsLauncher.launch(vm.manageWriteSettingsIntent()) }) { Text("Open settings", color = c.accent) }
            },
            dismissButton = { TextButton(onClick = vm::cancelPending) { Text("Not now", color = c.muted) } },
            containerColor = c.surface, titleContentColor = c.text, textContentColor = c.muted,
        )
    }

    ui.callPreview?.let { t -> CallPreview(t.title, t.artUrl, onClose = vm::stopCallPreview) }
    changing?.let { ct ->
        ChooseToneDialog(
            title = "Ringtone for ${ct.name}",
            tones = ui.tones,
            current = ct.toneId,
            onDismiss = { changing = null },
            onChoose = { t -> changing = null; vm.changeContactTone(ct, t) },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("My tones", style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
            Text(
                when (ui.tab) {
                    TonesTab.Tones -> "Saved in your phone’s Ringtones, Notifications and Alarms folders."
                    TonesTab.Contacts -> "People who have their own ringtone."
                    TonesTab.Downloads -> "Songs you’ve downloaded. Open one to cut another tone without downloading again."
                },
                style = MaterialTheme.typography.bodyMedium, color = c.muted,
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TonesTab.entries.forEach { tab ->
                val count = when (tab) {
                    TonesTab.Tones -> ui.tones.size
                    TonesTab.Contacts -> ui.contacts.size
                    TonesTab.Downloads -> ui.downloads.size
                }
                TabPill(if (ui.loaded && count > 0) "${tab.label} · $count" else tab.label, ui.tab == tab) { vm.setTab(tab) }
            }
        }
        ui.message?.let { msg ->
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp).clip(Radius18).background(c.accentSoft).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(msg, style = MaterialTheme.typography.bodySmall, color = c.accent, modifier = Modifier.weight(1f))
                IconButton44(R.drawable.ic_close, "Dismiss", vm::clearMessage, tint = c.accent, iconSize = 16.dp)
            }
        }

        when (ui.tab) {
            TonesTab.Tones -> TonesList(
                ui, vm,
                onSetForContacts = { t -> withContacts { onPickContacts(t) } },
                onOpenRotation = onOpenRotation,
            )
            TonesTab.Contacts -> ContactsList(
                ui, vm,
                onAllow = { withContacts {} },
                onAdd = { withContacts { onPickContacts(null) } },
                onChange = { changing = it },
            )
            TonesTab.Downloads -> DownloadsList(ui, vm, onOpenSong)
        }
    }
}

@Composable
private fun TonesList(ui: TonesUi, vm: MyTonesViewModel, onSetForContacts: (SavedTone) -> Unit, onOpenRotation: () -> Unit) {
    val c = Tono.colors
    if (ui.loaded && ui.tones.isEmpty()) {
        Text(
            "No tones yet. Find a song, trim it, and it’ll show up here.",
            Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = c.muted,
        )
        return
    }
    // Which contacts use each tone, for the little "Mom" chips.
    val contactsByTone = ui.contacts.filter { it.toneId != null }.groupBy({ it.toneId!! }, { it.name })
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp)) {
        if (ui.tones.size >= 2 || ui.rotation.enabled) item(key = "rotation") {
            val chosen = ui.tones.count { it.id in ui.rotation.toneIds }
            Card(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = onOpenRotation)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(if (ui.rotation.enabled) c.accent else c.accentSoft),
                        contentAlignment = Alignment.Center,
                    ) { TonoIcon(R.drawable.ic_shuffle, tint = if (ui.rotation.enabled) c.onAccent else c.accent, size = 18.dp) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Ringtone rotation", style = MaterialTheme.typography.titleSmall)
                        Text(ui.rotation.summary(chosen), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TonoIcon(R.drawable.ic_arrow_right, tint = c.muted, size = 18.dp)
                }
            }
        }
        items(ui.tones, key = { it.id }) { t ->
            ToneRow(
                tone = t,
                playing = ui.playing == t.uri,
                inUseAs = ui.defaults.filterValues { it == t.id }.keys,
                contactNames = contactsByTone[t.id].orEmpty(),
                onPlay = { vm.togglePlay(t.uri) },
                onSetAs = { use -> vm.setAs(t, use) },
                onSetForContacts = { onSetForContacts(t) },
                onHearAsCall = { vm.startCallPreview(t) },
                onRename = { name -> vm.rename(t, name) },
                onDelete = { vm.delete(t) },
            )
        }
    }
}

@Composable
private fun ContactsList(
    ui: TonesUi,
    vm: MyTonesViewModel,
    onAllow: () -> Unit,
    onAdd: () -> Unit,
    onChange: (ContactTone) -> Unit,
) {
    val c = Tono.colors
    if (!ui.canReadContacts) {
        Card(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("See who has their own ringtone", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Tono reads only each contact’s name and ringtone, and changes a ringtone only when you ask.",
                    style = MaterialTheme.typography.bodySmall, color = c.muted,
                )
                PrimaryButton("Allow contacts access", onAllow, Modifier.fillMaxWidth())
            }
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp)) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(Radius18)
                    .selectable(selected = false, role = Role.Button, onClick = onAdd)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                    TonoIcon(R.drawable.ic_plus, tint = c.onAccent, size = 20.dp)
                }
                Text("Set ringtones for contacts", style = MaterialTheme.typography.titleSmall, color = c.accent)
            }
        }
        if (ui.contacts.isEmpty()) item {
            Text(
                "No one has their own ringtone yet.",
                Modifier.padding(horizontal = 12.dp, vertical = 16.dp), style = MaterialTheme.typography.bodyMedium, color = c.muted,
            )
        }
        items(ui.contacts, key = { it.contactId }) { ct ->
            ContactRow(
                ct,
                playing = ui.playing == ct.toneUri,
                onPlay = { vm.togglePlay(ct.toneUri) },
                onChange = { onChange(ct) },
                onClear = { vm.clearContactTone(ct) },
            )
        }
    }
}

@Composable
private fun DownloadsList(ui: TonesUi, vm: MyTonesViewModel, onOpen: (Song) -> Unit) {
    val c = Tono.colors
    var confirmClear by remember { mutableStateOf(false) }
    if (ui.loaded && ui.downloads.isEmpty()) {
        Text(
            "Nothing downloaded yet. Songs you make tones from are kept here.",
            Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = c.muted,
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp)) {
        items(ui.downloads, key = { it.file.name }) { d ->
            DownloadRow(d, onOpen = { onOpen(d.song) }, onDelete = { vm.deleteDownload(d) })
        }
        item {
            val totalMb = ui.downloads.sumOf { it.sizeBytes } / (1024.0 * 1024.0)
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("%.1f MB in total".format(totalMb), style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmClear = true }) { Text("Delete all", color = c.danger) }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all downloads?") },
            text = { Text("Your saved tones stay. Songs will download again if you open them.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.deleteAllDownloads() }) { Text("Delete all", color = c.danger) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = c.muted) } },
            containerColor = c.surface, titleContentColor = c.text, textContentColor = c.muted,
        )
    }
}

@Composable
private fun DownloadRow(d: DownloadedSong, onOpen: () -> Unit, onDelete: () -> Unit) {
    val c = Tono.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Radius18)
            .selectable(selected = false, role = Role.Button, onClick = onOpen)
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(d.song.thumbnailUrl, size = 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(d.song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val mb = d.sizeBytes / (1024.0 * 1024.0)
            Text(
                listOf(d.song.artist, formatDuration(d.song.durationSec), "%.1f MB".format(mb)).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton44(R.drawable.ic_more, "More options for ${d.song.title}", { menu = true }, tint = c.muted)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.raised) {
                DropdownMenuItem(text = { Text("Delete download", color = c.danger) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun ContactRow(ct: ContactTone, playing: Boolean, onPlay: () -> Unit, onChange: () -> Unit, onClear: () -> Unit) {
    val c = Tono.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(Radius12).background(c.raised), contentAlignment = Alignment.Center) {
            TonoIcon(R.drawable.ic_person, size = 20.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(ct.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ct.toneTitle, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton44(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play,
            if (playing) "Stop" else "Play ${ct.name}’s ringtone",
            onPlay,
            tint = if (playing) c.onAccent else c.accent,
            background = if (playing) c.accent else c.accentSoft,
            iconSize = 18.dp, shape = CircleShape,
        )
        Box {
            IconButton44(R.drawable.ic_more, "More options for ${ct.name}", { menu = true }, tint = c.muted)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.raised) {
                DropdownMenuItem(text = { Text("Change tone…") }, onClick = { menu = false; onChange() })
                DropdownMenuItem(text = { Text("Use normal ringtone") }, onClick = { menu = false; onClear() })
            }
        }
    }
}

/** Radio list of saved Tono tones. */
@Composable
fun ChooseToneDialog(
    title: String,
    tones: List<SavedTone>,
    current: Long?,
    onDismiss: () -> Unit,
    onChoose: (SavedTone) -> Unit,
) {
    val c = Tono.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (tones.isEmpty()) {
                Text("You haven’t made any tones yet. Search for a song and trim one first.")
            } else {
                Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                    tones.forEach { t ->
                        val selected = t.id == current
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(Radius12)
                                .selectable(selected = selected, role = Role.RadioButton, onClick = { onChoose(t) })
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                Modifier.size(20.dp).clip(CircleShape).background(if (selected) c.accent else c.raised),
                                contentAlignment = Alignment.Center,
                            ) { if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(c.onAccent)) }
                            Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.muted) } },
        containerColor = c.surface, titleContentColor = c.text, textContentColor = c.text,
    )
}

@Composable
private fun ToneRow(
    tone: SavedTone,
    playing: Boolean,
    inUseAs: Set<ToneUse>,
    contactNames: List<String>,
    onPlay: () -> Unit,
    onSetAs: (ToneUse) -> Unit,
    onSetForContacts: () -> Unit,
    onHearAsCall: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val c = Tono.colors
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).padding(start = 8.dp, end = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Album art of the source song, with the play/stop control on top of it.
        val label = if (playing) "Stop ${tone.title}" else "Play ${tone.title}"
        Box(
            Modifier
                .size(48.dp)
                .clip(Radius12)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onPlay)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Artwork(tone.artUrl, size = 48.dp, shape = Radius12)
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(if (playing) c.accent else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                TonoIcon(if (playing) R.drawable.ic_pause else R.drawable.ic_play, size = 14.dp, tint = if (playing) c.onAccent else androidx.compose.ui.graphics.Color.White)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(tone.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${tone.use.label} · ${formatMs(tone.durationMs)}", style = MaterialTheme.typography.bodySmall, color = c.muted)
            val chips = inUseAs.map { "Current ${it.label.lowercase()}" } +
                contactNames.take(2) + listOfNotNull(if (contactNames.size > 2) "+${contactNames.size - 2}" else null)
            if (chips.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.forEach { label ->
                        Box(Modifier.clip(Pill).background(c.accentSoft).padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Text(label, style = MaterialTheme.typography.bodySmall, color = c.accent, maxLines = 1)
                        }
                    }
                }
            }
        }
        Box {
            IconButton44(R.drawable.ic_more, "More options for ${tone.title}", { menu = true }, tint = c.muted)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.raised) {
                ToneUse.entries.forEach { use ->
                    DropdownMenuItem(
                        text = { Text("Set as ${use.label.lowercase()}") },
                        onClick = { menu = false; onSetAs(use) },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Set for contacts…") },
                    onClick = { menu = false; onSetForContacts() },
                )
                DropdownMenuItem(
                    text = { Text("Hear it as a call") },
                    onClick = { menu = false; onHearAsCall() },
                )
                DropdownMenuItem(
                    text = { Text("Rename…") },
                    onClick = { menu = false; renaming = true },
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = c.danger) },
                    onClick = { menu = false; confirmDelete = true },
                )
            }
        }
    }

    if (renaming) RenameDialog(tone.title, onDismiss = { renaming = false }, onRename = { renaming = false; onRename(it) })

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${tone.title}”?") },
            text = {
                val uses = inUseAs.map { "your ${it.label.lowercase()}" } + contactNames.map { "$it’s ringtone" }
                Text(
                    if (uses.isEmpty()) "It’s removed from your phone."
                    else "It’s " + uses.joinToString(", ") + ". After deleting, contacts go back to your normal ringtone and " +
                        "system sounds go back to a built-in sound.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = c.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = c.muted) } },
            containerColor = c.surface, titleContentColor = c.text, textContentColor = c.muted,
        )
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    val c = Tono.colors
    var value by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename tone") },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(Radius18)
                    .background(c.bg)
                    .border(1.dp, c.line, Radius18)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onRename(value.text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Tone name" },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(value.text) }, enabled = value.text.isNotBlank()) { Text("Rename", color = c.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.muted) } },
        containerColor = c.surface, titleContentColor = c.text, textContentColor = c.text,
    )
}
