package app.tono.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tono.AppContainer
import app.tono.data.SearchFilters
import app.tono.data.SearchPage
import app.tono.data.Song
import app.tono.data.applyFilters
import app.tono.ui.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SourceTab(val label: String) { YouTube("YouTube"), Device("On device") }

sealed interface ResultsState {
    data object Idle : ResultsState
    data object Loading : ResultsState
    data class Loaded(val songs: List<Song>, val hasMore: Boolean, val loadingMore: Boolean) : ResultsState
    data class Failed(val message: String) : ResultsState
}

data class SearchUi(
    val tab: SourceTab = SourceTab.YouTube,
    val query: String = "",
    val results: ResultsState = ResultsState.Idle,
)

class SearchViewModel(private val c: AppContainer) : ViewModel() {

    val filters: StateFlow<SearchFilters> =
        c.prefs.filters.stateIn(viewModelScope, SharingStarted.Eagerly, SearchFilters())

    val recent: StateFlow<List<String>> =
        c.prefs.recentSearches.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _ui = MutableStateFlow(SearchUi())
    val ui: StateFlow<SearchUi> = _ui.asStateFlow()

    /** Songs ready to open in the trimmer (picked files). */
    private val _open = Channel<Song>(Channel.BUFFERED)
    val open = _open.receiveAsFlow()

    // Everything fetched so far, before client-side filtering.
    private var raw = emptyList<Song>()
    private var lastPage: SearchPage? = null
    private var searchJob: Job? = null

    init {
        // Re-run the search when the server-side part of the filters (songs vs. videos, language)
        // changes, and re-filter locally for everything else.
        viewModelScope.launch {
            filters.drop(1).map { it.songsOnly to it.language }.distinctUntilChanged().collect {
                if (_ui.value.query.isNotBlank()) search()
            }
        }
        viewModelScope.launch {
            filters.drop(1).collect { refilter() }
        }
    }

    fun setTab(tab: SourceTab) = _ui.update { it.copy(tab = tab) }
    fun setQuery(q: String) = _ui.update { it.copy(query = q) }
    fun clearQuery() {
        searchJob?.cancel()
        raw = emptyList(); lastPage = null
        _ui.update { it.copy(query = "", results = ResultsState.Idle) }
    }

    fun search() {
        val q = _ui.value.query.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        viewModelScope.launch { c.prefs.addRecentSearch(q) }
        searchJob = viewModelScope.launch {
            _ui.update { it.copy(results = ResultsState.Loading) }
            try {
                val page = c.youTube.search(q, filters.value)
                raw = page.songs
                lastPage = page
                refilter()
                topUp()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _ui.update { it.copy(results = ResultsState.Failed(e.userMessage())) }
            }
        }
    }

    /** Called when the list nears its end. */
    fun loadMore() {
        val page = lastPage ?: return
        val state = _ui.value.results as? ResultsState.Loaded ?: return
        if (!page.hasMore || state.loadingMore || searchJob?.isActive == true) return
        searchJob = viewModelScope.launch { fetchNext() }
    }

    private suspend fun fetchNext() {
        val page = lastPage ?: return
        _ui.update { s -> s.copy(results = (s.results as? ResultsState.Loaded)?.copy(loadingMore = true) ?: s.results) }
        try {
            val next = c.youTube.more(page)
            lastPage = next
            raw = (raw + next.songs).distinctBy { it.url }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            lastPage = SearchPage(emptyList(), page.query, null, page.songsOnly) // stop paging
        }
        refilter()
    }

    /** Strict filters can empty a page; fetch a couple more so the list isn't bare. */
    private suspend fun topUp() {
        var extra = 0
        while (extra < 2 && (lastPage?.hasMore == true) &&
            ((_ui.value.results as? ResultsState.Loaded)?.songs?.size ?: 0) < 8
        ) {
            fetchNext(); extra++
        }
    }

    private fun refilter() {
        if (lastPage == null) return
        val shown = applyFilters(raw, filters.value, System.currentTimeMillis() / 1000)
        _ui.update {
            it.copy(results = ResultsState.Loaded(shown, hasMore = lastPage?.hasMore == true, loadingMore = false))
        }
    }

    fun openLocal(uri: android.net.Uri) {
        viewModelScope.launch {
            runCatching { c.localAudio.describe(uri) }.onSuccess { _open.send(it) }
        }
    }

    fun searchFor(q: String) {
        setQuery(q)
        search()
    }

    fun removeRecent(q: String) { viewModelScope.launch { c.prefs.removeRecentSearch(q) } }
    fun clearRecent() { viewModelScope.launch { c.prefs.clearRecentSearches() } }

    /** Tapping an active-filter chip turns that filter off. */
    fun removeFilter(chip: app.tono.data.ActiveChip) = saveFilters(chip.clear(filters.value))

    fun saveFilters(f: SearchFilters) {
        viewModelScope.launch { c.prefs.saveFilters(f) }
    }
}
