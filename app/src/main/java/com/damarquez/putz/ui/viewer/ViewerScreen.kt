package com.damarquez.putz.ui.viewer

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Dispatches to the in-app preview screen for [kind]. Add a branch here when a new ViewerKind is introduced. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    kind: ViewerKind,
    title: String,
    filePath: String,
    onNavigateUp: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (kind) {
            ViewerKind.IMAGE -> ImageViewer(filePath = filePath, modifier = Modifier.padding(padding))
            ViewerKind.EPUB -> EpubViewer(filePath = filePath, modifier = Modifier.padding(padding))
        }
    }
}
