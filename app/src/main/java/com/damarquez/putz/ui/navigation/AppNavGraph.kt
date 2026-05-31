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
import com.damarquez.putz.ui.archive.ArchiveScreen
import com.damarquez.putz.ui.auth.AuthScreen
import com.damarquez.putz.ui.files.FilesScreen
import com.damarquez.putz.ui.settings.LanConnectionsScreen
import com.damarquez.putz.ui.trash.TrashScreen
import com.damarquez.putz.ui.settings.SettingsScreen
import com.damarquez.putz.ui.transfers.CalibreTransfersScreen
import com.damarquez.putz.ui.transfers.TransferHistoryScreen
import com.damarquez.putz.ui.transfers.TransfersScreen

import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import com.damarquez.putz.ui.GlobalSyncViewModel

import androidx.compose.runtime.LaunchedEffect
import com.damarquez.putz.data.repository.PendingCommentsRepository
import com.damarquez.putz.data.repository.PendingCoverRepository
import com.damarquez.putz.data.repository.PendingDeletionActionRepository
import com.damarquez.putz.data.repository.PendingGenerateCoverRepository
import com.damarquez.putz.data.repository.PendingSetPageCountRepository
import kotlinx.coroutines.flow.combine

private const val ROOT_FOLDER_NAME = "Your Files"

@Composable
fun AppNavGraph(
    settingsRepository: SettingsRepository,
    pendingCoverRepository: PendingCoverRepository,
    pendingCommentsRepository: PendingCommentsRepository,
    pendingGenerateCoverRepository: PendingGenerateCoverRepository,
    pendingSetPageCountRepository: PendingSetPageCountRepository,
    pendingDeletionActionRepository: PendingDeletionActionRepository,
) {
    val navController = rememberNavController()
    val authToken by settingsRepository.authTokenFlow.collectAsState()
    
    val syncViewModel: GlobalSyncViewModel = hiltViewModel()
    val libraryHasUpdates by syncViewModel.libraryHasUpdates.collectAsState()

    LaunchedEffect(Unit) {
        combine(
            listOf(
                pendingCoverRepository.flow,
                pendingCommentsRepository.flow,
                pendingGenerateCoverRepository.flow,
                pendingSetPageCountRepository.flow,
                pendingDeletionActionRepository.flow,
            )
        ) { values -> values.any { it != null } }.collect { hasAny ->
            if (hasAny) {
                val alreadyOnScreen = navController.currentDestination?.route == Screen.CalibreTransfers.route
                if (!alreadyOnScreen) {
                    navController.navigate(Screen.CalibreTransfers.route) {
                        launchSingleTop = true
                    }
                }
            }
        }
    }

    val startDestination = if (authToken.isBlank()) Screen.Auth.route
    else Screen.Files.createRoute(0L, ROOT_FOLDER_NAME)

    // Determine bottom nav visibility from the current back-stack entry
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showBottomNav = currentRoute == Screen.Files.route ||
                        currentRoute == Screen.Transfers.route ||
                        currentRoute == Screen.CalibreTransfers.route ||
                        currentRoute == Screen.Archive.route

    // Tab selection state
    val filesSelected = currentRoute == Screen.Files.route || currentRoute == Screen.Archive.route
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
                            if (filesSelected) {
                                // Already on Files — tap again to pop back to root
                                navController.popBackStack(
                                    route = Screen.Files.createRoute(0L, ROOT_FOLDER_NAME),
                                    inclusive = false,
                                )
                            } else {
                                // Pop the current tab entry to reveal the Files back stack
                                if (!navController.popBackStack(
                                        route = Screen.Files.route,
                                        inclusive = false,
                                    )
                                ) {
                                    navController.navigate(Screen.Files.createRoute(0L, ROOT_FOLDER_NAME))
                                }
                            }
                        },
                        icon = { Icon(Icons.Default.Folder, contentDescription = "Files") },
                        label = { Text("Files") },
                    )
                    NavigationBarItem(
                        selected = transfersSelected,
                        onClick = {
                            if (!transfersSelected) {
                                // Pop any other tab entry (e.g. Calibre) to keep Files stack intact
                                navController.popBackStack(route = Screen.Files.route, inclusive = false)
                                navController.navigate(Screen.Transfers.route) {
                                    launchSingleTop = true
                                }
                            }
                        },
                        icon = { Icon(Icons.Default.CloudDownload, contentDescription = "Transfers") },
                        label = { Text("Put.io Transfers") },
                    )
                    NavigationBarItem(
                        selected = calibreSelected,
                        onClick = {
                            if (!calibreSelected) {
                                // Pop any other tab entry (e.g. Transfers) to keep Files stack intact
                                navController.popBackStack(route = Screen.Files.route, inclusive = false)
                                navController.navigate(Screen.CalibreTransfers.route) {
                                    launchSingleTop = true
                                }
                            }
                        },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (libraryHasUpdates) {
                                        Badge()
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Book, contentDescription = "Calibre")
                            }
                        },
                        label = { Text("Calibre Transfers") },
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
                    navArgument(Screen.Files.ARG_HIGHLIGHT_ID) {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                    navArgument(Screen.Files.ARG_LOCAL_URI) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument(Screen.Files.ARG_LAN_CONNECTION_ID) {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                    navArgument(Screen.Files.ARG_LAN_PATH) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument(Screen.Files.ARG_TAB) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                FilesScreen(
                    onNavigateToFolder = { id, name, localUri, lanConnectionId, lanPath, tab ->
                        navController.navigate(
                            Screen.Files.createRoute(
                                id, name,
                                localUri = localUri,
                                lanConnectionId = if (lanConnectionId != -1L) lanConnectionId else null,
                                lanPath = lanPath,
                                tab = tab,
                            )
                        )
                    },
                    onNavigateToArchive = { localUri, lanConnectionId, lanPath, archiveName ->
                        navController.navigate(
                            Screen.Archive.createRoute(
                                archiveName,
                                localUri = localUri,
                                lanConnectionId = if (lanConnectionId != -1L) lanConnectionId else null,
                                lanPath = lanPath,
                            )
                        )
                    },
                    onNavigateToPutioArchive = { fileId, stubFileId, fileName, downloadUrl, fileSize, parentFolderId, isSynced ->
                        navController.navigate(
                            Screen.Archive.createRoute(
                                fileName,
                                putioFileId = fileId,
                                putioStubFileId = stubFileId,
                                putioDownloadUrl = downloadUrl,
                                putioFileSize = fileSize,
                                putioParentFolderId = parentFolderId,
                                putioIsSynced = isSynced,
                            )
                        )
                    },
                    onNavigateToTrash = { navController.navigate(Screen.Trash.route) },
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

            composable(Screen.Trash.route) {
                TrashScreen(
                    onNavigateUp = { navController.navigateUp() },
                    viewModel = hiltViewModel(),
                )
            }

            composable(
                route = Screen.Archive.route,
                arguments = listOf(
                    navArgument(Screen.Archive.ARG_ARCHIVE_NAME) { type = NavType.StringType },
                    navArgument(Screen.Archive.ARG_LOCAL_URI) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument(Screen.Archive.ARG_LAN_CONNECTION_ID) {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                    navArgument(Screen.Archive.ARG_LAN_PATH) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument(Screen.Archive.ARG_PUTIO_FILE_ID) {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                    navArgument(Screen.Archive.ARG_PUTIO_STUB_FILE_ID) {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                    navArgument(Screen.Archive.ARG_PUTIO_DOWNLOAD_URL) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument(Screen.Archive.ARG_PUTIO_FILE_SIZE) {
                        type = NavType.LongType
                        defaultValue = 0L
                    },
                    navArgument(Screen.Archive.ARG_PUTIO_PARENT_FOLDER_ID) {
                        type = NavType.LongType
                        defaultValue = 0L
                    },
                    navArgument(Screen.Archive.ARG_PUTIO_IS_SYNCED) {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) {
                ArchiveScreen(
                    onNavigateUp = { navController.navigateUp() },
                    viewModel = hiltViewModel(),
                )
            }

            composable(Screen.Transfers.route) {
                TransfersScreen(
                    viewModel = hiltViewModel(),
                    onNavigateToFiles = { parentId, folderName, highlightId ->
                        navController.popBackStack(Screen.Files.route, inclusive = false)
                        navController.navigate(Screen.Files.createRoute(parentId, folderName, highlightId))
                    },
                    onNavigateToHistory = { navController.navigate(Screen.TransferHistory.route) },
                )
            }

            composable(Screen.TransferHistory.route) {
                TransferHistoryScreen(
                    viewModel = hiltViewModel(),
                    onNavigateUp = { navController.navigateUp() },
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateUp = { navController.navigateUp() },
                    onNavigateToCalibreTransfers = { navController.navigate(Screen.CalibreTransfers.route) },
                    onNavigateToLanConnections = { navController.navigate(Screen.LanConnections.route) },
                    viewModel = hiltViewModel(),
                )
            }

            composable(Screen.LanConnections.route) {
                LanConnectionsScreen(
                    onNavigateUp = { navController.navigateUp() },
                    viewModel = hiltViewModel(),
                )
            }

            composable(Screen.CalibreTransfers.route) {
                CalibreTransfersScreen(
                    onNavigateUp = { navController.navigateUp() },
                    viewModel = hiltViewModel(),
                    pendingCoverRepository = pendingCoverRepository,
                    pendingCommentsRepository = pendingCommentsRepository,
                    pendingGenerateCoverRepository = pendingGenerateCoverRepository,
                    pendingSetPageCountRepository = pendingSetPageCountRepository,
                    pendingDeletionActionRepository = pendingDeletionActionRepository,
                )
            }
        }
    }
}
