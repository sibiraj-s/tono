package app.tono.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import app.tono.ui.components.QrImage
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.tono.R
import app.tono.container
import app.tono.ui.components.Card
import app.tono.ui.components.ChoiceChip
import app.tono.ui.components.Divider
import app.tono.ui.components.SectionHeader
import app.tono.ui.components.TonoIcon
import app.tono.ui.theme.ThemeMode
import app.tono.ui.theme.Tono
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(theme: ThemeMode, onTheme: (ThemeMode) -> Unit, onOpenFilters: () -> Unit, updates: UpdateViewModel) {
    val c = Tono.colors
    val context = LocalContext.current
    val store = context.container.toneStore
    var canWrite by remember { mutableStateOf(store.canChangeSystemSounds()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canWrite = store.canChangeSystemSounds() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(start = 4.dp).semantics { heading() })

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionHeader("Appearance", Modifier.padding(start = 4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Theme", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 4.dp))
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m -> ChoiceChip(m.label, theme == m) { onTheme(m) } }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionHeader("Tones", Modifier.padding(start = 4.dp))
            Card(Modifier.fillMaxWidth()) {
                Column {
                    LinkRow(
                        "Change system sounds",
                        if (canWrite) "Allowed. Tono can set your ringtone, notification and alarm."
                        else "Not allowed yet. Tap to allow, so Tono can set tones for you.",
                    ) { context.startActivity(store.manageWriteSettingsIntent()) }
                    Divider()
                    LinkRow("Search filters and ringtone defaults", "Songs only, quality, hidden versions, clip length…", onOpenFilters)
                }
            }
        }

        UpdatesSection(updates)

        ShareSection()

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("About", Modifier.padding(start = 4.dp))
            Text(
                "Tono searches YouTube and YouTube Music with the open-source NewPipeExtractor library. " +
                    "No account or API key is used. Audio is downloaded only to cut your tone and is kept in the app’s cache.",
                style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(horizontal = 4.dp),
            )
            Text(
                "Use it for personal tones from music you have the right to use.",
                style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun LinkRow(title: String, hint: String, onClick: () -> Unit) {
    val c = Tono.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        TonoIcon(R.drawable.ic_arrow_right, tint = c.muted, size = 18.dp)
    }
}

@Composable
private fun ShareSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val linkIntent = remember { ShareApp.linkIntent(context.container.updates.repo) }
    val downloadUrl = remember { ShareApp.downloadUrl(context.container.updates.repo) }
    val canSendFile = remember { ShareApp.canSendFile(context) }
    var preparing by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    if (linkIntent == null && !canSendFile) return

    if (showQr && downloadUrl != null) QrDialog(downloadUrl) { showQr = false }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader("Share Tono", Modifier.padding(start = 4.dp))
        Card(Modifier.fillMaxWidth()) {
            Column {
                if (canSendFile) {
                    LinkRow(
                        "Send the app",
                        if (preparing) "Getting the app file ready…"
                        else "Share the app file with Quick Share, Bluetooth or a chat app. Works offline.",
                    ) {
                        if (preparing) return@LinkRow
                        preparing = true
                        scope.launch {
                            runCatching { ShareApp.fileIntent(context) }
                                .onSuccess { context.startActivity(it) }
                            preparing = false
                        }
                    }
                }
                if (canSendFile && linkIntent != null) Divider()
                if (linkIntent != null) {
                    LinkRow("Share download link", "Send a link to the latest version on GitHub.") {
                        context.startActivity(linkIntent)
                    }
                }
                if (downloadUrl != null) {
                    Divider()
                    LinkRow("Show QR code", "A friend scans it with their camera to get Tono.") { showQr = true }
                }
            }
        }
    }
}

@Composable
private fun QrDialog(url: String, onDismiss: () -> Unit) {
    val c = Tono.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan to get Tono") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                QrImage(
                    url, "QR code for the Tono download page",
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
                )
                Text(
                    "Point the other phone’s camera at this code. It opens the download page for the latest version.",
                    style = MaterialTheme.typography.bodySmall, color = c.muted,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = c.accent) } },
        containerColor = c.surface, titleContentColor = c.text, textContentColor = c.muted,
    )
}

