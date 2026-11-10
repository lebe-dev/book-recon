package ru.tinyops.android.bookrecon.ui.nav

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.first
import ru.tinyops.android.bookrecon.R
import ru.tinyops.android.bookrecon.di.appContainer
import ru.tinyops.android.bookrecon.ui.book.FlibustaBookScreen
import ru.tinyops.android.bookrecon.ui.downloads.DownloadsScreen
import ru.tinyops.android.bookrecon.ui.library.LibraryScreen
import ru.tinyops.android.bookrecon.ui.log.LogScreen
import ru.tinyops.android.bookrecon.ui.search.SearchScreen
import ru.tinyops.android.bookrecon.ui.settings.SettingsScreen
import ru.tinyops.android.bookrecon.ui.settings.SettingsSection
import ru.tinyops.android.bookrecon.ui.settings.SettingsSectionScreen
import ru.tinyops.android.bookrecon.ui.topic.RuTrackerTopicScreen
import ru.tinyops.android.bookrecon.ui.web.WebVerificationScreen

private enum class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    @param:StringRes val label: Int,
    val icon: ImageVector,
) {
    SEARCH(SearchRoute, SearchRoute::class, R.string.nav_search, Icons.Filled.Search),
    DOWNLOADS(DownloadsRoute, DownloadsRoute::class, R.string.nav_downloads, Icons.Filled.Download),
    LIBRARY(LibraryRoute, LibraryRoute::class, R.string.nav_library, Icons.AutoMirrored.Filled.LibraryBooks),
    SETTINGS(SettingsRoute, SettingsRoute::class, R.string.nav_settings, Icons.Filled.Settings),
}

@Composable
fun BookReconNavHost(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val selected = TopLevelDestination.entries.firstOrNull { destination.isOn(it) }
    OpenSettingsOnFirstRun(navController)

    Scaffold(
        bottomBar = {
            if (selected == null) return@Scaffold
            NavigationBar {
                TopLevelDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = item == selected,
                        onClick = { navController.navigateToTopLevel(item.route) },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = SearchRoute,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable<SearchRoute> { entry ->
                val verificationResults = remember(entry) { entry.savedStateHandle.verificationResults() }
                SearchScreen(
                    onOpenFlibustaBook = { bookId -> navController.navigate(FlibustaBookRoute(bookId)) },
                    onOpenRuTrackerTopic = { topicId, kind, title ->
                        navController.navigate(RuTrackerTopicRoute(topicId, kind, title))
                    },
                    onOpenVerification = { url, purpose -> navController.navigate(WebVerificationRoute(url, purpose)) },
                    onOpenSettings = { navController.navigateToTopLevel(SettingsRoute) },
                    verificationResults = verificationResults,
                )
            }
            composable<FlibustaBookRoute> { entry ->
                val route = entry.toRoute<FlibustaBookRoute>()
                val verificationResults = remember(entry) { entry.savedStateHandle.verificationResults() }
                FlibustaBookScreen(
                    bookId = route.bookId,
                    onBack = { navController.popBackStack() },
                    onOpenDownloads = { navController.navigateToTopLevel(DownloadsRoute) },
                    onOpenSettings = { navController.navigateToTopLevel(SettingsRoute) },
                    onOpenVerification = { url, purpose -> navController.navigate(WebVerificationRoute(url, purpose)) },
                    verificationResults = verificationResults,
                )
            }
            composable<RuTrackerTopicRoute> { entry ->
                val route = entry.toRoute<RuTrackerTopicRoute>()
                val verificationResults = remember(entry) { entry.savedStateHandle.verificationResults() }
                RuTrackerTopicScreen(
                    topicId = route.topicId,
                    kind = route.kind,
                    title = route.title,
                    onBack = { navController.popBackStack() },
                    onOpenDownloads = { navController.navigateToTopLevel(DownloadsRoute) },
                    onOpenSettings = { navController.navigateToTopLevel(SettingsRoute) },
                    onOpenVerification = { url, purpose -> navController.navigate(WebVerificationRoute(url, purpose)) },
                    verificationResults = verificationResults,
                )
            }
            composable<DownloadsRoute> {
                DownloadsScreen(onOpenSettings = { navController.navigateToTopLevel(SettingsRoute) })
            }
            composable<LibraryRoute> {
                LibraryScreen(onOpenSearch = { navController.navigateToTopLevel(SearchRoute) })
            }
            composable<SettingsRoute> {
                SettingsScreen(onOpenSection = { section -> navController.navigate(SettingsSectionRoute(section)) })
            }
            composable<SettingsSectionRoute> { entry ->
                val route = entry.toRoute<SettingsSectionRoute>()
                val verificationResults = remember(entry) { entry.savedStateHandle.verificationResults() }
                SettingsSectionScreen(
                    section = route.section,
                    onBack = { navController.popBackStack() },
                    onOpenVerification = { url, purpose -> navController.navigate(WebVerificationRoute(url, purpose)) },
                    onOpenLog = { navController.navigate(LogRoute) },
                    verificationResults = verificationResults,
                )
            }
            composable<LogRoute> {
                LogScreen(onBack = { navController.popBackStack() })
            }
            composable<WebVerificationRoute> { entry ->
                val route = entry.toRoute<WebVerificationRoute>()
                WebVerificationScreen(
                    url = route.url,
                    purpose = route.purpose,
                    onDone = { passed ->
                        navController.previousBackStackEntry?.savedStateHandle?.set(VERIFICATION_RESULT_KEY, passed)
                        navController.popBackStack()
                    },
                )
            }
        }
    }
}

/**
 * Opens the settings tab once per launch while storage is not set up, so a fresh install
 * starts where file access and root folders are granted instead of failing on first download.
 */
@Composable
private fun OpenSettingsOnFirstRun(navController: NavHostController) {
    val context = LocalContext.current
    var checked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (checked) return@LaunchedEffect
        checked = true
        val module = context.appContainer.settings
        val settings = module.settingsRepository.settings.first()
        if (!needsStorageSetup(module.fileStorage.hasAllFilesAccess(), settings)) return@LaunchedEffect
        context.appContainer.log.appLog.info(TAG, "Storage is not set up, opening settings")
        navController.navigateToTopLevel(SettingsRoute)
        navController.navigate(SettingsSectionRoute(SettingsSection.STORAGE))
    }
}

private const val TAG = "Navigation"

private fun NavDestination?.isOn(item: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(item.routeClass) } == true

private fun NavHostController.navigateToTopLevel(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
