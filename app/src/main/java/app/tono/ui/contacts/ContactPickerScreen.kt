package app.tono.ui.contacts

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.tono.R
import app.tono.audio.ContactEntry
import app.tono.audio.ContactGroup
import app.tono.audio.SavedTone
import app.tono.container
import app.tono.ui.components.Artwork
import app.tono.ui.components.PrimaryButton
import app.tono.ui.components.Radius18
import app.tono.ui.components.ToggleChip
import app.tono.ui.components.TonoIcon
import app.tono.ui.components.TopBar
import app.tono.ui.theme.Tono
import app.tono.ui.tones.ChooseToneDialog
import app.tono.ui.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PickerUi(
    /** Tone being given out; null when it's chosen after picking people (Contacts tab). */
    val tone: SavedTone? = null,
    val loading: Boolean = true,
    val contacts: List<ContactEntry> = emptyList(),
    val groups: List<ContactGroup> = emptyList(),
    /** Ringtone names for contacts that have their own. */
    val ringtoneTitles: Map<Long, String> = emptyMap(),
    /** Contacts that already use [tone]; unticking one puts them back on the normal ringtone. */
    val initial: Set<Long> = emptySet(),
    val selected: Set<Long> = emptySet(),
    val query: String = "",
    val tones: List<SavedTone> = emptyList(),
    val saving: Boolean = false,
    val error: String? = null,
) {
    val favourites: Set<Long> get() = contacts.filter { it.starred }.map { it.id }.toSet()
    val changed: Boolean get() = selected != initial
    val visible: List<ContactEntry>
        get() = if (query.isBlank()) contacts else contacts.filter { it.name.contains(query.trim(), ignoreCase = true) }
}

class ContactPickerViewModel(app: Application) : AndroidViewModel(app) {
    private val store = app.container.toneStore
    private val _ui = MutableStateFlow(PickerUi())
    val ui: StateFlow<PickerUi> = _ui.asStateFlow()
    private var loadJob: Job? = null

    fun open(tone: SavedTone?) {
        loadJob?.cancel()
        _ui.value = PickerUi(tone = tone)
        loadJob = viewModelScope.launch {
            runCatching {
                val contacts = store.allContacts()
                val ids = contacts.map { it.id }.toSet()
                // Only groups with people we list (contacts with a phone number).
                val groups = store.contactGroups()
                    .map { g -> g.copy(memberIds = g.memberIds intersect ids) }
                    .filter { it.memberIds.isNotEmpty() }
                val custom = store.contactsWithCustomRingtones()
                val already = if (tone == null) emptySet()
                else custom.filter { it.toneId == tone.id }.map { it.contactId }.toSet() intersect ids
                _ui.update {
                    it.copy(
                        loading = false, contacts = contacts, groups = groups,
                        ringtoneTitles = custom.associate { ct -> ct.contactId to ct.toneTitle },
                        initial = already, selected = already, tones = store.list(),
                    )
                }
            }.onFailure { e -> _ui.update { it.copy(loading = false, error = e.userMessage()) } }
        }
    }

    fun setQuery(q: String) = _ui.update { it.copy(query = q) }

    fun toggle(id: Long) = _ui.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }

    /** Ticks the whole group, or unticks it when everyone in it is already ticked. */
    fun toggleGroup(members: Set<Long>) = _ui.update {
        it.copy(selected = if (it.selected.containsAll(members)) it.selected - members else it.selected + members)
    }

    /** Applies the changes and returns a summary for My tones, or null on failure (shown here). */
    fun save(tone: SavedTone, onDone: (String) -> Unit) {
        val s = _ui.value
        if (s.saving) return
        val add = s.selected - s.initial
        val remove = s.initial - s.selected
        _ui.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            runCatching { store.updateContactRingtones(add, tone.uri, remove) }
                .onSuccess { onDone(summary(tone, add, remove, s.contacts)) }
                .onFailure { e -> _ui.update { it.copy(saving = false, error = e.userMessage()) } }
        }
    }

    private fun summary(tone: SavedTone, add: Set<Long>, remove: Set<Long>, all: List<ContactEntry>): String {
        val names = all.associate { it.id to it.name }
        fun people(ids: Set<Long>) = if (ids.size == 1) names[ids.first()] ?: "1 contact" else "${ids.size} contacts"
        val parts = buildList {
            if (add.isNotEmpty()) add("“${tone.title}” will play when ${people(add)} call${if (add.size == 1) "s" else ""}")
            if (remove.isNotEmpty()) add("${people(remove)} use${if (remove.size == 1) "s" else ""} the normal ringtone again")
        }
        return parts.joinToString("; ").replaceFirstChar { it.uppercase() } + "."
    }
}

@Composable
fun ContactPickerScreen(vm: ContactPickerViewModel, onBack: () -> Unit, onDone: (String) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val c = Tono.colors
    var chooseTone by remember { mutableStateOf(false) }

    if (chooseTone) {
        ChooseToneDialog(
            title = "Ringtone for ${ui.selected.size} contact${if (ui.selected.size == 1) "" else "s"}",
            tones = ui.tones,
            current = null,
            onDismiss = { chooseTone = false },
            onChoose = { t -> chooseTone = false; vm.save(t, onDone) },
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Choose contacts", R.drawable.ic_back, "Back", onBack)

        ui.tone?.let { t ->
            Row(
                Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Artwork(t.artUrl, size = 40.dp, iconSize = 18.dp)
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Ticked contacts get this ringtone", style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
            }
        }

        // Search
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 16.dp)
                .height(48.dp)
                .clip(Radius18)
                .background(c.surface)
                .border(1.dp, c.line, Radius18)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TonoIcon(R.drawable.ic_search, tint = c.muted, size = 18.dp)
                Box(Modifier.weight(1f)) {
                    if (ui.query.isEmpty()) Text("Search contacts", style = MaterialTheme.typography.bodyLarge, color = c.muted)
                    BasicTextField(
                        value = ui.query,
                        onValueChange = vm::setQuery,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search contacts" },
                    )
                }
            }
        }

        // Groups: tick everyone in one go.
        val groupChips = buildList {
            if (ui.favourites.isNotEmpty()) add("Favourites" to ui.favourites)
            ui.groups.forEach { add(it.title to it.memberIds) }
        }
        if (groupChips.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groupChips.forEach { (title, members) ->
                    ToggleChip("$title · ${members.size}", ui.selected.containsAll(members)) { vm.toggleGroup(members) }
                }
            }
        }

        Box(Modifier.weight(1f)) {
            when {
                ui.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = c.accent)
                ui.contacts.isEmpty() -> Text(
                    "No contacts with phone numbers.", Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium, color = c.muted,
                )
                else -> LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
                    items(ui.visible, key = { it.id }) { ct ->
                        ContactPickRow(
                            ct,
                            subtitle = ui.ringtoneTitles[ct.id]?.let { "Ringtone: $it" },
                            checked = ct.id in ui.selected,
                            onToggle = { vm.toggle(ct.id) },
                        )
                    }
                }
            }
        }

        ui.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        }

        val n = ui.selected.size
        val tone = ui.tone
        if (tone != null) {
            val label = when {
                ui.saving -> "Saving…"
                !ui.changed -> "No changes"
                else -> "Save · $n contact${if (n == 1) "" else "s"}"
            }
            PrimaryButton(
                label, { vm.save(tone, onDone) },
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
                enabled = ui.changed && !ui.saving,
            )
        } else {
            PrimaryButton(
                if (ui.saving) "Saving…" else if (n == 0) "Tick contacts" else "Choose a tone · $n contact${if (n == 1) "" else "s"}",
                { chooseTone = true },
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
                enabled = n > 0 && !ui.saving,
            )
        }
    }
}

@Composable
private fun ContactPickRow(ct: ContactEntry, subtitle: String?, checked: Boolean, onToggle: () -> Unit) {
    val c = Tono.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(Radius18)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(ct)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(ct.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(
            Modifier
                .size(24.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(7.dp))
                .background(if (checked) c.accent else androidx.compose.ui.graphics.Color.Transparent)
                .border(1.5.dp, if (checked) c.accent else c.off, androidx.compose.foundation.shape.RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) { if (checked) TonoIcon(R.drawable.ic_check, tint = c.onAccent, size = 16.dp) }
    }
}

@Composable
private fun Avatar(ct: ContactEntry) {
    val c = Tono.colors
    Box(Modifier.size(44.dp).clip(CircleShape).background(c.raised), contentAlignment = Alignment.Center) {
        Text(ct.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", style = MaterialTheme.typography.titleSmall, color = c.muted)
        if (ct.photoUri != null) {
            coil3.compose.AsyncImage(
                model = ct.photoUri, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
