package com.damarquez.putz.ui.viewer

import com.damarquez.putz.util.MetadataUtils

/** A file format with an in-app preview screen. Add a case here + a branch in ViewerScreen's `when` to support a new format; until then, previewFile() falls back to an external app via Intent. */
enum class ViewerKind {
    IMAGE,
    EPUB,
    MOBI,
    PDF,
    TXT,
    CBR;

    companion object {
        fun forFileName(fileName: String): ViewerKind? = when {
            MetadataUtils.isImage(fileName) -> IMAGE
            MetadataUtils.isEpub(fileName) -> EPUB
            // AZW3 (KF8) text records use the same PalmDOC layout as legacy MOBI — same extractor/viewer.
            MetadataUtils.isMobi(fileName) || MetadataUtils.isAzw3(fileName) -> MOBI
            MetadataUtils.isPdf(fileName) -> PDF
            MetadataUtils.isTxt(fileName) -> TXT
            MetadataUtils.isComicArchive(fileName) -> CBR
            else -> null
        }
    }
}
