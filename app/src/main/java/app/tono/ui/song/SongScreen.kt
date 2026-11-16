@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.tono.ui.song

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import android.app.Application
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.tono.R
import app.tono.container
import app.tono.data.NewPipeDownloader
import app.tono.data.Song
import app.tono.data.formatMs
import app.tono.ui.components.PrimaryButton
import app.tono.ui.components.Radius24
import app.tono.ui.components.TonoIcon
import app.tono.ui.components.TopBar
import app.tono.ui.theme.Tono
import app.tono.ui.trim.EditorUi
import app.tono.ui.trim.LoadState
import app.tono.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SongUi(
    val song: Song? = null,
    val preparing: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
)

/** Plays a song before you decide to cut it: streams it, or uses the download if we have one. */
class SongViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val _ui = MutableStateFlow(SongUi())
    val ui: StateFlow<SongUi> = _ui.asStateFlow()

    private var player: ExoPlayer? = null
    private var tick: Job? = null
    private var prepareJob: Job? = null

    fun open(song: Song) {
        if (_ui.value.song?.url == song.url) return
        release()
        _ui.value = SongUi(song = song, durationMs = song.durationSec * 1000)
    }

    fun togglePlay() {
        val p = player
        if (p != null) {
            if (_ui.value.playing) pause() else { p.play(); _ui.update { it.copy(playing = true) }; startTick() }
            return
        }
        if (prepareJob?.isActive == true) return
        val song = _ui.value.song ?: return
        prepareJob = viewModelScope.launch {
            _ui.update { it.copy(preparing = true, error = null) }
            try {
                val local = c.youTube.cachedFile(song)
                val item: MediaItem
                var userAgent = NewPipeDownloader.BROWSER_USER_AGENT
                if (local != null) {
                    item = MediaItem.fromUri(Uri.fromFile(local))
                } else {
                    val src = c.youTube.resolveStream(song, c.prefs.filters.first())
                    userAgent = src.userAgent
                    item = MediaItem.fromUri(src.url)
                    _ui.update { it.copy(song = src.song, durationMs = src.song.durationSec * 1000) }
                }
                val http = DefaultHttpDataSource.Factory().setUserAgent(userAgent).setAllowCrossProtocolRedirects(true)
                val exo = ExoPlayer.Builder(getApplication())
                    .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(getApplication(), http)))
                    .build()
                exo.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_READY -> _ui.update {
                                it.copy(preparing = false, durationMs = exo.duration.takeIf { d -> d > 0 } ?: it.durationMs)
                            }
                            Player.STATE_ENDED -> {
                                exo.pause(); exo.seekTo(0)
                                tick?.cancel()
                                _ui.update { it.copy(playing = false, positionMs = 0) }
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        _ui.update { it.copy(preparing = false, playing = false, error = "Couldn't play this song. Try again.") }
                        release()
                    }
                })
                exo.setMediaItem(item)
                exo.prepare()
                exo.play()
                player = exo
                _ui.update { it.copy(playing = true) }
                startTick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(preparing = false, error = e.userMessage()) }
            }
        }
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms)
        _ui.update { it.copy(positionMs = ms) }
    }

    fun pause() {
        player?.pause()
        tick?.cancel()
        _ui.update { it.copy(playing = false) }
    }

    private fun startTick() {
        tick?.cancel()
        tick = viewModelScope.launch {
            while (isActive) {
                val p = player ?: break
                _ui.update { it.copy(positionMs = p.currentPosition) }
                delay(200)
            }
        }
    }

    private fun release() {
        prepareJob?.cancel()
        tick?.cancel()
        player?.release()
        player = null
    }

    override fun onCleared() = release()
}

/**
 * Song page: album art, play/pause with a seek bar, and "Make a tone". The download and
 * processing only start from that button, and their progress shows here too.
 */
@Composable
fun SongScreen(
    vm: SongViewModel,
    editor: EditorUi,
    onBack: () -> Unit,
    onMakeTone: (Song) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val c = Tono.colors
    val song = ui.song ?: return
    DisposableEffect(Unit) { onDispose { vm.pause() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pause() }

    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    Column(Modifier.fillMaxSize()) {
        TopBar("", R.drawable.ic_back, "Back", onBack)

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.widthIn(max = 340.dp).fillMaxWidth().aspectRatio(1f).clip(Radius24).background(c.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                TonoIcon(R.drawable.ic_music, tint = c.accent, size = 64.dp)
                song.thumbnailUrl?.let { url ->
                    coil3.compose.AsyncImage(
                        model = largeArt(url),
                        contentDescription = "Album art for ${song.title}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(
                song.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(song.artist, style = MaterialTheme.typography.bodyLarge, color = c.muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))

            // Seek bar + times
            val duration = ui.durationMs.coerceAtLeast(1)
            val shown = if (dragging) dragValue else ui.positionMs.toFloat()
            Slider(
                value = shown.coerceIn(0f, duration.toFloat()),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = { vm.seekTo(dragValue.toLong()); dragging = false },
                valueRange = 0f..duration.toFloat(),
                enabled = ui.playing || ui.positionMs > 0,
                colors = SliderDefaults.colors(
                    thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.raised,
                    disabledThumbColor = c.off, disabledInactiveTrackColor = c.raised,
                ),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Seek" },
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatMs(shown.toLong()), style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.weight(1f))
                Text(formatMs(ui.durationMs), style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            Spacer(Modifier.height(12.dp))

            // Play / pause
            val label = if (ui.playing) "Pause" else "Play"
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(c.text)
                    .clickable(role = Role.Button, onClickLabel = label, onClick = vm::togglePlay)
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                if (ui.preparing) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = c.bg, strokeWidth = 3.dp)
                } else {
                    TonoIcon(if (ui.playing) R.drawable.ic_pause else R.drawable.ic_play, tint = c.bg, size = 28.dp)
                }
            }
            ui.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
        }

        // "Make a tone" also shows progress if the trimmer is already preparing this song.
        val mine = editor.song?.url == song.url
        val load = editor.load
        val label = when {
            !mine -> "Make a tone"
            load is LoadState.Loading -> load.stage.removeSuffix("…") + (load.progress?.let { " · ${(it * 100).toInt()}%" } ?: "…")
            load is LoadState.Ready -> "Continue editing"
            else -> "Make a tone"
        }
        PrimaryButton(
            label,
            { vm.pause(); onMakeTone(ui.song ?: song) },
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
            trailingIcon = R.drawable.ic_scissors,
        )
    }
}

/** YouTube Music art URLs end in "=w120-h120-…"; ask for a bigger size for the page. */
private fun largeArt(url: String): String =
    url.replace(Regex("""=w\d+-h\d+"""), "=w720-h720")
