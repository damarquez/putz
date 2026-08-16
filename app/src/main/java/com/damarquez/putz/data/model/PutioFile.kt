package com.damarquez.putz.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PutioFile(
    val id: Long,
    val name: String,
    @SerialName("file_type") val fileType: String = "OTHER",
    @SerialName("content_type") val contentType: String? = null,
    val size: Long = 0L,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("parent_id") val parentId: Long = -1L,
    @SerialName("is_shared") val isShared: Boolean = false,
    val icon: String? = null,
    val screenshot: String? = null,
    @SerialName("is_mp4_available") val isMp4Available: Boolean = false,

    // Local file integration
    val isLocal: Boolean = false,
    val localUri: String? = null,

    // LAN file integration
    val isLan: Boolean = false,
    val lanPath: String? = null,
    val lanConnectionId: Long? = null,

    // Trash virtual folder
    val isTrash: Boolean = false,

    // Google Drive integration (read-only)
    val isDrive: Boolean = false,
    val driveFileId: String? = null,

    // Local-only "change display name" override for folders — never sent to put.io.
    // See FilesRepository, which populates this from FolderDisplayNameDao. CONTRACT: none —
    // purely a Putz-side cosmetic layer, the daemon and put.io never see this value.
    val customDisplayName: String? = null,
) {
    val isFolder: Boolean get() = fileType == "FOLDER"
    val isSpecialRootFolder: Boolean get() = id == LOCAL_ROOT_ID || id == LAN_ROOT_ID || id == TRASH_ROOT_ID || id == PUTIO_LOCAL_ROOT_ID || id == DRIVE_ROOT_ID
    val isPutzAttachments: Boolean get() = name == ".putz_attachments"
    val isPutzHistory: Boolean get() = name == ".putz_history"
    val isPutzHidden: Boolean get() = name == ".putz_hidden"

    // CONTRACT: stub convention, Putz file state
    val isSynced: Boolean get() = !isLocal && !isLan && !isTrash && !isFolder && ".sk_synced" in name

    // CONTRACT: ARCHIVE_TO_FOLDER — a synced archive currently being deflated into a real
    // folder tree by the daemon. Deliberately still isSynced=true (name contains ".sk_synced")
    // so it isn't mistaken for a regular unsynced remote file, but every interactive action
    // (browsing, Join archive, Send to Calibre, download, Archive to Folder itself) must be
    // additionally gated on !isDeflating — the underlying stub is mid-replacement.
    val isDeflating: Boolean get() = isSynced && ".sk_synced.deflating." in name

    // CONTRACT: ARCHIVE_TO_FOLDER — "extracting" or "building", embedded in the deflating
    // marker's name. Null if malformed/absent.
    val deflatePhase: String? get() {
        if (!isDeflating) return null
        return Regex("\\.sk_synced\\.deflating\\.(extracting|building)\\.\\d+-\\d+\\.\\d+$")
            .find(name)?.groupValues?.get(1)
    }

    // CONTRACT: ARCHIVE_TO_FOLDER — (done, total) progress counts embedded in the deflating
    // marker's name, for the file-row progress badge. Null if malformed/absent.
    val deflateProgress: Pair<Long, Long>? get() {
        if (!isDeflating) return null
        val m = Regex("\\.sk_synced\\.deflating\\.(?:extracting|building)\\.(\\d+)-(\\d+)\\.\\d+$").find(name) ?: return null
        val done = m.groupValues[1].toLongOrNull() ?: return null
        val total = m.groupValues[2].toLongOrNull() ?: return null
        return done to total
    }

    // CONTRACT: Putz file state — a plain remote put.io file that hasn't been synced/downloaded
    // at all yet (no local stub, no local copy, no LAN copy). Shown dimmed in the file list and,
    // per the file-selection invariant, can never be selected alongside a non-"regular remote"
    // file — see FilesScreen.kt's selectionIsRemoteOnly.
    val isRegularRemote: Boolean get() = !isLocal && !isLan && !isTrash && !isDrive && !isFolder &&
        !isSpecialRootFolder && !isSynced && !isPutzAttachments

    // A folder's user-chosen display name, when set — never used for files (only folders can
    // have one; see the "change display name" feature). name/isSynced/isPutzHidden etc. all
    // keep reading the real put.io `name`, so this never interferes with stub/sentinel detection.
    val hasCustomDisplayName: Boolean get() = isFolder && !customDisplayName.isNullOrBlank()

    // CONTRACT: stub convention — always use displayName (not name) with MetadataUtils.
    // Strips a leading "<size>~~" size-prefix marker (if present), then everything from
    // ".sk_synced" onward.
    val displayName: String get() {
        if (isFolder) return customDisplayName?.takeIf { it.isNotBlank() } ?: name
        if (!isSynced) return name
        return Regex("^\\d+~~").replaceFirst(name, "").substringBefore(".sk_synced")
    }

    // CONTRACT: stub convention — the real file size embedded as a "<size>~~" prefix in the
    // stub filename, when present (stubs created/backfilled after this feature shipped).
    // Null for stubs that don't have it yet — callers should fall back to a network fetch
    // (CalibreRepository.readStubFileSize) in that case.
    val sizeFromStubName: Long? get() = if (isSynced)
        Regex("^(\\d+)~~").find(name)?.groupValues?.get(1)?.toLongOrNull() else null

    // CONTRACT: stub convention — size to sort/display by without a network round-trip.
    // Prefers the embedded stub size prefix, falls back to the regular API-reported size.
    val effectiveSize: Long get() = sizeFromStubName ?: size

    // CONTRACT: stub convention — original file ID encoded in new-format stubs (book.epub.sk_synced.12345)
    // Also used to distinguish new-format (true) from old-format (false) stubs
    val isNewFormatStub: Boolean get() = isSynced &&
        name.substringAfterLast(".sk_synced.", missingDelimiterValue = "").toLongOrNull() != null

    private val embeddedSyncId: Long?
        get() = name.substringAfterLast(".sk_synced.", missingDelimiterValue = "").toLongOrNull()

    // The original put.io file ID: from stub filename for new-format stubs, falls back to id for old-format
    val syncedFileId: Long get() = embeddedSyncId ?: id

    companion object {
        const val TRASH_ROOT_ID = -3000L
        const val LOCAL_ROOT_ID = -2L
        const val LAN_ROOT_ID = -3L
        const val PUTIO_LOCAL_ROOT_ID = -4L  // virtual root for the local put.io repository (accessed via LAN)
        const val DRIVE_ROOT_ID = -5L
    }
}

enum class PutioFileType(val apiValue: String) {
    FOLDER("FOLDER"),
    VIDEO("VIDEO"),
    AUDIO("AUDIO"),
    IMAGE("IMAGE"),
    ARCHIVE("ARCHIVE"),
    PDF("PDF"),
    OTHER("OTHER");

    companion object {
        fun from(value: String): PutioFileType =
            entries.firstOrNull { it.apiValue == value } ?: OTHER
    }
}
