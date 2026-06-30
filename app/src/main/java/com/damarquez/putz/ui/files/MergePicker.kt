package com.damarquez.putz.ui.files

import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.util.MetadataUtils

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

/**
 * Which engine a folder/archive merge trigger should run, and the predicate used to find its
 * candidate files during the recursive scan. File triggers don't need this — the file's own
 * type already determines the engine (see FileItem.kt's per-type "Merge" menu entries).
 */
enum class MergeContentType(val itemType: String, val outputFileName: String, val label: String) {
    IMAGES("IMAGE_PDF_PACK", "Book.pdf", "Images"),
    PDFS("PDF_PACK", "Book.pdf", "PDFs"),
    CBR("CBR_PDF_PACK", "Book.pdf", "Comic archives (CBR)"),
    AUDIO("PACK", "Audiobook.m4b", "Audio"),
    EPUBS("EPUB_PACK", "Book.epub", "EPUBs"),
    MOBIS("MOBI_PACK", "Book.mobi", "MOBIs"),
}

// Name-based core so non-PutioFile sources (e.g. archive entries) can match without
// constructing a fake PutioFile just to call this.
fun MergeContentType.matchesName(name: String): Boolean = when (this) {
    MergeContentType.IMAGES -> MetadataUtils.isImage(name)
    MergeContentType.PDFS -> MetadataUtils.isPdf(name)
    MergeContentType.CBR -> MetadataUtils.isComicArchive(name)
    MergeContentType.AUDIO -> MetadataUtils.isMultiTrackAudio(name)
    MergeContentType.EPUBS -> MetadataUtils.isEpub(name)
    MergeContentType.MOBIS -> MetadataUtils.isMobi(name)
}

fun MergeContentType.matches(file: PutioFile): Boolean = matchesName(file.displayName)

enum class ImageOutputFormat(val itemType: String, val outputFileName: String, val label: String) {
    PDF("IMAGE_PDF_PACK", "Book.pdf", "PDF"),
    EPUB("IMAGE_EPUB_PACK", "Book.epub", "EPUB"),
    CBZ("IMAGE_CBZ_PACK", "Book.cbz", "CBZ"),
}

fun defaultImageOutputFormat(fileNames: Iterable<String>): ImageOutputFormat =
    if (fileNames.any { it.endsWith(".gif", ignoreCase = true) }) ImageOutputFormat.CBZ
    else ImageOutputFormat.PDF

sealed class MergePickerState {
    data class Loading(val folderName: String) : MergePickerState()
    data class Error(val folderName: String, val message: String) : MergePickerState()
    data class ReadyFlat(val folderName: String, val files: List<MergeCandidateFile>) : MergePickerState()
    data class ReadyGrouped(val folderName: String, val groups: List<MergeCandidateGroup>) : MergePickerState()
}

/**
 * Pending folder/archive merge trigger, walking through "what content type" then "what
 * process" before scanning starts. contentType is null until the first question is answered.
 */
data class MergeProcessChoice(
    val folder: PutioFile,
    val contentType: MergeContentType? = null,
)

/**
 * A resolved merge selection (from any file/folder trigger) waiting to be appended into an
 * existing pending assembly via FilesViewModel.appendMergeToAssembly, once the user picks
 * which assembly. Pass either `files` (flat) or `groups` (chaptered).
 */
data class MergeAssemblyPayload(
    val type: String,
    val fileName: String,
    val displayName: String,
    val files: List<PutioFile>? = null,
    val groups: List<MergeCandidateGroup>? = null,
)
