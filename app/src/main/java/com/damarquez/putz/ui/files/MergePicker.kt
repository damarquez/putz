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
    CBR("CBR_PDF_PACK", "Book.pdf", "Comic archives (CBR/CBZ)"),
    AUDIO("PACK", "Audiobook.m4b", "Audio"),
    EPUBS("EPUB_PACK", "Book.epub", "EPUBs"),
    MOBIS("MOBI_PACK", "Book.mobi", "MOBIs"),
    TEXT("TEXT_PDF_PACK", "Book.pdf", "Text files"),
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
    MergeContentType.TEXT -> MetadataUtils.isJoinableText(name)
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

/**
 * JPEG re-encode quality for the image-to-PDF pack engines (ImagePdfPackJob/CbrPdfPackJob —
 * see CONTRACTS.md "Merge framework"). Only meaningful for PDF output: CBZ/CBR/EPUB embed
 * source image bytes as-is by design (so animated GIFs survive), so a compression choice
 * there would be a no-op. ORIGINAL (null) keeps the pre-existing lossless-but-large behavior.
 */
enum class ImageCompressionLevel(val quality: Int?, val label: String) {
    ORIGINAL(null, "Original"),
    HIGH(85, "High"),
    MEDIUM(70, "Medium"),
    SMALL(50, "Small"),
}

/** Item types whose engine (ImagePdfPackJob/CbrPdfPackJob) honors [ImageCompressionLevel]. */
fun compressionApplicableTo(itemType: String): Boolean =
    itemType == "IMAGE_PDF_PACK" || itemType == "CBR_PDF_PACK"

/** Which [MergeOutputFormat] options (if any) exist for a wire item type already in flight —
 * used by the pending-assembly editor (TransferBrowserSheet) to let a user switch e.g.
 * IMAGE_CBZ_PACK to IMAGE_PDF_PACK before the request is sent, without re-running the whole
 * picker flow. Null when the type isn't part of a switchable output-format family (SINGLE,
 * PACK, ARCHIVE, etc — those have exactly one engine, nothing to switch between). */
fun formatOptionsForItemType(itemType: String): List<MergeOutputFormat>? =
    MergeContentType.entries.map { it.outputFormatOptions() }
        .firstOrNull { options -> options.any { it.itemType == itemType } }

/**
 * A concrete output choice (item type + file name + display label) for a merge-framework
 * folder/archive trigger. Generalizes the per-content-type default in [MergeContentType] and
 * the images-only [ImageOutputFormat] so every content type can offer/confirm a target format.
 */
data class MergeOutputFormat(val itemType: String, val outputFileName: String, val label: String)

fun MergeContentType.outputFormatOptions(): List<MergeOutputFormat> = when (this) {
    MergeContentType.IMAGES -> listOf(
        MergeOutputFormat("IMAGE_CBZ_PACK", "Book.cbz", "CBZ"),
        MergeOutputFormat("IMAGE_PDF_PACK", "Book.pdf", "PDF"),
        MergeOutputFormat("IMAGE_EPUB_PACK", "Book.epub", "EPUB"),
        MergeOutputFormat("IMAGE_CBR_PACK", "Book.cbr", "CBR"),
    )
    MergeContentType.CBR -> listOf(
        MergeOutputFormat("CBR_CBZ_PACK", "Book.cbz", "CBZ"),
        MergeOutputFormat("CBR_PDF_PACK", "Book.pdf", "PDF"),
    )
    MergeContentType.PDFS -> listOf(MergeOutputFormat("PDF_PACK", "Book.pdf", "PDF"))
    MergeContentType.AUDIO -> listOf(MergeOutputFormat("PACK", "Audiobook.m4b", "M4B"))
    MergeContentType.EPUBS -> listOf(MergeOutputFormat("EPUB_PACK", "Book.epub", "EPUB"))
    MergeContentType.MOBIS -> listOf(MergeOutputFormat("MOBI_PACK", "Book.mobi", "MOBI"))
    MergeContentType.TEXT -> listOf(MergeOutputFormat("TEXT_PDF_PACK", "Book.pdf", "PDF"))
}

// Images keep the existing GIF-presence heuristic (GIF survives better in CBZ than flattened
// into a PDF); every other type (including CBR) defaults to its first/best option — CBZ for CBR.
fun MergeContentType.defaultOutputFormat(fileNames: Iterable<String> = emptyList()): MergeOutputFormat {
    val options = outputFormatOptions()
    if (this == MergeContentType.IMAGES && fileNames.any { it.endsWith(".gif", ignoreCase = true) }) {
        return options.first { it.itemType == "IMAGE_CBZ_PACK" }
    }
    return options.first()
}

sealed class MergePickerState {
    data class Loading(val folderName: String) : MergePickerState()
    data class Error(val folderName: String, val message: String) : MergePickerState()
    data class ReadyFlat(val folderName: String, val files: List<MergeCandidateFile>) : MergePickerState()
    data class ReadyGrouped(val folderName: String, val groups: List<MergeCandidateGroup>) : MergePickerState()
}

/**
 * One file found during a full (untyped) recursive scan of a merge folder/archive-dir trigger,
 * classified by content type (null if it doesn't match any mergeable type) so the "what should
 * be merged?" dialog can show per-type counts before the user picks.
 */
data class ScannedMergeFile(
    val file: PutioFile,
    val relativePath: String,
    val contentType: MergeContentType?,
)

/** Result of a single recursive folder/archive-dir walk — backs both the tally shown in
 * [MergeContentTypeChoiceDialog] and the final flat/grouped candidate list once a content type
 * and process mode are chosen, so the tree only needs to be walked once. */
data class FolderScanResult(
    val files: List<ScannedMergeFile>,
    val subfolderCount: Int,
) {
    /** Extension -> count, most common first, for a "jpg (10), cbz (1), pdf (7)" summary line. */
    fun extensionTally(): List<Pair<String, Int>> =
        files.groupingBy { it.file.displayName.substringAfterLast('.', "").lowercase() }
            .eachCount().toList().sortedByDescending { it.second }

    fun countFor(type: MergeContentType): Int = files.count { it.contentType == type }

    /** Whether "subfolders as chapters" would produce more than just a single root chapter for
     * this type — if not, that process-mode question is moot and can be skipped entirely. */
    fun hasSubfoldersFor(type: MergeContentType): Boolean =
        files.any { it.contentType == type && it.relativePath.contains('/') }

    fun flatCandidates(type: MergeContentType): List<MergeCandidateFile> =
        files.filter { it.contentType == type }
            .map { MergeCandidateFile(it.file, it.relativePath) }
            .sortedBy { it.relativePath }

    /** One level of chaptering by the first path segment, deeper nesting preserved within each
     * chapter. Root-level files (no subfolder to belong to) become their own leading chapter,
     * labeled [rootLabel], instead of being silently dropped. */
    fun groupedCandidates(type: MergeContentType, rootLabel: String): List<MergeCandidateGroup> {
        val matched = files.filter { it.contentType == type }
        val rootGroup = matched.filter { !it.relativePath.contains('/') }
            .map { MergeCandidateFile(it.file, it.relativePath) }
            .sortedBy { it.relativePath }
            .let { if (it.isEmpty()) null else MergeCandidateGroup(rootLabel, it) }
        val subfolderGroups = matched.filter { it.relativePath.contains('/') }
            .groupBy { it.relativePath.substringBefore('/') }
            .toList().sortedBy { it.first }
            .map { (chapter, items) ->
                MergeCandidateGroup(
                    chapter,
                    items.map { MergeCandidateFile(it.file, it.relativePath.substringAfter('/')) }.sortedBy { it.relativePath },
                )
            }
        return listOfNotNull(rootGroup) + subfolderGroups
    }
}

/**
 * Pending folder/archive merge trigger, walking through "what content type" then "what
 * process" before the picker sheet shows. [Ready.contentType] is null until the first question
 * is answered; [Ready.scan] is the full-tree scan run once up front, reused for both the tally
 * shown here and the final candidate list once a process mode is picked.
 */
sealed class MergeChoiceState {
    data class Scanning(val folderName: String) : MergeChoiceState()
    data class Error(val folderName: String, val message: String) : MergeChoiceState()
    data class Ready(
        val folderName: String,
        val scan: FolderScanResult,
        val contentType: MergeContentType? = null,
    ) : MergeChoiceState()
}

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
