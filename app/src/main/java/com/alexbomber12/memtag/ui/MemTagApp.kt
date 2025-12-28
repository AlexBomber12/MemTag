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
import com.alexbomber12.memtag.data.settings.AppSettings
import com.alexbomber12.memtag.ui.navigation.AppBottomBar
import com.alexbomber12.memtag.ui.navigation.AppDestinations
import com.alexbomber12.memtag.ui.navigation.AppNavHost
import com.alexbomber12.memtag.ui.navigation.AppTopBar
import com.alexbomber12.memtag.ui.navigation.navigateToTopLevel
import com.alexbomber12.memtag.util.epc.EpcNormalizer
import kotlinx.coroutines.flow.map
import java.util.Locale

@Composable
fun MemTagApp(
    appContainer: AppContainer,
    deepLinkIntent: Intent? = null,
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
        val logger = appContainer.logger
        if (!isFindDeepLink(uri)) {
            logger.w(DEEP_LINK_TAG, "external deeplink ignored: ${uri}")
            return@LaunchedEffect
        }
        val epcRaw = uri.getQueryParameter("epc")?.trim().orEmpty()
        val normalizedEpc =
            if (epcRaw.isBlank()) {
                ""
            } else {
                runCatching { EpcNormalizer.normalize(epcRaw) }.getOrNull().orEmpty()
            }
        if (epcRaw.isNotBlank() && normalizedEpc.isBlank()) {
            logger.w(DEEP_LINK_TAG, "external deeplink invalid epc: $epcRaw")
        }
        val autoStart =
            parseAutoStart(uri, normalizedEpc.isNotBlank()) && normalizedEpc.isNotBlank()
        if (normalizedEpc.isNotBlank()) {
            appContainer.settingsStore.update { it.copy(lastFindTargetEpc = normalizedEpc) }
        }
        logger.i(DEEP_LINK_TAG, "external deeplink: find epc=$normalizedEpc autoStart=$autoStart")
        navController.navigate(
            AppDestinations.findRoute(
                epc = normalizedEpc,
                autoStart = autoStart,
                fromBatch = false,
            ),
        ) {
            launchSingleTop = true
            restoreState = true
            popUpTo(navController.graph.startDestinationId) {
                saveState = true
            }
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

private fun isFindDeepLink(uri: Uri): Boolean {
    val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
    if (scheme != "memtag") {
        return false
    }
    val host = uri.host?.lowercase(Locale.US).orEmpty()
    val path = uri.path?.lowercase(Locale.US).orEmpty()
    return host == "find" || path == "/find"
}

private fun parseAutoStart(
    uri: Uri,
    hasEpc: Boolean,
): Boolean {
    val raw = uri.getQueryParameter("autoStart") ?: return hasEpc
    return when (raw.trim().lowercase(Locale.US)) {
        "1",
        "true",
        "yes",
        -> true
        "0",
        "false",
        "no",
        -> false
        else -> hasEpc
    }
}

private const val DEEP_LINK_TAG = "DeepLink"
