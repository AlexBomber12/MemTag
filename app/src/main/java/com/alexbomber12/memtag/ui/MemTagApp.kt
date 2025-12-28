@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.alexbomber12.memtag.app.AppContainer
import com.alexbomber12.memtag.app.AppViewModelFactory
import com.alexbomber12.memtag.data.settings.AppSettings
import com.alexbomber12.memtag.ui.navigation.AppBottomBar
import com.alexbomber12.memtag.ui.navigation.AppDestinations
import com.alexbomber12.memtag.ui.navigation.AppNavHost
import com.alexbomber12.memtag.ui.navigation.AppTopBar
import com.alexbomber12.memtag.ui.navigation.navigateToTopLevel

@Composable
fun MemTagApp(appContainer: AppContainer) {
    val navController = rememberNavController()
    val settings by
        appContainer.settingsStore.settingsFlow.collectAsStateWithLifecycle(
            initialValue = AppSettings(),
        )
    val showDiagnosticsTab = settings.showDiagnosticsTab
    val destinations = AppDestinations.topLevelDestinations(showDiagnosticsTab)
    val viewModelFactory = remember(appContainer) { AppViewModelFactory(appContainer) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentRoot = currentRoute?.substringBefore("?")?.substringBefore("/")
    val currentDestination =
        AppDestinations.topLevelDestinationForRoute(currentRoot)
            ?: destinations.first()

    LaunchedEffect(showDiagnosticsTab, currentRoot) {
        if (!showDiagnosticsTab && currentRoot == AppDestinations.Diagnostics.route) {
            navController.navigateToTopLevel(AppDestinations.Find)
        }
    }

    Scaffold(
        topBar = { AppTopBar(title = currentDestination.title) },
        bottomBar = { AppBottomBar(navController = navController, destinations = destinations) },
    ) { innerPadding ->
        AppNavHost(
            navController = navController,
            viewModelFactory = viewModelFactory,
            appContainer = appContainer,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
