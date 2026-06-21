package com.damarquez.putz.ui.viewer

import com.damarquez.putz.util.FileSignatures
import com.damarquez.putz.util.MetadataUtils
import java.io.File

/** A file format with an in-app preview screen. Add a case here + a branch in ViewerScreen's `when` to support a new format; until then, previewFile() falls back to an external app via Intent. */
enum class ViewerKind {
    IMAGE,
    EPUB,
    MOBI,
    PDF,
    TXT,
    CBR,
    RTF,
    DOCX,
    DOC,
    AUDIO;

    companion object {
        fun forFileName(fileName: String): ViewerKind? = when {
            MetadataUtils.isAudio(fileName) -> AUDIO
            MetadataUtils.isImage(fileName) -> IMAGE
            MetadataUtils.isEpub(fileName) -> EPUB
            // AZW3 (KF8) and AZW (pre-KF8) text records use the same PalmDOC layout as legacy MOBI — same extractor/viewer.
            MetadataUtils.isMobi(fileName) || MetadataUtils.isAzw3(fileName) || MetadataUtils.isAzw(fileName) -> MOBI
            MetadataUtils.isPdf(fileName) -> PDF
            MetadataUtils.isTxt(fileName) -> TXT
            MetadataUtils.isComicArchive(fileName) -> CBR
            MetadataUtils.isRtf(fileName) -> RTF
            MetadataUtils.isDocx(fileName) -> DOCX
            MetadataUtils.isDoc(fileName) -> DOC
            else -> null
        }

        /** Detects the kind from real file content; null when sniffing is inconclusive (e.g. plain text, or any archive — see FileSignatures). */
        fun forFile(file: File): ViewerKind? = when (FileSignatures.detect(file)) {
            FileSignatures.IMAGE -> IMAGE
            FileSignatures.EPUB -> EPUB
            FileSignatures.MOBI -> MOBI
            FileSignatures.PDF -> PDF
            FileSignatures.RTF -> RTF
            FileSignatures.DOCX -> DOCX
            FileSignatures.DOC -> DOC
            else -> null
        }
    }
}
