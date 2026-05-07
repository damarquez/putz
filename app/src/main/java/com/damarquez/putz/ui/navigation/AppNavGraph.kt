package com.damarquez.putz.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.damarquez.putz.settings.SettingsRepository
import com.damarquez.putz.ui.auth.AuthScreen
import com.damarquez.putz.ui.files.FilesScreen
import com.damarquez.putz.ui.settings.SettingsScreen
import com.damarquez.putz.ui.transfers.CalibreTransfersScreen
import com.damarquez.putz.ui.transfers.TransfersScreen

private const val ROOT_FOLDER_NAME = "Your Files"

@Composable
fun AppNavGraph(settingsRepository: SettingsRepository) {
    val navController = rememberNavController()
    val authToken by settingsRepository.authTokenFlow.collectAsState()

    val startDestination = if (authToken.isBlank()) Screen.Auth.route
    else Screen.Files.createRoute(0L, ROOT_FOLDER_NAME)

    // Determine bottom nav visibility from the current back-stack entry
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentParentId = backStackEntry?.arguments?.getLong(Screen.Files.ARG_PARENT_ID) ?: 0L

    val showBottomNav = when {
        currentRoute == Screen.Transfers.route -> true
        currentRoute == Screen.CalibreTransfers.route -> true
        currentRoute == Screen.Files.route && currentParentId == 0L -> true
        else -> false
    }

    // Tab selection state
    val filesSelected = currentRoute == Screen.Files.route
    val transfersSelected = currentRoute == Screen.Transfers.route
    val calibreSelected = currentRoute == Screen.CalibreTransfers.route

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomNav,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                NavigationBar {
                    NavigationBarItem(
                        selected = filesSelected,
                        onClick = {
                            navController.navigate(Screen.Files.createRoute(0L, ROOT_FOLDER_NAME)) {
                                launchSingleTop = true
                                restoreState = true
                                popUpTo(Screen.Files.route) { inclusive = true }
                            }
                        },
                        icon = { Icon(Icons.Default.Folder, contentDescription = "Files") },
                        label = { Text("Files") },
                    )
                    NavigationBarItem(
                        selected = transfersSelected,
                        onClick = {
                            navController.navigate(Screen.Transfers.route) {
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Default.CloudDownload, contentDescription = "Transfers") },
                        label = { Text("Transfers") },
                    )
                    NavigationBarItem(
                        selected = calibreSelected,
                        onClick = {
                            navController.navigate(Screen.CalibreTransfers.route) {
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Default.Book, contentDescription = "Calibre") },
                        label = { Text("Calibre") },
                    )
                }
            }
        },
        // Let the outer Scaffold own all window insets so inner screens don't double-apply them
        contentWindowInsets = WindowInsets(0),
    ) { scaffoldPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(scaffoldPadding),
        ) {
            composable(Screen.Auth.route) {
                AuthScreen(
                    onAuthSuccess = {
                        navController.navigate(Screen.Files.createRoute(0L, ROOT_FOLDER_NAME)) {
                            popUpTo(Screen.Auth.route) { inclusive = true }
                        }
                    },
                    viewModel = hiltViewModel(),
                )
            }

            composable(
                route = Screen.Files.route,
                arguments = listOf(
                    navArgument(Screen.Files.ARG_PARENT_ID) {
                        type = NavType.LongType
                        defaultValue = 0L
                    },
                    navArgument(Screen.Files.ARG_FOLDER_NAME) {
                        type = NavType.StringType
                        defaultValue = ROOT_FOLDER_NAME
                    },
                ),
            ) {
                FilesScreen(
                    onNavigateToFolder = { id, name ->
                        navController.navigate(Screen.Files.createRoute(id, name))
                    },
                    onNavigateUp = { navController.navigateUp() },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onSignOut = {
                        navController.navigate(Screen.Auth.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    viewModel = hiltViewModel(),
                )
            }

            composable(Screen.Transfers.route) {
                TransfersScreen(viewModel = hiltViewModel())
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateUp = { navController.navigateUp() },
                    onNavigateToCalibreTransfers = { navController.navigate(Screen.CalibreTransfers.route) },
                    viewModel = hiltViewModel(),
                )
            }

            composable(Screen.CalibreTransfers.route) {
                CalibreTransfersScreen(
                    onNavigateUp = { navController.navigateUp() },
                    viewModel = hiltViewModel()
                )
            }
        }
    }
}
