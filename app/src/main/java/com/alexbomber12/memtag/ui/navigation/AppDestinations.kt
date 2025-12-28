package com.alexbomber12.memtag.ui.navigation

import android.net.Uri
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
    val Find =
        AppDestination(
            route = "find",
            label = "Find",
            title = "Find",
            icon = Icons.Filled.MyLocation,
        )
    val Lookup =
        AppDestination(
            route = "lookup",
            label = "Lookup",
            title = "Lookup",
            icon = Icons.Filled.Search,
        )
    val RepairWrite =
        AppDestination(
            route = "repair_write",
            label = "Repair",
            title = "Verify & Repair",
            icon = Icons.Filled.Build,
        )
    val Batch =
        AppDestination(
            route = "batch",
            label = "Batch",
            title = "Batch",
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
            label = "Diag",
            title = "Diagnostics",
            icon = Icons.Filled.Info,
        )

    private val baseTopLevel = listOf(Find, Lookup, RepairWrite, Batch, Settings)
    private val allTopLevel = baseTopLevel + Diagnostics

    fun topLevelDestinations(showDiagnostics: Boolean): List<AppDestination> {
        return if (showDiagnostics) {
            allTopLevel
        } else {
            baseTopLevel
        }
    }

    fun topLevelDestinationForRoute(route: String?): AppDestination? {
        return allTopLevel.firstOrNull { it.route == route }
    }

    const val FIND_ROUTE_PATTERN = "find?epc={epc}&autoStart={autoStart}&fromBatch={fromBatch}"

    fun findRoute(
        epc: String = "",
        autoStart: Boolean = false,
        fromBatch: Boolean = false,
    ): String {
        val encoded = Uri.encode(epc)
        return "find?epc=$encoded&autoStart=$autoStart&fromBatch=$fromBatch"
    }
}
