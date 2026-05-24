package com.damarquez.putz.ui.files

import com.damarquez.putz.data.model.PutioFile

/**
 * An audio file found by recursive scan of a put.io folder, paired with its path
 * relative to the scanned root — e.g. "Disk 1/Chapter 01.mp3".
 * The relative path is used for sorting (matching archive extraction order) and
 * for display so the user can see the folder structure before confirming.
 */
data class FolderAudioFile(
    val file: PutioFile,
    val relativePath: String,
)

sealed class FolderAudioPickerState {
    data class Loading(val folderName: String) : FolderAudioPickerState()
    data class Error(val folderName: String, val message: String) : FolderAudioPickerState()
    data class Ready(val folderName: String, val files: List<FolderAudioFile>) : FolderAudioPickerState()
}
