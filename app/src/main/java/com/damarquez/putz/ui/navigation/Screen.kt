package com.damarquez.putz.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    data object Auth : Screen("auth")

    data object Files : Screen("files/{parentId}/{folderName}?highlight={highlightId}") {
        fun createRoute(parentId: Long, folderName: String, highlightId: Long? = null): String {
            val base = "files/$parentId/${Uri.encode(folderName)}"
            return if (highlightId != null) "$base?highlight=$highlightId" else base
        }
        const val ARG_PARENT_ID = "parentId"
        const val ARG_FOLDER_NAME = "folderName"
        const val ARG_HIGHLIGHT_ID = "highlightId"
    }

    data object Transfers : Screen("transfers")

    data object Settings : Screen("settings")
    data object CalibreTransfers : Screen("calibre_transfers")
}
