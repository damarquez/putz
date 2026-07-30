package com.damarquez.putz.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToDrive
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Storage
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
import com.damarquez.putz.ui.files.FilesTab
import com.damarquez.putz.ui.search.SearchScreen
import com.damarquez.putz.ui.settings.LanConnectionsScreen
import com.damarquez.putz.ui.trash.TrashScreen
import com.damarquez.putz.ui.settings.SettingsScreen
import com.damarquez.putz.ui.settings.SettingsAboutScreen
import com.damarquez.putz.ui.settings.SettingsAccountScreen
import com.damarquez.putz.ui.settings.SettingsAppearanceScreen
import com.damarquez.putz.ui.settings.SettingsDaemonScreen
import com.damarquez.putz.ui.settings.SettingsLibrariesScreen
import com.damarquez.putz.ui.settings.SettingsSearchScreen
import com.damarquez.putz.ui.transfers.CalibreTransfersScreen
import com.damarquez.putz.ui.transfers.TransferHistoryScreen
import com.damarquez.putz.ui.transfers.TransfersScreen
import com.damarquez.putz.ui.viewer.ViewerKind
import com.damarquez.putz.ui.viewer.ViewerScreen

import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import com.damarquez.putz.ui.GlobalSyncViewModel

import androidx.compose.runtime.LaunchedEffect
import com.damarquez.putz.data.repository.PendingCommentsRepository
import com.damarquez.putz.data.repository.PendingConvertFormatRepository
import com.damarquez.putz.data.repository.PendingCoverRepository
import com.damarquez.putz.data.repository.PendingDeletionActionRepository
import com.damarquez.putz.data.repository.PendingExtractOrRandomCoverRepository
import com.damarquez.putz.data.repository.PendingGenerateCoverRepository
import com.damarquez.putz.data.repository.PendingProtectBookRepository
import com.damarquez.putz.data.repository.PendingSetPageCountRepository
import com.damarquez.putz.data.repository.PendingUnprotectBookRepository
import kotlinx.coroutines.flow.combine

private const val ROOT_FOLDER_NAME = "Your Files"

@Composable
fun AppNavGraph(
    settingsRepository: SettingsRepository,
    pendingCoverRepository: PendingCoverRepository,
    pendingCommentsRepository: PendingCommentsRepository,
    pendingGenerateCoverRepository: PendingGenerateCoverRepository,
    pendingExtractOrRandomCoverRepository: PendingExtractOrRandomCoverRepository,
    pendingSetPageCountRepository: PendingSetPageCountRepository,
    pendingConvertFormatRepository: PendingConvertFormatRepository,
    pendingDeletionActionRepository: PendingDeletionActionRepository,
    pendingEditMetadataRepository: com.damarquez.putz.data.repository.PendingEditMetadataRepository,
    pendingProtectBookRepository: PendingProtectBookRepository,
    pendingUnprotectBookRepository: PendingUnprotectBookRepository,
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
                pendingExtractOrRandomCoverRepository.flow,
                pendingProtectBookRepository.flow,
                pendingUnprotectBookRepository.flow,
                pendingSetPageCountRepository.flow,
                pendingConvertFormatRepository.flow,
                pendingDeletionActionRepository.flow,
                pendingEditMetadataRepository.flow,
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
                        currentRoute == Screen.Drive.route ||
                        currentRoute == Screen.Transfers.route ||
                        currentRoute == Screen.Search.route ||
                        currentRoute == Screen.CalibreTransfers.route ||
                        currentRoute == Screen.Archive.route

    // The Files route carries which file-source tab it's showing (Cloud/put.io vs Special/LAN)
    // as a query arg — the route template alone can't tell them apart, so read the live arg.
    val currentTabArg = backStackEntry?.arguments?.getString(Screen.Files.ARG_TAB)

    // Tab selection state. Archive has no tab of its own (reached from either source) and is
    // treated as belonging to Cloud, matching the pre-split default.
    val cloudSelected = currentRoute == Screen.Archive.route ||
        (currentRoute == Screen.Files.route && currentTabArg != FilesTab.SPECIAL.name)
    val specialSelected = currentRoute == Screen.Files.route && currentTabArg == FilesTab.SPECIAL.name
    val driveSelected = currentRoute == Screen.Drive.route
    val transfersSelected = currentRoute == Screen.Transfers.route
    val searchSelected = currentRoute == Screen.Search.route
    val calibreSelected = currentRoute == Screen.CalibreTransfers.route

    // True when the current screen is a non-Files tab (Special, Drive, Transfers, Search, Calibre).
    // These are always at the top of the back stack — pop one entry to return to Files/Archive.
    val isOnNonFilesTab = specialSelected || driveSelected || transfersSelected || searchSelected || calibreSelected

    // CONTRACT: back-stack-per-tab invariant — every other tab button assumes it can leave its
    // own tab behind with a single navController.popBackStack() call, which only removes exactly
    // one entry. That's true for Special/Torrents/Calibre (they never push more than one entry),
    // but Drive supports deep folder navigation just like Files, so a single pop only steps up one
    // Drive folder instead of leaving Drive entirely — the bug where tapping another tab while deep
    // in Drive seemed to do nothing (or worse, silently drilled back up through Drive). Whenever
    // Drive is the tab being left, collapse its whole subtree in one shot by popping back to its
    // root entry, inclusive — with saveState = true so Navigation keeps the popped entries (and
    // their ViewModels/scroll position/etc.) alive instead of destroying them, so Drive's own
    // "entering" branch below can revive them via restoreState instead of reloading from scratch.
    val driveRootRoute = Screen.Drive.createRoute(
        com.damarquez.putz.data.repository.DriveFilesRepository.DRIVE_ROOT_ID,
        "Drive",
    )
    fun leaveCurrentNonFilesTab(): Boolean {
        return if (driveSelected) {
            navController.popBackStack(route = driveRootRoute, inclusive = true, saveState = true)
        } else {
            navController.popBackStack()
        }
    }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomNav,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                NavigationBar {
                    NavigationBarItem(
                        selected = cloudSelected,
                        onClick = {
                            if (cloudSelected) {
                                // Already on Files/Archive — tap again to pop back to root
                                navController.popBackStack(
                                    route = Screen.Files.createRoute(0L, ROOT_FOLDER_NAME),
                                    inclusive = false,
                                )
                            } else {
                                // On a non-Files tab: leave it to reveal Files/Archive's back stack
                                if (!leaveCurrentNonFilesTab()) {
                                    navController.navigate(Screen.Files.createRoute(0L, ROOT_FOLDER_NAME))
                                }
                            }
                        },
                        icon = { Icon(Icons.Default.Folder, contentDescription = "Files") },
                        label = { Text("Files") },
                    )
                    NavigationBarItem(
                        selected = specialSelected,
                        onClick = {
                            if (specialSelected) {
                                // Already on Special — tap again to pop back to its root
                                navController.popBackStack(
                                    route = Screen.Files.createRoute(0L, "Special", tab = FilesTab.SPECIAL.name),
                                    inclusive = false,
                                )
                            } else {
                                if (isOnNonFilesTab) leaveCurrentNonFilesTab()
                                navController.navigate(Screen.Files.createRoute(0L, "Special", tab = FilesTab.SPECIAL.name))
                            }
                        },
                        icon = { Icon(Icons.Default.Storage, contentDescription = "Special") },
                        label = { Text("Special") },
                    )
                    NavigationBarItem(
                        selected = driveSelected,
                        onClick = {
                            if (driveSelected) {
                                // Already on Drive — tap again to pop back to its root (root itself
                                // is always present in the stack, see driveRootRoute).
                                navController.popBackStack(route = driveRootRoute, inclusive = false)
                            } else {
                                // Returning from another tab: leave it (if Drive itself is what's
                                // being left, leaveCurrentNonFilesTab saves its state rather than
                                // destroying it — see above), then revive whatever was saved instead
                                // of creating a fresh root. restoreState is a no-op (falls through to
                                // a plain fresh navigate) the first time Drive is ever opened.
                                if (isOnNonFilesTab) leaveCurrentNonFilesTab()
                                navController.navigate(driveRootRoute) {
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(Icons.Default.AddToDrive, contentDescription = "Drive") },
                        label = { Text("Drive") },
                    )
                    NavigationBarItem(
                        selected = transfersSelected,
                        onClick = {
                            if (!transfersSelected) {
                                // If on another non-Files tab, leave it first so we don't stack tabs
                                if (isOnNonFilesTab) leaveCurrentNonFilesTab()
                                navController.navigate(Screen.Transfers.route) {
                                    launchSingleTop = true
                                }
                            }
                        },
                        icon = { Icon(Icons.Default.CloudDownload, contentDescription = "Torrents") },
                        label = { Text("Torrents") },
                    )
                    NavigationBarItem(
                        selected = calibreSelected,
                        onClick = {
                            if (!calibreSelected) {
                                if (isOnNonFilesTab) leaveCurrentNonFilesTab()
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
                    navArgument(Screen.Files.ARG_DRIVE_FOLDER_ID) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                FilesScreen(
                    onNavigateToFolder = { id, name, localUri, lanConnectionId, lanPath, tab, driveFolderId ->
                        navController.navigate(
                            Screen.Files.createRoute(
                                id, name,
                                localUri = localUri,
                                lanConnectionId = if (lanConnectionId != -1L) lanConnectionId else null,
                                lanPath = lanPath,
                                tab = tab,
                                driveFolderId = driveFolderId,
                            )
                        )
                    },
                    onNavigateToFolderHighlighted = { folderId, folderName, highlightId ->
                        navController.navigate(
                            Screen.Files.createRoute(folderId, folderName, highlightId = highlightId)
                        )
                    },
                    onNavigateToArchive = { localUri, lanConnectionId, lanPath, archiveName, autoJoin ->
                        navController.navigate(
                            Screen.Archive.createRoute(
                                archiveName,
                                localUri = localUri,
                                lanConnectionId = if (lanConnectionId != -1L) lanConnectionId else null,
                                lanPath = lanPath,
                                autoJoin = autoJoin,
                            )
                        )
                    },
                    onNavigateToPutioArchive = { fileId, stubFileId, fileName, downloadUrl, fileSize, parentFolderId, isSynced, autoJoin ->
                        navController.navigate(
                            Screen.Archive.createRoute(
                                fileName,
                                putioFileId = fileId,
                                putioStubFileId = stubFileId,
                                putioDownloadUrl = downloadUrl,
                                putioFileSize = fileSize,
                                putioParentFolderId = parentFolderId,
                                putioIsSynced = isSynced,
                                autoJoin = autoJoin,
                            )
                        )
                    },
                    onNavigateToViewer = { kind, title, filePath ->
                        navController.navigate(Screen.Viewer.createRoute(kind.name, title, filePath))
                    },
                    onNavigateToTrash = { navController.navigate(Screen.Trash.route) },
                    onNavigateUp = { navController.navigateUp() },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    viewModel = hiltViewModel(),
                    syncViewModel = syncViewModel,
                )
            }

            // Same FilesScreen/FilesViewModel as above, but a distinct destination — see the
            // comment on Screen.Drive for why Drive can't just be another Screen.Files instance.
            // Drive is read-only, so archive/trash/highlight navigation are unreachable here
            // (FilesScreen never calls them for isDrive files) and are wired as no-ops.
            composable(
                route = Screen.Drive.route,
                arguments = listOf(
                    navArgument(Screen.Drive.ARG_PARENT_ID) {
                        type = NavType.LongType
                        defaultValue = com.damarquez.putz.data.repository.DriveFilesRepository.DRIVE_ROOT_ID
                    },
                    navArgument(Screen.Drive.ARG_FOLDER_NAME) {
                        type = NavType.StringType
                        defaultValue = "Drive"
                    },
                    navArgument(Screen.Drive.ARG_DRIVE_FOLDER_ID) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                FilesScreen(
                    onNavigateToFolder = { id, name, _, _, _, _, driveFolderId ->
                        navController.navigate(Screen.Drive.createRoute(id, name, driveFolderId))
                    },
                    onNavigateToFolderHighlighted = { _, _, _ -> },
                    onNavigateToArchive = { _, _, _, _, _ -> },
                    onNavigateToPutioArchive = { _, _, _, _, _, _, _, _ -> },
                    onNavigateToViewer = { kind, title, filePath ->
                        navController.navigate(Screen.Viewer.createRoute(kind.name, title, filePath))
                    },
                    onNavigateToTrash = {},
                    onNavigateUp = { navController.navigateUp() },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    viewModel = hiltViewModel(),
                    syncViewModel = syncViewModel,
                )
            }

            composable(
                route = Screen.Viewer.route,
                arguments = listOf(
                    navArgument(Screen.Viewer.ARG_KIND) { type = NavType.StringType },
                    navArgument(Screen.Viewer.ARG_TITLE) { type = NavType.StringType },
                    navArgument(Screen.Viewer.ARG_FILE_PATH) { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val kindArg = backStackEntry.arguments?.getString(Screen.Viewer.ARG_KIND).orEmpty()
                val title = backStackEntry.arguments?.getString(Screen.Viewer.ARG_TITLE).orEmpty()
                val filePath = backStackEntry.arguments?.getString(Screen.Viewer.ARG_FILE_PATH).orEmpty()
                ViewerScreen(
                    kind = ViewerKind.valueOf(kindArg),
                    title = title,
                    filePath = filePath,
                    onNavigateUp = { navController.navigateUp() },
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
                    navArgument(Screen.Archive.ARG_AUTO_JOIN) {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) { backStackEntry ->
                ArchiveScreen(
                    onNavigateUp = { navController.navigateUp() },
                    onNavigateToViewer = { kind, title, filePath ->
                        navController.navigate(Screen.Viewer.createRoute(kind.name, title, filePath))
                    },
                    autoJoin = backStackEntry.arguments?.getBoolean(Screen.Archive.ARG_AUTO_JOIN) ?: false,
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
                    onNavigateToContent = {
                        if (!searchSelected) {
                            if (isOnNonFilesTab) navController.popBackStack()
                            navController.navigate(Screen.Search.route) {
                                launchSingleTop = true
                            }
                        }
                    },
                )
            }

            composable(Screen.Search.route) {
                SearchScreen(viewModel = hiltViewModel())
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
                    onNavigateToAppearance = { navController.navigate(Screen.SettingsAppearance.route) },
                    onNavigateToAccount = { navController.navigate(Screen.SettingsAccount.route) },
                    onNavigateToLibraries = { navController.navigate(Screen.SettingsLibraries.route) },
                    onNavigateToDaemon = { navController.navigate(Screen.SettingsDaemon.route) },
                    onNavigateToSearch = { navController.navigate(Screen.SettingsSearch.route) },
                    onNavigateToAbout = { navController.navigate(Screen.SettingsAbout.route) },
                )
            }

            composable(Screen.SettingsAppearance.route) {
                SettingsAppearanceScreen(
                    onNavigateUp = { navController.navigateUp() },
                    viewModel = hiltViewModel(),
                )
            }

            composable(Screen.SettingsAccount.route) {
                SettingsAccountScreen(
                    onNavigateUp = { navController.navigateUp() },
                    onSignOutOfPutio = {
                        navController.navigate(Screen.Auth.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    viewModel = hiltViewModel(),
                )
            }

            composable(Screen.SettingsLibraries.route) {
                SettingsLibrariesScreen(
                    onNavigateUp = { navController.navigateUp() },
                    onNavigateToCalibreTransfers = { navController.navigate(Screen.CalibreTransfers.route) },
                    onNavigateToLanConnections = { navController.navigate(Screen.LanConnections.route) },
                    viewModel = hiltViewModel(),
                )
            }

            composable(Screen.SettingsDaemon.route) {
                SettingsDaemonScreen(
                    onNavigateUp = { navController.navigateUp() },
                    viewModel = hiltViewModel(),
                )
            }

            composable(Screen.SettingsSearch.route) {
                SettingsSearchScreen(
                    onNavigateUp = { navController.navigateUp() },
                    viewModel = hiltViewModel(),
                )
            }

            composable(Screen.SettingsAbout.route) {
                SettingsAboutScreen(
                    onNavigateUp = { navController.navigateUp() },
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
                    onOpenChain = { navController.navigate(Screen.Chain.route) },
                    viewModel = hiltViewModel(),
                    pendingCoverRepository = pendingCoverRepository,
                    pendingCommentsRepository = pendingCommentsRepository,
                    pendingGenerateCoverRepository = pendingGenerateCoverRepository,
                    pendingExtractOrRandomCoverRepository = pendingExtractOrRandomCoverRepository,
                    pendingProtectBookRepository = pendingProtectBookRepository,
                    pendingUnprotectBookRepository = pendingUnprotectBookRepository,
                    pendingSetPageCountRepository = pendingSetPageCountRepository,
                    pendingConvertFormatRepository = pendingConvertFormatRepository,
                    pendingDeletionActionRepository = pendingDeletionActionRepository,
                    pendingEditMetadataRepository = pendingEditMetadataRepository,
                    syncViewModel = syncViewModel,
                )
            }

            // CONTRACT: CHAIN
            composable(Screen.Chain.route) {
                com.damarquez.putz.ui.chain.ChainScreen(
                    onNavigateUp = { navController.navigateUp() },
                    viewModel = hiltViewModel(),
                )
            }
        }
    }
}
