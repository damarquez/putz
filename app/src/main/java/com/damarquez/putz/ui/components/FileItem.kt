package com.damarquez.putz.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import android.app.SearchManager
import android.content.Intent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.data.model.PutioFileType
import com.damarquez.putz.ui.theme.LocalAppStyling
import com.damarquez.putz.util.MetadataUtils

import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.Book
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Drop-in replacement for Material3's DropdownMenuItem: same named-parameter shape, but the
// default DropdownMenuItem enforces a 48.dp minimum row height that made this file's popup menu
// (which can have a dozen+ entries) take up far too much vertical space.
@Composable
private fun TightMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) { leadingIcon() }
            Spacer(modifier = Modifier.width(10.dp))
        }
        androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
            text()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileItem(
    file: PutioFile,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPreview: (PutioFile) -> Unit,
    onReplaceCover: (PutioFile) -> Unit,
    onSendAsImagePack: (PutioFile) -> Unit,
    onSendToCalibre: (PutioFile) -> Unit,
    onSendAsAudiobookPack: (PutioFile) -> Unit,
    onSendAsJoinedPdf: (PutioFile) -> Unit,
    onSendAsJoinedEpub: (PutioFile) -> Unit,
    onSendAsJoinedMobi: (PutioFile) -> Unit,
    onSendAsCbrPdf: (PutioFile) -> Unit,
    onSendAsCbrCbz: (PutioFile) -> Unit,
    onSendToPlex: (PutioFile) -> Unit,
    onAssembleSubtitleIntoPlex: (PutioFile) -> Unit,
    onAddSubtitleToMovie: (PutioFile) -> Unit,
    onSendToPlexamp: (PutioFile) -> Unit,
    onMergeFolder: (PutioFile) -> Unit,
    onMergeArchive: (PutioFile) -> Unit,
    hasPendingPlexAssemblies: Boolean = false,
    onRequestPrioritySync: (PutioFile) -> Unit,
    onDownload: (PutioFile) -> Unit,
    onCopyLink: (PutioFile) -> Unit,
    onCopyJson: (PutioFile) -> Unit,
    onDelete: () -> Unit,
    onRename: ((PutioFile) -> Unit)? = null,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    // CONTRACT: selection-type invariant — whether this file is allowed to join the selection
    // in progress (stubs/regular files and "regular remote" (non-stub, undownloaded) files can
    // never be selected together, since they have no bulk operations in common). Irrelevant when
    // isSelectionMode is false. See FilesScreen.kt's selectionIsRemoteOnly.
    isSelectable: Boolean = true,
    selectionCount: Int = 0,
    onSelectNextN: () -> Unit = {},
    onUnselectAllAndSelectNextN: () -> Unit = {},
    onUnselectAllAndSelectNextNotSent: () -> Unit = {},
    onUnselectBeforeHere: () -> Unit = {},
    onUnselectFromHere: () -> Unit = {},
    onReverseSelection: () -> Unit = {},
    isGoogleSignedIn: Boolean = false,
    isHighlighted: Boolean = false,
    isFolderAllSynced: Boolean = false,
    isInSearchResults: Boolean = false,
    isInHiddenFolder: Boolean = false,
    onOpenParentFolder: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val styling = LocalAppStyling.current
    val cornerRadius = styling.cornerRadiusDp.dp
    val fileType = PutioFileType.from(file.fileType)
    // Use displayName for all labelling so .sk_synced is hidden
    val isEbook = MetadataUtils.isEbook(file.displayName)
    val isMultiTrackAudio = MetadataUtils.isMultiTrackAudio(file.displayName)
    val isPdf = MetadataUtils.isPdf(file.displayName)
    val isEpub = MetadataUtils.isEpub(file.displayName)
    val isMobi = MetadataUtils.isMobi(file.displayName)
    val isVideo = fileType == PutioFileType.VIDEO || MetadataUtils.isVideo(file.displayName)
    val isAudio = MetadataUtils.isAudio(file.displayName)
    val isSubtitle = file.displayName.endsWith(".srt", ignoreCase = true) ||
        file.displayName.endsWith(".ass", ignoreCase = true) ||
        file.displayName.endsWith(".sub", ignoreCase = true)
    val isImage = fileType == PutioFileType.IMAGE || MetadataUtils.isImage(file.displayName)
    val isComicArchive = MetadataUtils.isComicArchive(file.displayName)
    val isGenericArchive = MetadataUtils.isArchive(file.displayName) && !isComicArchive
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showSelectionMenu by remember { mutableStateOf(false) }

    // CONTRACT: Putz file state — drives menu visibility; see CONTRACTS.md §19
    // Hidden-folder files are treated like regular remote (dimmed) but have a different menu
    val isRegularRemote = file.isRegularRemote

    // Grayed out and non-interactive: either a plain undownloaded remote file (always dimmed) or,
    // while selecting, any file whose type doesn't match the in-progress selection's type.
    val isDimmed = isRegularRemote || (isSelectionMode && !isSelectable)

    val formatLabel = file.displayName
        .substringAfterLast('.', "")
        .takeIf { it.isNotEmpty() && it.length <= 5 && !it.contains(' ') }
        ?.uppercase()

    val specialBandColor: Color? = when {
        file.isSpecialRootFolder || file.isPutzAttachments || file.isPutzHistory || file.isPutzHidden -> Color(0xFF757575)
        else -> null
    }
    val foregroundColor: Color? = when {
        file.isSpecialRootFolder || file.isPutzAttachments || file.isPutzHistory || file.isPutzHidden -> Color.White
        else -> null
    }

    val backgroundColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        isHighlighted -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
        specialBandColor != null -> specialBandColor
        else -> Color.Transparent
    }

    // CONTRACT: selection-type invariant — an incompatible-with-selection file ignores taps
    // entirely (no toggling, no navigation) while a selection of the other type is in progress.
    val effectiveOnClick = if (isSelectionMode && !isSelectable) { {} } else onClick

    Column(modifier = modifier.fillMaxWidth().background(backgroundColor).then(
        if (isDimmed) Modifier.alpha(0.45f) else Modifier
    )) {
        Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = effectiveOnClick,
                    onLongClick = if (isSelectionMode) {
                        if (isSelectable) {
                            // CONTRACT: bulk-select popup — a plain long-press while already in
                            // selection mode used to just add this one file. Now it opens a popup
                            // instead, since a single accidental long-press bulk-selecting or
                            // bulk-unselecting a large contiguous range (see onSelectNextN/
                            // onUnselectFromHere below) is too easy to trigger by mistake to fire
                            // directly off a long-press with no confirmation step.
                            { showSelectionMenu = true }
                        } else {
                            {}
                        }
                    } else onLongClick,
                )
                .padding(horizontal = 1.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSelectionMode) {
                // onCheckedChange = null: the Checkbox is purely visual here — giving it its own
                // tap handler made it run a second, independent gesture detector alongside the
                // parent Row's combinedClickable above. Long-pressing exactly on the checkbox fired
                // both: this Checkbox's tap-toggle (toggleable doesn't understand "long press", so
                // it toggled anyway) plus the Row's onLongClick opening the selection menu — so an
                // action picked from that menu (e.g. "Reverse selection") applied on top of a
                // selection state the long-press itself had already silently mutated. All taps must
                // route through the Row's own combinedClickable instead.
                Checkbox(
                    checked = isSelected,
                    enabled = isSelectable,
                    onCheckedChange = null,
                )
                Spacer(modifier = Modifier.width(4.dp))
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(44.dp)
            ) {
                Box {
                    FileTypeIcon(
                        fileType = fileType,
                        fileName = file.displayName,
                        isFolder = file.isFolder,
                        cornerRadius = cornerRadius.value.toInt(),
                    )
                    when {
                        isFolderAllSynced -> Icon(
                            imageVector = Icons.Default.CloudDone,
                            contentDescription = "All files synced",
                            tint = Color(0xFF4FC3F7),
                            modifier = Modifier
                                .size(20.dp)
                                .align(Alignment.BottomEnd)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(3.dp))
                                .padding(2.dp)
                        )
                        file.isSynced -> Icon(
                            imageVector = Icons.Default.CloudDone,
                            contentDescription = "Synced locally",
                            tint = if (file.isNewFormatStub) Color(0xFF4FC3F7) else MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .size(20.dp)
                                .align(Alignment.BottomEnd)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(3.dp))
                                .padding(2.dp)
                        )
                        file.isLocal -> Icon(
                            imageVector = Icons.Default.Smartphone,
                            contentDescription = "Local file",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(20.dp)
                                .align(Alignment.BottomEnd)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(3.dp))
                                .padding(2.dp)
                        )
                        file.isLan -> Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = "LAN file",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .size(20.dp)
                                .align(Alignment.BottomEnd)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(3.dp))
                                .padding(2.dp)
                        )
                        file.isTrash -> Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Trash",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .size(20.dp)
                                .align(Alignment.BottomEnd)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(3.dp))
                                .padding(2.dp)
                        )
                        file.isPutzAttachments -> Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Attachments folder",
                            tint = Color(0xFFFF6D00),
                            modifier = Modifier
                                .size(20.dp)
                                .align(Alignment.BottomEnd)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(3.dp))
                                .padding(2.dp)
                        )
                    }
                }
                if (isEbook && formatLabel != null) {
                    Text(
                        text = formatLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = foregroundColor ?: MaterialTheme.colorScheme.onSurface,
                )
                if (!file.isFolder) {
                    Text(
                        text = buildString {
                            if (file.isSynced) {
                                if (file.effectiveSize > 0) append(formatFileSize(file.effectiveSize))
                                val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                val formatted = file.createdAt?.let { raw ->
                                    runCatching {
                                        OffsetDateTime.parse(raw)
                                            .atZoneSameInstant(ZoneId.systemDefault())
                                            .format(fmt)
                                    }.recoverCatching {
                                        LocalDateTime.parse(raw).format(fmt)
                                    }.getOrNull()
                                }
                                if (formatted != null) {
                                    if (isNotEmpty()) append("  •  ")
                                    append(formatted)
                                }
                            } else {
                                append(fileType.apiValue.lowercase().replaceFirstChar { it.uppercase() })
                                if (file.size > 0) {
                                    append("  •  ")
                                    append(formatFileSize(file.size))
                                }
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = foregroundColor?.copy(alpha = 0.7f) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (!isSelectionMode) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More actions",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        if (isInHiddenFolder && !file.isFolder) {
                            // Hidden folder: only download, rename, delete
                            TightMenuItem(
                                text = { Text("Download") },
                                onClick = {
                                    showMenu = false
                                    onDownload(file)
                                },
                            )
                            if (onRename != null) {
                                TightMenuItem(
                                    text = { Text("Rename") },
                                    onClick = {
                                        showMenu = false
                                        onRename(file)
                                    },
                                )
                            }
                            TightMenuItem(
                                text = { Text("Copy JSON") },
                                onClick = {
                                    showMenu = false
                                    onCopyJson(file)
                                },
                            )
                            HorizontalDivider()
                            TightMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                },
                            )
                        } else {
                        if (isInSearchResults && onOpenParentFolder != null && !file.isSpecialRootFolder) {
                            TightMenuItem(
                                text = { Text("Open parent folder") },
                                leadingIcon = {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                                },
                                onClick = {
                                    showMenu = false
                                    onOpenParentFolder()
                                },
                            )
                            HorizontalDivider()
                        }
                        // Audio previews stream progressively (HTTP range requests), so unlike other
                        // formats they don't need a full download/sync first — safe to allow even
                        // on a plain remote put.io file that was never synced to the LAN server.
                        if (!file.isFolder && !file.isTrash && (!isRegularRemote || isAudio)) {
                            TightMenuItem(
                                text = { Text("Preview") },
                                onClick = {
                                    showMenu = false
                                    onPreview(file)
                                },
                            )
                            HorizontalDivider()
                        }
                        if (!file.isLan && !file.isFolder && !isRegularRemote) {
                            TightMenuItem(
                                text = { Text("Download") },
                                onClick = {
                                    showMenu = false
                                    onDownload(file)
                                },
                            )
                            TightMenuItem(
                                text = { Text("Copy download link") },
                                onClick = {
                                    showMenu = false
                                    onCopyLink(file)
                                },
                            )
                            HorizontalDivider()
                        }
                        if (isRegularRemote && !file.isLan && !file.isFolder) {
                            TightMenuItem(
                                text = { Text("Priority sync") },
                                onClick = {
                                    showMenu = false
                                    onRequestPrioritySync(file)
                                },
                            )
                            HorizontalDivider()
                        }
                        if (!isRegularRemote) {
                            if (isEbook) {
                                TightMenuItem(
                                    text = { Text("Send to Calibre") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onSendToCalibre(file)
                                    },
                                )
                            }
                            if (isImage) {
                                TightMenuItem(
                                    text = { Text("Replace book cover") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onReplaceCover(file)
                                    },
                                )
                                TightMenuItem(
                                    // CONTRACT: merge framework — see CONTRACTS.md "Merge framework"
                                    text = { Text("Fuse images…") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onSendAsImagePack(file)
                                    },
                                )
                            }
                            if (isMultiTrackAudio) {
                                TightMenuItem(
                                    // CONTRACT: merge framework — see CONTRACTS.md "Merge framework"
                                    text = { Text("Fuse into M4B…") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onSendAsAudiobookPack(file)
                                    },
                                )
                            }
                            if (isPdf) {
                                TightMenuItem(
                                    // CONTRACT: merge framework — see CONTRACTS.md "Merge framework"
                                    text = { Text("Fuse PDFs…") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onSendAsJoinedPdf(file)
                                    },
                                )
                            }
                            if (isEpub) {
                                TightMenuItem(
                                    // CONTRACT: merge framework — see CONTRACTS.md "Merge framework"
                                    text = { Text("Fuse EPUBs…") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onSendAsJoinedEpub(file)
                                    },
                                )
                            }
                            if (isMobi) {
                                TightMenuItem(
                                    // CONTRACT: merge framework — see CONTRACTS.md "Merge framework"
                                    text = { Text("Fuse MOBIs…") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onSendAsJoinedMobi(file)
                                    },
                                )
                            }
                            if (isComicArchive) {
                                TightMenuItem(
                                    // CONTRACT: merge framework — see CONTRACTS.md "Merge framework"
                                    text = { Text("Convert CBRs to PDF…") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onSendAsCbrPdf(file)
                                    },
                                )
                                TightMenuItem(
                                    // CONTRACT: merge framework — see CONTRACTS.md "Merge framework"
                                    text = { Text("Convert CBRs to CBZ…") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onSendAsCbrCbz(file)
                                    },
                                )
                            }
                            if (isVideo && file.isSynced) {
                                TightMenuItem(
                                    text = { Text("Send to Plex") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onSendToPlex(file)
                                    },
                                )
                            }
                            if (isSubtitle && file.isSynced) {
                                if (hasPendingPlexAssemblies) {
                                    TightMenuItem(
                                        text = { Text("Assemble into movie") },
                                        enabled = isGoogleSignedIn,
                                        onClick = {
                                            showMenu = false
                                            onAssembleSubtitleIntoPlex(file)
                                        },
                                    )
                                }
                                TightMenuItem(
                                    text = { Text("Add subtitle to movie") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onAddSubtitleToMovie(file)
                                    },
                                )
                            }
                            if (isAudio && file.isSynced) {
                                TightMenuItem(
                                    text = { Text("Send to Plexamp") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onSendToPlexamp(file)
                                    },
                                )
                            }
                            val isRegularFolder = file.isFolder && !file.isTrash && !file.isSpecialRootFolder && !file.isPutzAttachments && !file.isPutzHistory && !file.isPutzHidden
                            if (isRegularFolder) {
                                TightMenuItem(
                                    text = { Text("Send folder to Plexamp") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onSendToPlexamp(file)
                                    },
                                )
                                TightMenuItem(
                                    // CONTRACT: merge framework — see CONTRACTS.md "Merge framework"
                                    text = { Text("Fuse folder…") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onMergeFolder(file)
                                    },
                                )
                            }
                            val canBrowseArchive = isGenericArchive && (file.isLocal || file.isLan || file.isSynced)
                            if (canBrowseArchive) {
                                TightMenuItem(
                                    // CONTRACT: merge framework — see CONTRACTS.md "Merge framework"
                                    text = { Text("Fuse archive…") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onMergeArchive(file)
                                    },
                                )
                            }
                            if (isEbook || isImage || isMultiTrackAudio || isPdf || isComicArchive || (isVideo && file.isSynced) || (isSubtitle && file.isSynced) || (isAudio && file.isSynced) || isRegularFolder || canBrowseArchive) {
                                HorizontalDivider()
                            }
                        }
                        TightMenuItem(
                            text = { Text("Copy name") },
                            onClick = {
                                showMenu = false
                                val nameToCopy = if (file.isFolder) {
                                    file.name
                                } else {
                                    // displayName already strips both the size-prefix marker
                                    // and the .sk_synced.<id> suffix for synced files.
                                    val name = file.displayName
                                    val dotIdx = name.lastIndexOf('.')
                                    if (dotIdx >= 0) name.substring(0, dotIdx) else name
                                }
                                clipboard.setText(AnnotatedString(nameToCopy))
                            },
                        )
                        TightMenuItem(
                            text = { Text("Copy ID") },
                            onClick = {
                                showMenu = false
                                clipboard.setText(AnnotatedString(file.id.toString()))
                            },
                        )
                        TightMenuItem(
                            text = { Text("Copy JSON") },
                            onClick = {
                                showMenu = false
                                onCopyJson(file)
                            },
                        )
                        if (!file.isTrash && !file.isSpecialRootFolder && !file.isPutzAttachments && !file.isPutzHistory && !file.isPutzHidden) {
                            TightMenuItem(
                                text = { Text("Search web") },
                                onClick = {
                                    showMenu = false
                                    val query = MetadataUtils.webSearchQuery(file.displayName)
                                    val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                                        putExtra(SearchManager.QUERY, query)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                },
                            )
                        }
                        if (!file.isLan && !file.isTrash && !file.isSpecialRootFolder && !file.isPutzAttachments && !file.isPutzHistory && !file.isPutzHidden && !isRegularRemote) {
                            HorizontalDivider()
                            TightMenuItem(
                                text = {
                                    Text(
                                        text = if (file.isLocal) "Detach from Putz" else "Delete",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                }
                            )
                        }

                        } // end else (not isInHiddenFolder)
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showSelectionMenu,
            onDismissRequest = { showSelectionMenu = false },
        ) {
            TightMenuItem(
                text = { Text("Select next $selectionCount") },
                onClick = {
                    showSelectionMenu = false
                    onSelectNextN()
                },
            )
            TightMenuItem(
                text = { Text("Unselect all and select next $selectionCount") },
                onClick = {
                    showSelectionMenu = false
                    onUnselectAllAndSelectNextN()
                },
            )
            TightMenuItem(
                text = { Text("Unselect all and select next not sent") },
                onClick = {
                    showSelectionMenu = false
                    onUnselectAllAndSelectNextNotSent()
                },
            )
            TightMenuItem(
                text = { Text("Unselect all before here") },
                onClick = {
                    showSelectionMenu = false
                    onUnselectBeforeHere()
                },
            )
            TightMenuItem(
                text = { Text("Unselect all from here") },
                onClick = {
                    showSelectionMenu = false
                    onUnselectFromHere()
                },
            )
            TightMenuItem(
                text = { Text("Reverse selection") },
                onClick = {
                    showSelectionMenu = false
                    onReverseSelection()
                },
            )
        }
        } // end Box (selection long-press menu anchor)

        if (styling.isEink) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun FileTypeIcon(
    fileType: PutioFileType,
    fileName: String,
    isFolder: Boolean,
    cornerRadius: Int,
) {
    val styling = LocalAppStyling.current
    val extension = fileName.substringAfterLast('.', "")
    val customRes: Int? = when {
        isFolder -> FileIconProvider.folder
        else -> FileIconProvider.forExtension(extension)
    }

    if (customRes != null) {
        Icon(
            painter = painterResource(id = customRes),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(35.dp),
        )
        return
    }

    val isEbook = MetadataUtils.isEbook(fileName)
    val icon: ImageVector = when {
        isEbook -> Icons.Default.Book
        fileType == PutioFileType.VIDEO -> Icons.Default.VideoFile
        fileType == PutioFileType.AUDIO -> Icons.Default.AudioFile
        fileType == PutioFileType.IMAGE -> Icons.Default.Image
        fileType == PutioFileType.ARCHIVE -> Icons.Default.FolderZip
        fileType == PutioFileType.PDF -> Icons.Default.Description
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

    val tint = when {
        isFolder -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val bgColor = when {
        isFolder -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val shape = RoundedCornerShape(cornerRadius.dp)

    Box(
        modifier = Modifier
            .size(35.dp)
            .clip(shape)
            .background(bgColor)
            .then(
                if (styling.useBorders) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
                else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}
