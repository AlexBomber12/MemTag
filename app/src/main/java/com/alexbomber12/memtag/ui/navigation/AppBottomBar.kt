@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.alexbomber12.memtag.ui.theme.MemTagTheme

@Composable
fun AppBottomBar(
    navController: NavController,
    destinations: List<AppDestination>,
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentRoot = currentRoute?.substringBefore("?")?.substringBefore("/")

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        destinations.forEach { destination ->
            NavigationBarItem(
                selected = currentRoot == destination.route,
                onClick = { navController.navigateToTopLevel(destination) },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(text = destination.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                alwaysShowLabel = true,
                colors =
                    NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    ),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun BottomBarPreview() {
    MemTagTheme {
        val navController = rememberNavController()
        val destinations = AppDestinations.topLevelDestinations(showDiagnostics = false)
        Scaffold(
            bottomBar = { AppBottomBar(navController = navController, destinations = destinations) },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = destinations.first().route,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
            ) {
                destinations.forEach { destination ->
                    composable(destination.route) {
                        Box(modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
