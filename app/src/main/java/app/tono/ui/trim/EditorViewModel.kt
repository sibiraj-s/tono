package app.tono.ui.trim

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.tono.audio.AudioMath
import app.tono.audio.ExportSpec
import app.tono.audio.PickedContact
import app.tono.audio.Waveform
import app.tono.container
import app.tono.data.SearchFilters
import app.tono.data.Song
import app.tono.data.SongSource
import app.tono.data.ToneUse
import app.tono.data.toneName
import app.tono.ui.components.RingtoneAudioAttributes
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
import java.io.File

sealed interface LoadState {
    /** [progress] 0..1, or null when indeterminate. */
    data class Loading(val stage: String, val progress: Float?) : LoadState
    data object Ready : LoadState
    data class Failed(val message: String) : LoadState
}

sealed interface SaveState {
    data object Idle : SaveState
    data class Working(val stage: String) : SaveState
    /** Saved to the phone; waiting for "Modify system settings" to set it as default. */
    data class NeedsPermission(val uri: Uri) : SaveState
    data class Done(val use: ToneUse, val setAsDefault: Boolean, val contactName: String? = null) : SaveState
    data class Failed(val message: String) : SaveState
}

data class EditorUi(
    val song: Song? = null,
    val load: LoadState = LoadState.Loading("Getting ready…", null),
    val waveform: Waveform? = null,
    val durationMs: Long = 0,
    val startMs: Long = 0,
    val endMs: Long = 0,
    val fadeIn: Boolean = true,
    val fadeOut: Boolean = true,
    val playing: Boolean = false,
    /** Preview repeats the selection, like a ringtone does until you answer. */
    val loop: Boolean = false,
    /** The pretend incoming-call screen is open (plays through the ringtone channel, looping). */
    val callPreview: Boolean = false,
    val playheadMs: Long = 0,
    /** Visible range of the zoomed waveform. */
    val windowStartMs: Long = 0,
    val windowLenMs: Long = 0,
    // Save screen
    val name: String = "",
    val use: ToneUse = ToneUse.Ringtone,
    /** "Contact" option selected instead of a system-wide [use]. */
    val forContact: Boolean = false,
    val contact: PickedContact? = null,
    val save: SaveState = SaveState.Idle,
) {
    val clipMs: Long get() = endMs - startMs
    val repeats: Boolean get() = loop || callPreview
    val showPlayhead: Boolean get() = playing || (playheadMs > startMs && playheadMs < endMs)
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val c = app.container
    private val _ui = MutableStateFlow(EditorUi())
    val ui: StateFlow<EditorUi> = _ui.asStateFlow()

    private var audioFile: File? = null
    private var filters = SearchFilters()
    private var loadJob: Job? = null
    private var tickJob: Job? = null
    private var player: ExoPlayer? = null

    fun open(song: Song) {
        val same = _ui.value.song?.url == song.url && _ui.value.load !is LoadState.Failed
        // Same song still loading, or loaded and its audio file still exists: keep everything.
        if (same && (_ui.value.load is LoadState.Loading || audioFile?.exists() == true)) return
        loadJob?.cancel()
        stopPlayback()
        audioFile = null
        stopCallPreview()
        _ui.value = EditorUi(song = song, name = toneName(song.title), loop = _ui.value.loop)
        loadJob = viewModelScope.launch { load(song) }
    }

    fun retry() {
        val song = _ui.value.song ?: return
        loadJob?.cancel()
        _ui.update { it.copy(load = LoadState.Loading("Getting ready…", null)) }
        loadJob = viewModelScope.launch { load(song) }
    }

    private suspend fun load(song: Song) {
        try {
            filters = c.prefs.filters.first()
            val file = if (song.source == SongSource.Device && song.localUri != null) {
                setLoading("Opening file…", null)
                c.localAudio.copyToCache(song.localUri)
            } else {
                setLoading("Downloading audio…", 0f)
                c.youTube.downloadAudio(song, filters) { p -> setLoading("Downloading audio…", p.takeIf { it >= 0 }) }.file
            }
            setLoading("Reading waveform…", 0f)
            val wf = Waveform.analyze(file) { p -> setLoading("Reading waveform…", p) }
            audioFile = file

            val duration = wf.durationMs
            val clip = (filters.clipSeconds * 1000L).coerceAtMost(duration)
            val start = if (filters.startAtChorus) {
                AudioMath.loudestWindowStartMs(wf.rms, wf.bucketMs, clip, duration)
            } else 0L
            _ui.update {
                it.copy(
                    load = LoadState.Ready, waveform = wf, durationMs = duration,
                    startMs = start, endMs = start + clip, playheadMs = start,
                ).recentered(force = true)
            }
            preparePlayer(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _ui.update { it.copy(load = LoadState.Failed(e.userMessage())) }
        }
    }

    private fun setLoading(stage: String, p: Float?) = _ui.update { it.copy(load = LoadState.Loading(stage, p)) }

    // ---------- Selection ----------

    /** Drag of the start handle; stays inside the visible window and keeps the clip 1–60 s. */
    fun setStart(ms: Long) = _ui.update { s ->
        val lo = maxOf(s.windowStartMs, s.endMs - MAX_CLIP_MS, 0L)
        val hi = s.endMs - MIN_CLIP_MS
        s.copy(startMs = ms.coerceIn(lo, maxOf(lo, hi)))
    }

    fun setEnd(ms: Long) = _ui.update { s ->
        val lo = s.startMs + MIN_CLIP_MS
        val hi = minOf(s.windowStartMs + s.windowLenMs, s.startMs + MAX_CLIP_MS, s.durationMs)
        s.copy(endMs = ms.coerceIn(lo, maxOf(lo, hi)))
    }

    /** Slide the whole selection, keeping its length. */
    fun moveBy(deltaMs: Long) = _ui.update { s ->
        val len = s.clipMs
        val start = (s.startMs + deltaMs).coerceIn(0L, maxOf(0L, s.durationMs - len))
        s.copy(startMs = start, endMs = start + len)
    }

    /** Overview strip: centre the selection on [ms]. */
    fun centerSelectionAt(ms: Long) = _ui.update { s ->
        val len = s.clipMs
        val start = (ms - len / 2).coerceIn(0L, maxOf(0L, s.durationMs - len))
        s.copy(startMs = start, endMs = start + len).recentered(force = true)
    }

    // The ranges below can be empty for very short audio (under MIN_CLIP_MS); maxOf keeps them valid.
    fun nudgeStart(deltaMs: Long) = _ui.update { s ->
        val lo = maxOf(0L, s.endMs - MAX_CLIP_MS)
        val hi = maxOf(lo, s.endMs - MIN_CLIP_MS)
        s.copy(startMs = (s.startMs + deltaMs).coerceIn(lo, hi)).recentered()
    }

    fun nudgeEnd(deltaMs: Long) = _ui.update { s ->
        val lo = minOf(s.startMs + MIN_CLIP_MS, s.durationMs)
        val hi = maxOf(lo, minOf(s.durationMs, s.startMs + MAX_CLIP_MS))
        s.copy(endMs = (s.endMs + deltaMs).coerceIn(lo, hi)).recentered()
    }

    /** After a drag ends, re-frame the window if the selection hugs an edge. */
    fun endDrag() = _ui.update { it.recentered() }

    fun setFadeIn(on: Boolean) = _ui.update { it.copy(fadeIn = on) }
    fun setFadeOut(on: Boolean) = _ui.update { it.copy(fadeOut = on) }

    private fun EditorUi.recentered(force: Boolean = false): EditorUi {
        if (durationMs <= 0) return this
        val len = minOf(durationMs, maxOf(WINDOW_MS, clipMs * 2))
        val margin = len / 10
        val inside = startMs >= windowStartMs + margin && endMs <= windowStartMs + windowLenMs - margin
        if (!force && inside && windowLenMs == len) return this
        val center = (startMs + endMs) / 2
        val ws = (center - len / 2).coerceIn(0L, maxOf(0L, durationMs - len))
        return copy(windowStartMs = ws, windowLenMs = len)
    }

    // ---------- Preview playback ----------

    private fun preparePlayer(file: File) {
        val p = player ?: ExoPlayer.Builder(getApplication()).build().also { created ->
            created.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    // The clip ran to the very end of the song.
                    if (playbackState == Player.STATE_ENDED && _ui.value.playing) {
                        if (_ui.value.repeats) startFrom(_ui.value.startMs) else stopPlayback()
                    }
                }
            })
            player = created
        }
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        p.prepare()
    }

    /** Play/pause. Resumes from the playhead if it's inside the clip, else from the start. */
    fun togglePlay() {
        if (player == null) return
        val s = _ui.value
        if (s.playing) { pausePlayback(); return }
        startFrom(if (s.playheadMs in s.startMs until s.endMs - 250) s.playheadMs else s.startMs)
    }

    /**
     * Tap or scrub on the waveform. While playing it jumps there; when paused it starts
     * playing from there if [play], otherwise just moves the playhead.
     */
    fun seekTo(ms: Long, play: Boolean) {
        val s = _ui.value
        val t = ms.coerceIn(s.startMs, maxOf(s.startMs, s.endMs - 100))
        when {
            s.playing -> { player?.seekTo(t); _ui.update { it.copy(playheadMs = t) } }
            play -> startFrom(t)
            else -> _ui.update { it.copy(playheadMs = t) }
        }
    }

    private fun startFrom(ms: Long) {
        val p = player ?: return
        p.seekTo(ms)
        p.volume = 0f
        p.play()
        _ui.update { it.copy(playing = true, playheadMs = ms) }
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            val gain = previewGain()
            while (isActive) {
                val cur = _ui.value
                var pos = p.currentPosition
                if (pos >= cur.endMs) {
                    if (!cur.repeats) { stopPlayback(); break }
                    p.seekTo(cur.startMs); pos = cur.startMs // straight back to the start, as a ringtone loops
                }
                if (pos < cur.startMs) { p.seekTo(cur.startMs); pos = cur.startMs } // clip start moved past us
                // Preview fades with player volume (gain above 1 can't be previewed).
                val m = AudioMath.fadeMultiplier(
                    pos - cur.startMs, cur.clipMs,
                    if (cur.fadeIn) FADE_IN_MS else 0, if (cur.fadeOut) FADE_OUT_MS else 0,
                )
                p.volume = (m * gain).coerceIn(0f, 1f)
                _ui.update { it.copy(playheadMs = pos) }
                delay(30)
            }
        }
    }

    fun toggleLoop() = _ui.update { it.copy(loop = !it.loop) }

    /** Opens the pretend call: the selection rings at ring volume, on repeat, until closed. */
    fun startCallPreview() {
        val p = player ?: return
        pausePlayback()
        p.setAudioAttributes(RingtoneAudioAttributes, false)
        _ui.update { it.copy(callPreview = true) }
        startFrom(_ui.value.startMs)
    }

    fun stopCallPreview() {
        if (!_ui.value.callPreview) return
        stopPlayback()
        player?.setAudioAttributes(AudioAttributes.DEFAULT, false)
        _ui.update { it.copy(callPreview = false) }
    }

    /** Pause where we are; the playhead stays put. */
    fun pausePlayback() {
        tickJob?.cancel()
        player?.pause()
        _ui.update { it.copy(playing = false) }
    }

    /** Stop and rewind the playhead to the clip start. */
    fun stopPlayback() {
        tickJob?.cancel()
        player?.pause()
        _ui.update { it.copy(playing = false, playheadMs = it.startMs) }
    }

    private fun previewGain(): Float = minOf(1f, levelingGain())

    private fun levelingGain(): Float {
        val s = _ui.value
        val wf = s.waveform ?: return 1f
        if (!filters.volumeLeveling) return 1f
        return AudioMath.levelingGain(wf.peak, wf.rms, wf.indexAt(s.startMs), wf.indexAt(s.endMs) + 1)
    }

    // ---------- Save ----------

    fun setName(n: String) = _ui.update { it.copy(name = n) }
    fun setUse(u: ToneUse) = _ui.update { it.copy(use = u, forContact = false) }

    /** Result of the system contact picker; null when the user backed out. */
    fun onContactPicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            runCatching { c.toneStore.readContact(uri) ?: error("Couldn't read that contact.") }
                .onSuccess { contact -> _ui.update { it.copy(forContact = true, contact = contact, save = SaveState.Idle) } }
                .onFailure { e -> _ui.update { it.copy(save = SaveState.Failed(e.userMessage())) } }
        }
    }

    fun selectContactTarget() = _ui.update { it.copy(forContact = true) }

    fun contactsPermissionDenied() = _ui.update {
        it.copy(save = SaveState.Failed("Tono needs contacts access to set a ringtone for one person."))
    }

    fun hasContactsPermission() = c.toneStore.hasContactsPermission()
    fun resetSave() = _ui.update { it.copy(save = SaveState.Idle) }

    fun save() {
        val input = audioFile ?: return
        val s = _ui.value
        if (s.save is SaveState.Working) return
        stopPlayback()
        viewModelScope.launch {
            try {
                _ui.update { it.copy(save = SaveState.Working("Cutting…")) }
                val spec = ExportSpec(
                    startMs = s.startMs, endMs = s.endMs,
                    fadeInMs = if (s.fadeIn) FADE_IN_MS else 0,
                    fadeOutMs = if (s.fadeOut) FADE_OUT_MS else 0,
                    gain = levelingGain(),
                )
                val out = File(getApplication<Application>().cacheDir, "export/tone.m4a")
                c.exporter.export(input, spec, out)

                _ui.update { it.copy(save = SaveState.Working("Saving…")) }
                val contact = s.contact.takeIf { s.forContact }
                val title = c.toneStore.uniqueTitle(s.name.ifBlank { toneName(s.song?.title ?: "Tono tone") })
                _ui.update { it.copy(name = title) }
                val uri = c.toneStore.save(
                    out, title,
                    if (contact != null) ToneUse.Ringtone else s.use,
                    artUrl = s.song?.thumbnailUrl,
                )
                out.delete()

                if (contact != null) {
                    try {
                        c.toneStore.setForContact(contact.id, uri)
                    } catch (e: Exception) {
                        // Don't leave an unused copy behind; a retry would add "Name 2", "Name 3"…
                        runCatching { c.toneStore.delete(uri) }
                        throw e
                    }
                    _ui.update { it.copy(save = SaveState.Done(ToneUse.Ringtone, setAsDefault = true, contactName = contact.name)) }
                    return@launch
                }

                if (c.toneStore.canChangeSystemSounds()) {
                    c.toneStore.setAsDefault(uri, s.use)
                    _ui.update { it.copy(save = SaveState.Done(s.use, setAsDefault = true)) }
                } else {
                    _ui.update { it.copy(save = SaveState.NeedsPermission(uri)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(save = SaveState.Failed(e.userMessage())) }
            }
        }
    }

    /** Called when the user comes back from the "Modify system settings" screen. */
    fun onPermissionResult() {
        val pending = _ui.value.save as? SaveState.NeedsPermission ?: return
        val use = _ui.value.use
        viewModelScope.launch {
            if (c.toneStore.canChangeSystemSounds()) {
                runCatching { c.toneStore.setAsDefault(pending.uri, use) }
                    .onSuccess { _ui.update { it.copy(save = SaveState.Done(use, setAsDefault = true)) } }
                    .onFailure { e -> _ui.update { it.copy(save = SaveState.Failed(e.userMessage())) } }
            } else {
                _ui.update { it.copy(save = SaveState.Done(use, setAsDefault = false)) }
            }
        }
    }

    fun manageWriteSettingsIntent() = c.toneStore.manageWriteSettingsIntent()

    override fun onCleared() {
        player?.release()
        player = null
    }

    companion object {
        const val MIN_CLIP_MS = 1_000L
        const val MAX_CLIP_MS = 60_000L
        const val WINDOW_MS = 60_000L
        const val NUDGE_MS = 500L
        const val FADE_IN_MS = 1_000L
        const val FADE_OUT_MS = 2_000L
    }
}
