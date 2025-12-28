package com.alexbomber12.memtag.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination

fun NavController.navigateToTopLevel(destination: AppDestination) {
    if (currentBackStackEntry == null || graph.nodes.size() == 0) {
        return
    }
    val targetRoute =
        if (destination.route == AppDestinations.Find.route) {
            AppDestinations.findRoute()
        } else {
            destination.route
        }
    val startId = runCatching { graph.findStartDestination().id }.getOrNull()
    navigate(targetRoute) {
        if (startId != null) {
            popUpTo(startId) {
                saveState = true
            }
        }
        launchSingleTop = true
        restoreState = true
    }
}
