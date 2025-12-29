@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.alexbomber12.memtag.app.AppContainer
import com.alexbomber12.memtag.app.AppViewModelFactory
import com.alexbomber12.memtag.core.logging.Logger
import com.alexbomber12.memtag.data.settings.AppSettings
import com.alexbomber12.memtag.ui.intent.extractFindEpc
import com.alexbomber12.memtag.ui.navigation.AppBottomBar
import com.alexbomber12.memtag.ui.navigation.AppDestinations
import com.alexbomber12.memtag.ui.navigation.AppNavHost
import com.alexbomber12.memtag.ui.navigation.AppTopBar
import com.alexbomber12.memtag.ui.navigation.navigateToTopLevel
import com.alexbomber12.memtag.ui.screens.find.FindViewModel
import kotlinx.coroutines.flow.map

@Composable
fun MemTagApp(
    appContainer: AppContainer,
    deepLinkIntent: Intent?,
) {
    val navController = rememberNavController()
    val settingsFlow =
        remember(appContainer.settingsStore) {
            appContainer.settingsStore.settingsFlow.map { it to true }
        }
    val settingsSnapshot by
        settingsFlow.collectAsStateWithLifecycle(
            initialValue = AppSettings() to false,
        )
    val (settings, settingsLoaded) = settingsSnapshot
    val showDiagnosticsTab = settings.showDiagnosticsTab
    val destinations = AppDestinations.topLevelDestinations(showDiagnosticsTab)
    val viewModelFactory = remember(appContainer) { AppViewModelFactory(appContainer) }
    val findViewModel: FindViewModel = viewModel(factory = viewModelFactory)
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentRoot = currentRoute?.substringBefore("?")?.substringBefore("/")
    val currentDestination =
        AppDestinations.topLevelDestinationForRoute(currentRoot)
            ?: destinations.first()

    LaunchedEffect(showDiagnosticsTab, currentRoot, settingsLoaded) {
        if (settingsLoaded && !showDiagnosticsTab && currentRoot == AppDestinations.Diagnostics.route) {
            navController.navigateToTopLevel(AppDestinations.Find)
        }
    }

    LaunchedEffect(deepLinkIntent) {
        val params = parseFindIntent(deepLinkIntent, appContainer.logger) ?: return@LaunchedEffect
        if (params.epc.isNotBlank()) {
            findViewModel.applyExternalEpc(params.epc, params.autoStart)
        }
        appContainer.logger.i(TAG, "external deeplink: find epc=${params.epc} autoStart=${params.autoStart}")
        navController.navigate(
            AppDestinations.findRoute(
                epc = params.epc,
                autoStart = params.autoStart,
                fromBatch = false,
            ),
        ) {
            popUpTo(navController.graph.startDestinationId) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        topBar = { AppTopBar(title = currentDestination.title) },
        bottomBar = { AppBottomBar(navController = navController, destinations = destinations) },
    ) { innerPadding ->
        AppNavHost(
            navController = navController,
            viewModelFactory = viewModelFactory,
            findViewModel = findViewModel,
            appContainer = appContainer,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

private data class FindIntentParams(
    val epc: String,
    val autoStart: Boolean,
)

private fun parseFindIntent(
    intent: Intent?,
    logger: Logger,
): FindIntentParams? {
    if (intent == null) {
        return null
    }
    val uri = intent.data
    val isFindUri = uri?.let { isFindDeepLink(it, logger) } ?: false
    val epc = extractFindEpc(intent).orEmpty()
    val hasEpc = epc.isNotBlank()
    if (!isFindUri && !hasEpc) {
        return null
    }
    val autoStart = uri?.let { parseAutoStartParam(it) } ?: hasEpc
    return FindIntentParams(
        epc = epc,
        autoStart = autoStart,
    )
}

private fun isFindDeepLink(
    uri: Uri,
    logger: Logger,
): Boolean {
    val scheme = uri.scheme?.lowercase().orEmpty()
    val host = uri.host?.lowercase().orEmpty()
    val path = uri.path?.lowercase().orEmpty()
    val isFindHost = host == "find"
    val isFindPath = host.isEmpty() && path == "/find"
    if (scheme != "memtag" || (!isFindHost && !isFindPath)) {
        logger.w(TAG, "external deeplink rejected: scheme=$scheme host=$host path=$path")
        return false
    }
    return true
}

private fun parseAutoStartParam(uri: Uri): Boolean? {
    if (!uri.queryParameterNames.contains("autoStart")) {
        return null
    }
    return when (uri.getQueryParameter("autoStart")?.trim()?.lowercase().orEmpty()) {
        "1",
        "true",
        "yes",
        -> true
        "0",
        "false",
        "no",
        -> false
        else -> false
    }
}

private const val TAG = "MemTagDeepLink"
