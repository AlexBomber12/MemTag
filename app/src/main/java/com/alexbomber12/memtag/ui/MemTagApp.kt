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
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.alexbomber12.memtag.app.AppContainer
import com.alexbomber12.memtag.app.AppViewModelFactory
import com.alexbomber12.memtag.core.logging.Logger
import com.alexbomber12.memtag.data.settings.AppSettings
import com.alexbomber12.memtag.ui.navigation.AppBottomBar
import com.alexbomber12.memtag.ui.navigation.AppDestinations
import com.alexbomber12.memtag.ui.navigation.AppNavHost
import com.alexbomber12.memtag.ui.navigation.AppTopBar
import com.alexbomber12.memtag.ui.navigation.navigateToTopLevel
import com.alexbomber12.memtag.util.epc.EpcNormalizer
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
        val uri = deepLinkIntent?.data ?: return@LaunchedEffect
        val params = parseFindDeepLink(uri, appContainer.logger) ?: return@LaunchedEffect
        if (params.epc.isNotBlank()) {
            val timestamp = System.currentTimeMillis()
            appContainer.settingsStore.update {
                it.copy(
                    lastFindTargetEpc = params.epc,
                    lastFindTargetEpcAt = timestamp,
                )
            }
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
            appContainer = appContainer,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

private data class FindDeepLinkParams(
    val epc: String,
    val autoStart: Boolean,
)

private fun parseFindDeepLink(
    uri: Uri,
    logger: Logger,
): FindDeepLinkParams? {
    val scheme = uri.scheme?.lowercase().orEmpty()
    val host = uri.host?.lowercase().orEmpty()
    val path = uri.path?.lowercase().orEmpty()
    val isFindHost = host == "find"
    val isFindPath = host.isEmpty() && path == "/find"
    if (scheme != "memtag" || (!isFindHost && !isFindPath)) {
        logger.w(TAG, "external deeplink rejected: scheme=$scheme host=$host path=$path")
        return null
    }
    val rawEpc = uri.getQueryParameter("epc")?.trim().orEmpty()
    val normalizedEpc = runCatching { EpcNormalizer.normalize(rawEpc) }.getOrNull().orEmpty()
    if (rawEpc.isNotBlank() && normalizedEpc.isBlank()) {
        logger.w(TAG, "external deeplink invalid epc")
    }
    val autoStart =
        parseAutoStartParam(uri)
            ?: normalizedEpc.isNotBlank()
    return FindDeepLinkParams(
        epc = normalizedEpc,
        autoStart = autoStart,
    )
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
