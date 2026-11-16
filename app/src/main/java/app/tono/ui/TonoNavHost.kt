package app.tono.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.tono.R
import app.tono.container
import app.tono.ui.components.Divider
import app.tono.ui.components.Pill
import app.tono.ui.components.TonoIcon
import app.tono.ui.contacts.ContactPickerScreen
import app.tono.ui.contacts.ContactPickerViewModel
import app.tono.ui.filters.FiltersScreen
import app.tono.ui.tones.RotationScreen
import app.tono.ui.tones.RotationViewModel
import app.tono.ui.save.SaveScreen
import app.tono.ui.search.SearchScreen
import app.tono.ui.search.SearchViewModel
import app.tono.ui.settings.SettingsScreen
import app.tono.ui.settings.UpdateViewModel
import app.tono.ui.components.Dot
import androidx.compose.runtime.LaunchedEffect
import app.tono.ui.theme.ThemeMode
import app.tono.ui.theme.Tono
import app.tono.ui.tones.MyTonesScreen
import app.tono.ui.tones.MyTonesViewModel
import app.tono.ui.trim.EditorViewModel
import app.tono.ui.trim.TrimScreen
import app.tono.ui.song.SongScreen
import app.tono.ui.song.SongViewModel
import kotlinx.coroutines.launch

private object Routes {
    const val SEARCH = "search"
    const val FILTERS = "filters"
    const val FILTERS_FROM_SETTINGS = "filters_settings"
    const val SONG = "song"
    const val TRIM = "trim"
    const val SAVE = "save"
    const val TONES = "tones"
    const val SETTINGS = "settings"
    const val CONTACTS = "contacts"
    const val ROTATION = "rotation"
}

private const val TAB_MS = 180
private const val PAGE_MS = 320
private val tabRoutes = setOf(Routes.SEARCH, Routes.TONES, Routes.SETTINGS)

/** Bottom-bar tab presses (forward navigations between tabs). Back always uses the pop slide. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch() =
    initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes

/** Every destination gets an opaque background so sliding pages never show through each other. */
private fun NavGraphBuilder.screen(route: String, content: @Composable () -> Unit) =
    composable(route) { Box(Modifier.fillMaxSize().background(Tono.colors.bg)) { content() } }

private data class Tab(val route: String, val label: String, @DrawableRes val icon: Int)

private val tabs = listOf(
    Tab(Routes.SEARCH, "Search", R.drawable.ic_search),
    Tab(Routes.TONES, "My tones", R.drawable.ic_tones),
    Tab(Routes.SETTINGS, "Settings", R.drawable.ic_settings),
)

@Composable
fun TonoNavHost(themeMode: ThemeMode) {
    val context = LocalContext.current
    val container = context.container
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    // Activity-scoped so Trim and Save share one editor, and search survives tab switches.
    val searchVm: SearchViewModel = viewModel(factory = viewModelFactory { initializer { SearchViewModel(container) } })
    val editorVm: EditorViewModel = viewModel()
    val tonesVm: MyTonesViewModel = viewModel()
    val songVm: SongViewModel = viewModel()
    val pickerVm: ContactPickerViewModel = viewModel()
    val updatesVm: UpdateViewModel = viewModel()
    val updatesUi by updatesVm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { updatesVm.checkOnLaunch() }
    val editorUi by editorVm.ui.collectAsStateWithLifecycle()
    fun openSong(song: app.tono.data.Song) { songVm.open(song); nav.navigate(Routes.SONG) { launchSingleTop = true } }
    fun cutSong(song: app.tono.data.Song) { editorVm.open(song); nav.navigate(Routes.TRIM) { launchSingleTop = true } }
    val filters by searchVm.filters.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    fun switchTab(r: String) = nav.navigate(r) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    val c = Tono.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Box(Modifier.weight(1f).imePadding()) {
            NavHost(
                nav,
                startDestination = Routes.SEARCH,
                // Tabs cross-fade quickly; pages slide in from the right and slide back out on
                // back / swipe-back (also drives the predictive back gesture).
                enterTransition = {
                    if (isTabSwitch()) fadeIn(tween(TAB_MS)) else slideInHorizontally(tween(PAGE_MS, easing = FastOutSlowInEasing)) { it }
                },
                exitTransition = {
                    if (isTabSwitch()) fadeOut(tween(TAB_MS))
                    else slideOutHorizontally(tween(PAGE_MS, easing = FastOutSlowInEasing)) { -it / 4 } + fadeOut(tween(PAGE_MS), targetAlpha = 0.6f)
                },
                popEnterTransition = {
                    slideInHorizontally(tween(PAGE_MS, easing = FastOutSlowInEasing)) { -it / 4 } + fadeIn(tween(PAGE_MS), initialAlpha = 0.6f)
                },
                popExitTransition = {
                    slideOutHorizontally(tween(PAGE_MS, easing = FastOutSlowInEasing)) { it }
                },
                // Swipe-back gesture: the page tracks the finger (linear), then finishes the same slide
                // on release. Without these, Android's default shrink-to-card is used and then snaps away.
                predictivePopEnterTransition = { _ ->
                    slideInHorizontally(tween(PAGE_MS, easing = LinearEasing)) { -it / 4 } + fadeIn(tween(PAGE_MS), initialAlpha = 0.6f)
                },
                predictivePopExitTransition = { _ ->
                    slideOutHorizontally(tween(PAGE_MS, easing = LinearEasing)) { it }
                },
            ) {
                screen(Routes.SEARCH) {
                    SearchScreen(
                        searchVm,
                        onOpenSong = ::openSong,
                        onCutSong = ::cutSong,
                        onOpenFilters = { nav.navigate(Routes.FILTERS_FROM_SETTINGS) },
                    )
                }
                screen(Routes.FILTERS) {
                    FiltersScreen(
                        initial = filters,
                        onApply = { f -> searchVm.saveFilters(f); nav.popBackStack() },
                        onClose = { nav.popBackStack() },
                    )
                }
                // Same screen opened from Settings: it saves and returns there, no "Show results".
                screen(Routes.FILTERS_FROM_SETTINGS) {
                    FiltersScreen(
                        initial = filters,
                        onApply = { f -> searchVm.saveFilters(f); nav.popBackStack() },
                        onClose = { nav.popBackStack() },
                        applyLabel = { "Save" },
                    )
                }
                screen(Routes.SONG) {
                    SongScreen(songVm, editorUi, onBack = { nav.popBackStack() }, onMakeTone = ::cutSong)
                }
                screen(Routes.TRIM) {
                    TrimScreen(editorVm, onBack = { nav.popBackStack() }, onContinue = {
                        editorVm.resetSave(); nav.navigate(Routes.SAVE)
                    })
                }
                screen(Routes.SAVE) {
                    SaveScreen(
                        editorVm,
                        onBack = { nav.popBackStack() },
                        onDone = { editorVm.resetSave(); switchTab(Routes.SEARCH) },
                        onMyTones = { editorVm.resetSave(); switchTab(Routes.TONES) },
                    )
                }
                screen(Routes.TONES) {
                    MyTonesScreen(
                        tonesVm,
                        onOpenSong = ::openSong,
                        onPickContacts = { tone -> pickerVm.open(tone); nav.navigate(Routes.CONTACTS) { launchSingleTop = true } },
                        onOpenRotation = { nav.navigate(Routes.ROTATION) { launchSingleTop = true } },
                    )
                }
                screen(Routes.CONTACTS) {
                    ContactPickerScreen(
                        pickerVm,
                        onBack = { nav.popBackStack() },
                        onDone = { msg -> tonesVm.showMessage(msg); nav.popBackStack() },
                    )
                }
                screen(Routes.ROTATION) {
                    val rotationVm: RotationViewModel = viewModel()
                    RotationScreen(rotationVm, onBack = { nav.popBackStack() })
                }
                screen(Routes.SETTINGS) {
                    SettingsScreen(
                        theme = themeMode,
                        onTheme = { m -> scope.launch { container.prefs.setThemeMode(m) } },
                        onOpenFilters = { nav.navigate(Routes.FILTERS_FROM_SETTINGS) },
                        updates = updatesVm,
                    )
                }
            }
        }

        if (route in tabs.map { it.route }) {
            BottomBar(route, ::switchTab, badge = if (updatesUi.hasUpdate) Routes.SETTINGS else null)
        } else {
            // Keep content clear of the gesture/nav bar on full-screen flows.
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun BottomBar(current: String?, onSelect: (String) -> Unit, badge: String? = null) {
    val c = Tono.colors
    Column(Modifier.background(c.bg).windowInsetsPadding(WindowInsets.navigationBars)) {
        Divider()
        Row(Modifier.fillMaxWidth().height(76.dp).padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
            tabs.forEach { tab ->
                val selected = current == tab.route
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab.route) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    Box(
                        Modifier
                            .width(56.dp)
                            .height(30.dp)
                            .clip(Pill)
                            .background(if (selected) c.accentSoft else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        TonoIcon(tab.icon, tint = if (selected) c.accent else c.muted, size = 20.dp)
                        // "Update available" dot on the Settings tab.
                        if (badge == tab.route) {
                            Box(Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 13.dp)) { Dot(c.accent, size = 8.dp) }
                        }
                    }
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize * 0.92f),
                        color = if (selected) c.text else c.muted,
                    )
                }
            }
        }
    }
}
