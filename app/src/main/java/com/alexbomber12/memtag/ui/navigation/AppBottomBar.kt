@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState

@Composable
fun AppBottomBar(
    navController: NavController,
    destinations: List<AppDestination>,
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentRoot = currentRoute?.substringBefore("?")?.substringBefore("/")

    NavigationBar {
        destinations.forEach { destination ->
            NavigationBarItem(
                selected = currentRoot == destination.route,
                onClick = { navController.navigateToTopLevel(destination) },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(text = destination.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                alwaysShowLabel = true,
            )
        }
    }
}
