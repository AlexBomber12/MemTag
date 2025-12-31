@file:Suppress("FunctionName")

package com.alexbomber12.memtag.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

private val DefaultContentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = title) },
                navigationIcon = {
                    if (navigationIcon != null) {
                        navigationIcon()
                    }
                },
                actions = actions,
            )
        },
    ) { innerPadding ->
        val mergedPadding =
            PaddingValues(
                start =
                    DefaultContentPadding.calculateStartPadding(layoutDirection) +
                        innerPadding.calculateStartPadding(layoutDirection),
                top =
                    DefaultContentPadding.calculateTopPadding() +
                        innerPadding.calculateTopPadding(),
                end =
                    DefaultContentPadding.calculateEndPadding(layoutDirection) +
                        innerPadding.calculateEndPadding(layoutDirection),
                bottom =
                    DefaultContentPadding.calculateBottomPadding() +
                        innerPadding.calculateBottomPadding(),
            )
        content(mergedPadding)
    }
}
