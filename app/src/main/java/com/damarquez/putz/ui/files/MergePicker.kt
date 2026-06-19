package com.damarquez.putz.ui.files

import com.damarquez.putz.data.model.PutioFile

/**
 * A candidate file for the generic merge framework, found by recursive scan of a
 * put.io folder (or pre-selected by the user), paired with its path relative to the
 * scanned root — e.g. "Disk 1/Chapter 01.jpg". The relative path is used for sorting
 * and for display so the user can see the folder structure before confirming.
 */
data class MergeCandidateFile(
    val file: PutioFile,
    val relativePath: String,
)

/** One chapter/bookmark in a chaptered merge — one immediate subfolder and its files. */
data class MergeCandidateGroup(
    val label: String,
    val files: List<MergeCandidateFile>,
)

/** How a folder/archive trigger turns its contents into merge input. */
enum class MergeProcessMode {
    FLATTEN,                 // recursively gather all matching files into one unchaptered list
    SUBFOLDERS_AS_CHAPTERS,  // each immediate subfolder becomes one chapter
}

sealed class MergePickerState {
    data class Loading(val folderName: String) : MergePickerState()
    data class Error(val folderName: String, val message: String) : MergePickerState()
    data class ReadyFlat(val folderName: String, val files: List<MergeCandidateFile>) : MergePickerState()
    data class ReadyGrouped(val folderName: String, val groups: List<MergeCandidateGroup>) : MergePickerState()
}

/** Pending "what process" choice for a folder/archive merge trigger, before scanning starts. */
data class MergeProcessChoice(
    val folder: PutioFile,
    val assembleBook: Boolean,
)
