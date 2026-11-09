package com.subscriptionoptimizer.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.subscriptionoptimizer.app.ui.assistant.AssistantRoute
import com.subscriptionoptimizer.app.ui.policy.PolicyRoute
import com.subscriptionoptimizer.app.ui.trace.TraceRoute
import com.subscriptionoptimizer.app.ui.compare.CompareRoute
import com.subscriptionoptimizer.app.ui.designsystem.components.isTextTight
import com.subscriptionoptimizer.app.ui.history.HistoryRoute
import com.subscriptionoptimizer.app.ui.home.HomeRoute
import com.subscriptionoptimizer.app.ui.optimize.OptimizeResultRoute
import com.subscriptionoptimizer.app.ui.optimize.OptimizeRoute
import com.subscriptionoptimizer.app.ui.trace.TraceViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.subscriptionoptimizer.app.ui.results.ResultsRoute
import com.subscriptionoptimizer.app.ui.settings.SettingsRoute

object Routes {
    const val HOME = "home"
    const val COMPARE = "compare"
    const val ASSISTANT = "assistant"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val RESULT_ID_ARG = "resultId"
    const val RESULTS = "results/{$RESULT_ID_ARG}"

    fun results(id: String) = "results/$id"

    const val TRACE = "trace/{$RESULT_ID_ARG}"
    fun trace(id: String) = "trace/$id"

    const val POLICY = "policy"

    /** Not a tab: opened from the Home card. */
    const val OPTIMIZE = "optimize"
    const val OPTIMIZE_RESULT = "optimize_result/{$RESULT_ID_ARG}"
    fun optimizeResult(id: String) = "optimize_result/$id"

    const val OPTIMIZE_TRACE = "optimize_trace/{$RESULT_ID_ARG}"
    fun optimizeTrace(id: String) = "optimize_trace/$id"
}

enum class TopLevelDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    HOME(Routes.HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home),
    COMPARE(Routes.COMPARE, "Compare", Icons.Filled.SwapHoriz, Icons.Outlined.SwapHoriz),
    ASSISTANT(Routes.ASSISTANT, "Assistant", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
    HISTORY(Routes.HISTORY, "History", Icons.Filled.History, Icons.Outlined.History),
    SETTINGS(Routes.SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
}

private fun NavDestination?.isOn(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.route == destination.route } == true

private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** App shell: bottom bar on phones, navigation rail from 600dp, hidden on detail screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showNavigation = TopLevelDestination.entries.any { currentDestination.isOn(it) }
    val useRail = LocalConfiguration.current.screenWidthDp >= 600
    val tightText = isTextTight()

    if (useRail) {
        Row(Modifier.fillMaxSize()) {
            if (showNavigation) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    TopLevelDestination.entries.forEach { destination ->
                        val selected = currentDestination.isOn(destination)
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navController.navigateToTopLevel(destination) },
                            icon = {
                                Icon(
                                    if (selected) destination.selectedIcon else destination.unselectedIcon,
                                    contentDescription = null,
                                )
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
            AppNavHost(navController, Modifier.weight(1f))
        }
    } else {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showNavigation) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        TopLevelDestination.entries.forEach { destination ->
                            val selected = currentDestination.isOn(destination)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navController.navigateToTopLevel(destination) },
                                icon = {
                                    Icon(
                                        if (selected) destination.selectedIcon else destination.unselectedIcon,
                                        // Hidden labels can't name the item, so the icon does.
                                        contentDescription = if (tightText) destination.label else null,
                                    )
                                },
                                // Five labels don't fit here, and clipped ones ("Comp", "Setti") read
                                // worse than icons, so the selected indicator marks the current tab.
                                label = if (tightText) null else { { Text(destination.label, maxLines = 1) } },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            AppNavHost(
                navController,
                Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding),
            )
        }
    }
}

@Composable
private fun AppNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    val openResult: (String) -> Unit = { id ->
        // launchSingleTop: a double tap on a history row opens one results screen, not two.
        navController.navigate(Routes.results(id)) { launchSingleTop = true }
    }
    val openOptimization: (String) -> Unit = { id ->
        navController.navigate(Routes.optimizeResult(id)) { launchSingleTop = true }
    }
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
        enterTransition = { fadeIn(tween(220)) },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(160)) },
    ) {
        composable(Routes.HOME) {
            HomeRoute(
                onNewComparison = { navController.navigateToTopLevel(TopLevelDestination.COMPARE) },
                onOpenAssistant = { navController.navigateToTopLevel(TopLevelDestination.ASSISTANT) },
                onOpenHistory = { navController.navigateToTopLevel(TopLevelDestination.HISTORY) },
                onOpenPolicy = { navController.navigate(Routes.POLICY) },
                onOpenResult = openResult,
                onOpenOptimize = { navController.navigate(Routes.OPTIMIZE) { launchSingleTop = true } },
            )
        }
        composable(Routes.COMPARE) {
            CompareRoute(
                onResult = openResult,
                onOpenSettings = { navController.navigateToTopLevel(TopLevelDestination.SETTINGS) },
            )
        }
        composable(Routes.ASSISTANT) { AssistantRoute() }
        composable(Routes.HISTORY) {
            HistoryRoute(
                onOpenResult = openResult,
                onOpenOptimization = openOptimization,
                onNewOptimization = { navController.navigate(Routes.OPTIMIZE) { launchSingleTop = true } },
                onNewComparison = { navController.navigateToTopLevel(TopLevelDestination.COMPARE) },
                onOpenSettings = { navController.navigateToTopLevel(TopLevelDestination.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) { SettingsRoute() }
        composable(
            route = Routes.RESULTS,
            arguments = listOf(navArgument(Routes.RESULT_ID_ARG) { type = NavType.StringType }),
            enterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(260)) + fadeIn()
            },
            popExitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(220)) + fadeOut()
            },
        ) {
            ResultsRoute(
                // dropUnlessResumed: a double tap on Back must not also pop the screen underneath,
                // which would throw away a filled-in comparison form.
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenTrace = { id -> navController.navigate(Routes.trace(id)) },
            )
        }
        composable(
            route = Routes.TRACE,
            arguments = listOf(navArgument(Routes.RESULT_ID_ARG) { type = NavType.StringType }),
        ) {
            TraceRoute(onBack = dropUnlessResumed { navController.popBackStack() })
        }
        composable(Routes.POLICY) { PolicyRoute(onBack = dropUnlessResumed { navController.popBackStack() }) }
        composable(Routes.OPTIMIZE) {
            OptimizeRoute(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onResult = openOptimization,
            )
        }
        composable(
            route = Routes.OPTIMIZE_RESULT,
            arguments = listOf(navArgument(Routes.RESULT_ID_ARG) { type = NavType.StringType }),
            enterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(260)) + fadeIn()
            },
            popExitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(220)) + fadeOut()
            },
        ) {
            OptimizeResultRoute(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenTrace = { id -> navController.navigate(Routes.optimizeTrace(id)) },
            )
        }
        composable(
            route = Routes.OPTIMIZE_TRACE,
            arguments = listOf(navArgument(Routes.RESULT_ID_ARG) { type = NavType.StringType }),
        ) {
            TraceRoute(
                onBack = dropUnlessResumed { navController.popBackStack() },
                viewModel = viewModel(factory = TraceViewModel.OptimizationFactory),
            )
        }
    }

    // System back on another tab goes Home the same way the Home tab does, saving that tab's state.
    // Popping it instead discarded its ViewModel, losing a half-filled comparison or chat draft.
    // Declared after NavHost so this callback is registered later and takes precedence over its own.
    val destination = navController.currentBackStackEntryAsState().value?.destination
    val onOtherTab = TopLevelDestination.entries.any { destination.isOn(it) } &&
        !destination.isOn(TopLevelDestination.HOME)
    BackHandler(enabled = onOtherTab) { navController.navigateToTopLevel(TopLevelDestination.HOME) }
}
