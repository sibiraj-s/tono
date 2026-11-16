package app.tono.ui.settings

import android.app.Application
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.tono.R
import app.tono.container
import app.tono.data.AppRelease
import app.tono.ui.components.Card
import app.tono.ui.components.Divider
import app.tono.ui.components.Pill
import app.tono.ui.components.PrimaryButton
import app.tono.ui.components.SectionHeader
import app.tono.ui.components.SwitchRow
import app.tono.ui.components.TonoIcon
import app.tono.ui.theme.Tono
import app.tono.ui.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState
    data class Ready(val release: AppRelease, val apk: File) : UpdateState
    data class Failed(val message: String, val release: AppRelease? = null) : UpdateState
}

data class UpdateUi(
    val state: UpdateState = UpdateState.Idle,
    val autoCheck: Boolean = true,
    /** Set when a download finishes, so the screen opens the installer once. */
    val installNow: Boolean = false,
    /** Why the installer didn't open, shown under the Install button. */
    val hint: String? = null,
) {
    val hasUpdate get() = state is UpdateState.Available || state is UpdateState.Ready
}

private const val AUTO_CHECK_EVERY_MS = 20L * 60 * 60 * 1000

/** Activity-scoped, so a download keeps going while you move between tabs. */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val repo = c.updates
    private val _ui = MutableStateFlow(UpdateUi())
    val ui: StateFlow<UpdateUi> = _ui.asStateFlow()
    val enabled get() = repo.enabled
    val currentVersion get() = repo.currentVersion
    private var job: Job? = null

    init {
        viewModelScope.launch { _ui.update { it.copy(autoCheck = c.prefs.autoUpdateCheck.first()) } }
    }

    /** Quiet check when the app opens, at most about once a day. */
    fun checkOnLaunch() {
        if (!repo.enabled) return
        viewModelScope.launch {
            if (!c.prefs.autoUpdateCheck.first()) return@launch
            if (System.currentTimeMillis() - c.prefs.lastUpdateCheck.first() < AUTO_CHECK_EVERY_MS) return@launch
            check(quiet = true)
        }
    }

    fun check(quiet: Boolean = false) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            if (!quiet) _ui.update { it.copy(state = UpdateState.Checking, hint = null) }
            runCatching { repo.checkForUpdate() }
                .onSuccess { r ->
                    c.prefs.setLastUpdateCheck(System.currentTimeMillis())
                    val done = r?.let { repo.downloaded(it.version) }
                    val s = when {
                        r == null -> if (quiet) UpdateState.Idle else UpdateState.UpToDate
                        done != null -> UpdateState.Ready(r, done)
                        else -> UpdateState.Available(r)
                    }
                    _ui.update { it.copy(state = s) }
                }
                .onFailure { e -> if (!quiet) _ui.update { it.copy(state = UpdateState.Failed(e.userMessage())) } }
        }
    }

    fun download(release: AppRelease) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _ui.update { it.copy(state = UpdateState.Downloading(release, 0f), hint = null) }
            runCatching {
                repo.download(release) { p ->
                    _ui.update { u -> if (u.state is UpdateState.Downloading) u.copy(state = UpdateState.Downloading(release, p)) else u }
                }
            }
                .onSuccess { f -> _ui.update { it.copy(state = UpdateState.Ready(release, f), installNow = true) } }
                .onFailure { e -> _ui.update { it.copy(state = UpdateState.Failed(e.userMessage(), release)) } }
        }
    }

    fun cancelDownload() {
        val s = _ui.value.state as? UpdateState.Downloading ?: return
        job?.cancel()
        _ui.update { it.copy(state = UpdateState.Available(s.release)) }
    }

    fun installHandled() = _ui.update { it.copy(installNow = false) }

    /** Install couldn't start: keep the downloaded file and say why under the Install button. */
    fun fail(message: String) = _ui.update { u ->
        if (u.state is UpdateState.Ready) u.copy(hint = message)
        else u.copy(state = UpdateState.Failed(message, (u.state as? UpdateState.Available)?.release))
    }

    fun setAutoCheck(on: Boolean) {
        _ui.update { it.copy(autoCheck = on) }
        viewModelScope.launch { c.prefs.setAutoUpdateCheck(on) }
    }
}

@Composable
fun UpdatesSection(vm: UpdateViewModel) {
    if (!vm.enabled) return
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val repo = context.container.updates

    fun openInstaller(apk: File) {
        try {
            context.startActivity(repo.installIntent(apk))
        } catch (_: ActivityNotFoundException) {
            vm.fail("This phone has no app installer Tono can open.")
        }
    }
    val allowInstall = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val s = vm.ui.value.state
        if (s is UpdateState.Ready) {
            if (repo.canInstall()) openInstaller(s.apk) else vm.fail("Allow “Install unknown apps” for Tono to install the update.")
        }
    }
    fun install(apk: File) {
        if (repo.canInstall()) openInstaller(apk) else allowInstall.launch(repo.allowInstallIntent())
    }
    LaunchedEffect(ui.installNow) {
        val s = ui.state
        if (ui.installNow && s is UpdateState.Ready) { vm.installHandled(); install(s.apk) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader("Updates", Modifier.padding(start = 4.dp))
        Card(Modifier.fillMaxWidth()) {
            Column {
                when (val s = ui.state) {
                    is UpdateState.Available -> ReleaseBlock(s.release, vm.currentVersion) {
                        PrimaryButton(
                            "Download update${s.release.sizeBytes.sizeLabel()}", { vm.download(s.release) },
                            Modifier.fillMaxWidth(), trailingIcon = R.drawable.ic_download,
                        )
                    }
                    is UpdateState.Downloading -> ReleaseBlock(s.release, vm.currentVersion) {
                        DownloadProgress(s.progress, vm::cancelDownload)
                    }
                    is UpdateState.Ready -> ReleaseBlock(s.release, vm.currentVersion) {
                        PrimaryButton("Install update", { install(s.apk) }, Modifier.fillMaxWidth())
                        ui.hint?.let { Hint(it, error = true) }
                        Hint("Android asks you to confirm. Your tones and settings stay.")
                    }
                    is UpdateState.Failed -> if (s.release != null) {
                        ReleaseBlock(s.release, vm.currentVersion) {
                            Hint(s.message, error = true)
                            PrimaryButton("Try again", { vm.download(s.release) }, Modifier.fillMaxWidth())
                        }
                    } else CheckRow(vm.currentVersion, s.message, error = true) { vm.check() }
                    UpdateState.Checking -> CheckRow(vm.currentVersion, "Checking…") {}
                    UpdateState.UpToDate -> CheckRow(vm.currentVersion, "You have the latest version.") { vm.check() }
                    UpdateState.Idle -> CheckRow(vm.currentVersion, "Tap to look for a newer version.") { vm.check() }
                }
                Divider()
                SwitchRow("Check automatically", "Once a day, when you open Tono", ui.autoCheck, vm::setAutoCheck)
            }
        }
    }
}

@Composable
private fun CheckRow(version: String, hint: String, error: Boolean = false, onClick: () -> Unit) {
    val c = Tono.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Check for updates", style = MaterialTheme.typography.titleSmall)
            Text("Version $version · $hint", style = MaterialTheme.typography.bodySmall, color = if (error) c.danger else c.muted)
        }
        TonoIcon(R.drawable.ic_arrow_right, tint = c.muted, size = 18.dp)
    }
}

@Composable
private fun ReleaseBlock(r: AppRelease, current: String, actions: @Composable () -> Unit) {
    val c = Tono.colors
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Version ${r.version} is available", style = MaterialTheme.typography.titleSmall)
            Text("You have $current", style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        val notes = plainNotes(r.notes)
        if (notes.isNotBlank()) {
            Text(notes, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 8, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Composable
private fun DownloadProgress(progress: Float, onCancel: () -> Unit) {
    val c = Tono.colors
    val pct = (progress * 100).roundToInt()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().height(8.dp).clip(Pill).background(c.off)
                .semantics { contentDescription = "Downloading, $pct percent" },
        ) { Box(Modifier.fillMaxHeight().fillMaxWidth(progress).clip(Pill).background(c.accent)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Downloading… $pct%", style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.weight(1f))
            Text(
                "Cancel", style = MaterialTheme.typography.labelLarge, color = c.accent,
                modifier = Modifier.clip(Pill).clickable(role = Role.Button, onClick = onCancel).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Hint(text: String, error: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (error) Tono.colors.danger else Tono.colors.muted)
}

private fun Long.sizeLabel(): String =
    if (this <= 0) "" else " · ${"%.0f".format(this / 1_048_576.0).let { if (it == "0") "<1" else it }} MB"

/** Release notes (the Changesets changelog entry) are Markdown; show them as plain lines. */
internal fun plainNotes(md: String): String =
    md.lines()
        .filterNot { it.startsWith("**Full Changelog**") || it.startsWith("<!--") }
        .map { line ->
            line.trim()
                .replace(Regex("^#+\\s*"), "")
                .replace(Regex("^[*-]\\s+"), "• ")
                // Changesets prefixes each entry with its commit: "- 1a2b3c4: Loop preview".
                .replace(Regex("^• \\[?[0-9a-f]{7,40}]?(\\([^)]*\\))?:\\s*"), "• ")
                .replace(Regex("\\s+by @\\S+ in https://\\S+$"), "")
                .replace("**", "")
        }
        .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
