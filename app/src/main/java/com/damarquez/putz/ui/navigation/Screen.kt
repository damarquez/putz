package com.damarquez.putz.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    data object Auth : Screen("auth")

    data object Files : Screen("files/{parentId}/{folderName}") {
        fun createRoute(parentId: Long, folderName: String): String =
            "files/$parentId/${Uri.encode(folderName)}"
        const val ARG_PARENT_ID = "parentId"
        const val ARG_FOLDER_NAME = "folderName"
    }

    data object Transfers : Screen("transfers")

    data object Settings : Screen("settings")
    data object CalibreTransfers : Screen("calibre_transfers")
}
