package app.tono.ui.filters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tono.R
import app.tono.data.FormatPref
import app.tono.data.LengthFilter
import app.tono.data.MinQuality
import app.tono.data.SearchFilters
import app.tono.data.SearchLanguage
import app.tono.data.SortOrder
import app.tono.data.UploadedFilter
import app.tono.data.VersionTag
import app.tono.ui.components.Card
import app.tono.ui.components.ChoiceChip
import app.tono.ui.components.Divider
import app.tono.ui.components.PrimaryButton
import app.tono.ui.components.SectionHeader
import app.tono.ui.components.SwitchRow
import app.tono.ui.components.ToggleChip
import app.tono.ui.components.TopBar
import app.tono.ui.theme.Tono

/**
 * Edits a draft copy of the filters; "Show results" saves it, the close button discards it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FiltersScreen(
    initial: SearchFilters,
    onApply: (SearchFilters) -> Unit,
    onClose: () -> Unit,
    applyLabel: (activeCount: Int) -> String = { n -> if (n > 0) "Show results · $n filters" else "Show results" },
) {
    var f by rememberSaveable(stateSaver = FiltersSaver) { mutableStateOf(initial) }
    val c = Tono.colors

    Column(Modifier.fillMaxSize()) {
        TopBar("Filters", R.drawable.ic_close, "Close filters", onClose) {
            TextButton(onClick = { f = SearchFilters.cleared(f) }, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("Reset", style = MaterialTheme.typography.titleSmall, color = c.accent)
            }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(36.dp),
        ) {
            Section("Search") {
                Card(Modifier.fillMaxWidth()) {
                    SwitchRow("Songs only", "Official tracks from YouTube Music", f.songsOnly, { f = f.copy(songsOnly = it) })
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Choices("Language", SearchLanguage.entries, f.language, { it.label }) { f = f.copy(language = it) }
                    Text(
                        "Puts songs in this language first. Some others may still show.",
                        style = MaterialTheme.typography.bodySmall, color = c.muted,
                    )
                }
                Choices("Length", LengthFilter.entries, f.length, { it.label }) { f = f.copy(length = it) }
                Choices("Uploaded", UploadedFilter.entries, f.uploaded, { it.label }) { f = f.copy(uploaded = it) }
                Choices("Sort by", SortOrder.entries, f.sort, { it.label }) { f = f.copy(sort = it) }
            }

            Section("Audio", hint = "Applied when the song is downloaded for trimming.") {
                Choices("Minimum quality", MinQuality.entries, f.minQuality, { it.label }) { f = f.copy(minQuality = it) }
                Choices("Preferred format", FormatPref.entries, f.format, { it.label }) { f = f.copy(format = it) }
                Card(Modifier.fillMaxWidth()) {
                    SwitchRow("Official channels only", "Artist and “Topic” channels", f.officialOnly, { f = f.copy(officialOnly = it) })
                }
            }

            Section("Hide versions", hint = "Skips results with these words in the title.", gap = 14) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    VersionTag.entries.forEach { tag ->
                        ToggleChip(tag.label, tag in f.hidden) { on ->
                            f = f.copy(hidden = if (on) f.hidden + tag else f.hidden - tag)
                        }
                    }
                }
            }

            Section("Ringtone defaults", hint = "Used when you open the trimmer.") {
                Choices("Clip length", listOf(20, 30, 40), f.clipSeconds, { "$it s" }) { f = f.copy(clipSeconds = it) }
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        SwitchRow("Volume leveling", "Quiet songs ring as loud as others", f.volumeLeveling, { f = f.copy(volumeLeveling = it) })
                        Divider()
                        SwitchRow("Start at the chorus", "Pre-select the loudest, most repeated part", f.startAtChorus, { f = f.copy(startAtChorus = it) })
                    }
                }
            }
        }

        val n = f.activeCount
        PrimaryButton(
            applyLabel(n),
            { onApply(f) },
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
        )
    }
}

@Composable
private fun Section(title: String, hint: String? = null, gap: Int = 18, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(gap.dp)) {
        SectionHeader(title, hint = hint)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(label: String, options: List<T>, selected: T, text: (T) -> String, onPick: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        FlowRow(
            Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { o -> ChoiceChip(text(o), o == selected) { onPick(o) } }
        }
    }
}

/** Survives rotation / process death while the draft is being edited. */
private val FiltersSaver = androidx.compose.runtime.saveable.Saver<SearchFilters, List<Any>>(
    save = { f ->
        listOf(
            f.songsOnly, f.language.name, f.length.name, f.uploaded.name, f.sort.name, f.minQuality.name,
            f.format.name, f.officialOnly, ArrayList(f.hidden.map { it.name }), f.clipSeconds,
            f.volumeLeveling, f.startAtChorus,
        )
    },
    restore = { l ->
        @Suppress("UNCHECKED_CAST")
        SearchFilters(
            songsOnly = l[0] as Boolean,
            language = SearchLanguage.valueOf(l[1] as String),
            length = LengthFilter.valueOf(l[2] as String),
            uploaded = UploadedFilter.valueOf(l[3] as String),
            sort = SortOrder.valueOf(l[4] as String),
            minQuality = MinQuality.valueOf(l[5] as String),
            format = FormatPref.valueOf(l[6] as String),
            officialOnly = l[7] as Boolean,
            hidden = (l[8] as List<String>).map { VersionTag.valueOf(it) }.toSet(),
            clipSeconds = l[9] as Int,
            volumeLeveling = l[10] as Boolean,
            startAtChorus = l[11] as Boolean,
        )
    },
)
