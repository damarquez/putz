package com.damarquez.putz.ui.files

data class LanFolderPickerState(
    val connectionId: Long,
    val rootPath: String,
    val currentPath: String,
    val pathStack: List<String> = emptyList(),
    val folders: List<com.damarquez.putz.data.model.PutioFile> = emptyList(),
    val files: List<com.damarquez.putz.data.model.PutioFile> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val canNavigateUp: Boolean get() = pathStack.isNotEmpty()
    val relativePath: String get() = when {
        rootPath.isEmpty() -> currentPath
        currentPath == rootPath -> ""
        else -> currentPath.removePrefix(rootPath).trimStart('/')
    }
    val breadcrumb: String get() = if (currentPath.isEmpty()) "/" else "/$currentPath"
}
