@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.alexbomber12.memtag.app.AppContainer
import com.alexbomber12.memtag.ui.screens.diagnostics.DiagnosticsScreen
import com.alexbomber12.memtag.ui.screens.diagnostics.DiagnosticsViewModel
import com.alexbomber12.memtag.ui.screens.find.FindScreen
import com.alexbomber12.memtag.ui.screens.find.FindViewModel
import com.alexbomber12.memtag.ui.screens.lookup.LookupScreen
import com.alexbomber12.memtag.ui.screens.lookup.LookupViewModel
import com.alexbomber12.memtag.ui.screens.queue.QueueScreen
import com.alexbomber12.memtag.ui.screens.queue.QueueViewModel
import com.alexbomber12.memtag.ui.screens.repair.RepairScreen
import com.alexbomber12.memtag.ui.screens.repair.RepairViewModel
import com.alexbomber12.memtag.ui.screens.settings.SettingsScreen
import com.alexbomber12.memtag.ui.screens.settings.SettingsViewModel

@Composable
fun AppNavHost(
    navController: NavHostController,
    viewModelFactory: ViewModelProvider.Factory,
    appContainer: AppContainer,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = AppDestinations.Lookup.route,
        modifier = modifier,
    ) {
        composable(AppDestinations.Lookup.route) {
            val viewModel: LookupViewModel = viewModel(factory = viewModelFactory)
            LookupScreen(viewModel = viewModel)
        }
        composable(
            route = AppDestinations.FIND_ROUTE_PATTERN,
            arguments =
                listOf(
                    navArgument("epc") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("autoStart") {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                    navArgument("fromQueue") {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
        ) { backStackEntry ->
            val viewModel: FindViewModel = viewModel(factory = viewModelFactory)
            val epc = backStackEntry.arguments?.getString("epc").orEmpty()
            val autoStart = backStackEntry.arguments?.getBoolean("autoStart") ?: false
            val fromQueue = backStackEntry.arguments?.getBoolean("fromQueue") ?: false
            FindScreen(
                viewModel = viewModel,
                initialEpc = epc,
                autoStart = autoStart,
                showBackToQueue = fromQueue,
                onBackToQueue = { navController.popBackStack() },
            )
        }
        composable(AppDestinations.RepairWrite.route) {
            val viewModel: RepairViewModel = viewModel(factory = viewModelFactory)
            RepairScreen(viewModel = viewModel)
        }
        composable(AppDestinations.Queue.route) {
            val viewModel: QueueViewModel = viewModel(factory = viewModelFactory)
            QueueScreen(
                viewModel = viewModel,
                onStartFind = { epc, autoStart ->
                    navController.navigate(
                        AppDestinations.findRoute(
                            epc = epc,
                            autoStart = autoStart,
                            fromQueue = true,
                        ),
                    )
                },
            )
        }
        composable(AppDestinations.Settings.route) {
            val viewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
            SettingsScreen(viewModel = viewModel)
        }
        composable(AppDestinations.Diagnostics.route) {
            val viewModel: DiagnosticsViewModel = viewModel(factory = viewModelFactory)
            DiagnosticsScreen(viewModel = viewModel)
        }
    }
}
