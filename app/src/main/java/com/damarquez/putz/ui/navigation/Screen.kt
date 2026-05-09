package com.damarquez.putz.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    data object Auth : Screen("auth")

    data object Files : Screen("files/{parentId}/{folderName}?highlight={highlightId}&localUri={localUri}") {
        fun createRoute(parentId: Long, folderName: String, highlightId: Long? = null, localUri: String? = null): String {
            var route = "files/$parentId/${Uri.encode(folderName)}"
            val params = mutableListOf<String>()
            if (highlightId != null) params.add("highlight=$highlightId")
            if (localUri != null) params.add("localUri=${Uri.encode(localUri)}")
            
            if (params.isNotEmpty()) {
                route += "?" + params.joinToString("&")
            }
            return route
        }
        const val ARG_PARENT_ID = "parentId"
        const val ARG_FOLDER_NAME = "folderName"
        const val ARG_HIGHLIGHT_ID = "highlightId"
        const val ARG_LOCAL_URI = "localUri"
    }

    data object Transfers : Screen("transfers")

    data object Settings : Screen("settings")
    data object CalibreTransfers : Screen("calibre_transfers")
}
