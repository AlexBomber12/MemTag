package com.alexbomber12.memtag.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

data class AppDestination(
    val route: String,
    val label: String,
    val title: String,
    val icon: ImageVector,
)

object AppDestinations {
    val Lookup =
        AppDestination(
            route = "lookup",
            label = "Lookup",
            title = "Lookup",
            icon = Icons.Filled.Search,
        )
    val Find =
        AppDestination(
            route = "find",
            label = "Find",
            title = "Find",
            icon = Icons.Filled.MyLocation,
        )
    val RepairWrite =
        AppDestination(
            route = "repair_write",
            label = "Repair",
            title = "Repair & Write",
            icon = Icons.Filled.Build,
        )
    val Queue =
        AppDestination(
            route = "queue",
            label = "Queue",
            title = "Queue",
            icon = Icons.AutoMirrored.Filled.List,
        )
    val Settings =
        AppDestination(
            route = "settings",
            label = "Settings",
            title = "Settings",
            icon = Icons.Filled.Settings,
        )
    val Diagnostics =
        AppDestination(
            route = "diagnostics",
            label = "Diagnostics",
            title = "Diagnostics",
            icon = Icons.Filled.Info,
        )

    val topLevel = listOf(Lookup, Find, RepairWrite, Queue, Settings, Diagnostics)
}
