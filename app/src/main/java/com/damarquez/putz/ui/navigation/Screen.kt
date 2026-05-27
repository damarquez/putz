package com.damarquez.putz.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    data object Auth : Screen("auth")

    data object Files : Screen("files/{parentId}/{folderName}?highlight={highlightId}&localUri={localUri}&lanConnectionId={lanConnectionId}&lanPath={lanPath}&tab={tab}") {
        fun createRoute(
            parentId: Long,
            folderName: String,
            highlightId: Long? = null,
            localUri: String? = null,
            lanConnectionId: Long? = null,
            lanPath: String? = null,
            tab: String? = null,
        ): String {
            var route = "files/$parentId/${Uri.encode(folderName)}"
            val params = mutableListOf<String>()
            if (highlightId != null) params.add("highlight=$highlightId")
            if (localUri != null) params.add("localUri=${Uri.encode(localUri)}")
            if (lanConnectionId != null) params.add("lanConnectionId=$lanConnectionId")
            if (lanPath != null) params.add("lanPath=${Uri.encode(lanPath)}")
            if (tab != null) params.add("tab=$tab")
            if (params.isNotEmpty()) route += "?" + params.joinToString("&")
            return route
        }
        const val ARG_PARENT_ID = "parentId"
        const val ARG_FOLDER_NAME = "folderName"
        const val ARG_HIGHLIGHT_ID = "highlightId"
        const val ARG_LOCAL_URI = "localUri"
        const val ARG_LAN_CONNECTION_ID = "lanConnectionId"
        const val ARG_LAN_PATH = "lanPath"
        const val ARG_TAB = "tab"
    }

    data object Archive : Screen("archive/{archiveName}?localUri={localUri}&lanConnectionId={lanConnectionId}&lanPath={lanPath}&putioFileId={putioFileId}&putioStubFileId={putioStubFileId}&putioDownloadUrl={putioDownloadUrl}&putioFileSize={putioFileSize}&putioParentFolderId={putioParentFolderId}&putioIsSynced={putioIsSynced}") {
        fun createRoute(
            archiveName: String,
            localUri: String? = null,
            lanConnectionId: Long? = null,
            lanPath: String? = null,
            putioFileId: Long? = null,
            putioStubFileId: Long? = null,  // CONTRACT: stub convention — actual put.io ID of the stub (differs from putioFileId for synced files)
            putioDownloadUrl: String? = null,
            putioFileSize: Long? = null,
            putioParentFolderId: Long? = null,
            putioIsSynced: Boolean = false,
        ): String {
            var route = "archive/${Uri.encode(archiveName)}"
            val params = mutableListOf<String>()
            if (localUri != null) params.add("localUri=${Uri.encode(localUri)}")
            if (lanConnectionId != null) params.add("lanConnectionId=$lanConnectionId")
            if (lanPath != null) params.add("lanPath=${Uri.encode(lanPath)}")
            if (putioFileId != null) params.add("putioFileId=$putioFileId")
            if (putioStubFileId != null) params.add("putioStubFileId=$putioStubFileId")
            if (putioDownloadUrl != null) params.add("putioDownloadUrl=${Uri.encode(putioDownloadUrl)}")
            if (putioFileSize != null) params.add("putioFileSize=$putioFileSize")
            if (putioParentFolderId != null) params.add("putioParentFolderId=$putioParentFolderId")
            if (putioIsSynced) params.add("putioIsSynced=true")
            if (params.isNotEmpty()) route += "?" + params.joinToString("&")
            return route
        }
        const val ARG_ARCHIVE_NAME = "archiveName"
        const val ARG_LOCAL_URI = "localUri"
        const val ARG_LAN_CONNECTION_ID = "lanConnectionId"
        const val ARG_LAN_PATH = "lanPath"
        const val ARG_PUTIO_FILE_ID = "putioFileId"
        const val ARG_PUTIO_STUB_FILE_ID = "putioStubFileId"  // CONTRACT: stub convention — actual put.io ID of the stub
        const val ARG_PUTIO_DOWNLOAD_URL = "putioDownloadUrl"
        const val ARG_PUTIO_FILE_SIZE = "putioFileSize"
        const val ARG_PUTIO_PARENT_FOLDER_ID = "putioParentFolderId"
        const val ARG_PUTIO_IS_SYNCED = "putioIsSynced"
    }

    data object Transfers : Screen("transfers")

    data object Trash : Screen("trash")

    data object Settings : Screen("settings")
    data object CalibreTransfers : Screen("calibre_transfers")
    data object LanConnections : Screen("lan_connections")
    data object TransferHistory : Screen("transfer_history")
}
