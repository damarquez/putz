package com.damarquez.putz.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.DropdownMenuItem
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileItem(
    file: PutioFile,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPreview: (PutioFile) -> Unit,
    onReplaceCover: (PutioFile) -> Unit,
    onSendAsImagePdf: (PutioFile) -> Unit,
    onSendToCalibre: (PutioFile) -> Unit,
    onSendAsAudiobookPack: (PutioFile) -> Unit,
    onAssembleToCalibre: (PutioFile, isPack: Boolean) -> Unit,
    onSendAsJoinedPdf: (PutioFile) -> Unit,
    onAssembleIntoPdf: (PutioFile) -> Unit,
    onSendAsJoinedEpub: (PutioFile) -> Unit,
    onSendAsCbrPdf: (PutioFile) -> Unit,
    onSendToPlex: (PutioFile) -> Unit,
    onAssembleSubtitleIntoPlex: (PutioFile) -> Unit,
    onAddSubtitleToMovie: (PutioFile) -> Unit,
    onSendToPlexamp: (PutioFile) -> Unit,
    onMergeFolder: (PutioFile) -> Unit,
    hasPendingPlexAssemblies: Boolean = false,
    onRequestPrioritySync: (PutioFile) -> Unit,
    onDownload: (PutioFile) -> Unit,
    onCopyLink: (PutioFile) -> Unit,
    onDelete: () -> Unit,
    onRename: ((PutioFile) -> Unit)? = null,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    isGoogleSignedIn: Boolean = false,
    hasPendingAssemblies: Boolean = false,
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
    val isVideo = fileType == PutioFileType.VIDEO || MetadataUtils.isVideo(file.displayName)
    val isAudio = MetadataUtils.isAudio(file.displayName)
    val isSubtitle = file.displayName.endsWith(".srt", ignoreCase = true) ||
        file.displayName.endsWith(".ass", ignoreCase = true) ||
        file.displayName.endsWith(".sub", ignoreCase = true)
    val isImage = fileType == PutioFileType.IMAGE || MetadataUtils.isImage(file.displayName)
    val isComicArchive = MetadataUtils.isComicArchive(file.displayName)
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    // CONTRACT: Putz file state — drives menu visibility; see CONTRACTS.md §19
    // Hidden-folder files are treated like regular remote (dimmed) but have a different menu
    val isRegularRemote = !file.isLocal && !file.isLan && !file.isTrash && !file.isFolder
        && !file.isSpecialRootFolder && !file.isSynced && !file.isPutzAttachments

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

    Column(modifier = modifier.fillMaxWidth().background(backgroundColor).then(
        if (isRegularRemote) Modifier.alpha(0.45f) else Modifier
    )) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (!isRegularRemote) onLongClick else { {} },
                )
                .padding(horizontal = 1.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = foregroundColor ?: MaterialTheme.colorScheme.onSurface,
                )
                if (!file.isFolder) {
                    Text(
                        text = buildString {
                            if (file.isSynced) {
                                if (file.size > 0) append(formatFileSize(file.size))
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
                            DropdownMenuItem(
                                text = { Text("Download") },
                                onClick = {
                                    showMenu = false
                                    onDownload(file)
                                },
                            )
                            if (onRename != null) {
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    onClick = {
                                        showMenu = false
                                        onRename(file)
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
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
                            DropdownMenuItem(
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
                            DropdownMenuItem(
                                text = { Text("Preview") },
                                onClick = {
                                    showMenu = false
                                    onPreview(file)
                                },
                            )
                            HorizontalDivider()
                        }
                        if (!file.isLan && !file.isFolder && !isRegularRemote) {
                            DropdownMenuItem(
                                text = { Text("Download") },
                                onClick = {
                                    showMenu = false
                                    onDownload(file)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Copy download link") },
                                onClick = {
                                    showMenu = false
                                    onCopyLink(file)
                                },
                            )
                            HorizontalDivider()
                        }
                        if (isRegularRemote && !file.isLan && !file.isFolder) {
                            DropdownMenuItem(
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
                                DropdownMenuItem(
                                    text = { Text("Send to Calibre") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onSendToCalibre(file)
                                    },
                                )
                                if (hasPendingAssemblies) {
                                    DropdownMenuItem(
                                        text = { Text("Assemble into book") },
                                        enabled = isGoogleSignedIn,
                                        onClick = {
                                            showMenu = false
                                            onAssembleToCalibre(file, false)
                                        },
                                    )
                                }
                            }
                            if (isImage) {
                                DropdownMenuItem(
                                    text = { Text("Replace book cover") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onReplaceCover(file)
                                    },
                                )
                                DropdownMenuItem(
                                    // CONTRACT: merge framework — see CONTRACTS.md "Merge framework"
                                    text = { Text("Merge") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onSendAsImagePdf(file)
                                    },
                                )
                            }
                            if (isMultiTrackAudio) {
                                DropdownMenuItem(
                                    // CONTRACT: merge framework — see CONTRACTS.md "Merge framework"
                                    text = { Text("Merge") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onSendAsAudiobookPack(file)
                                    },
                                )
                                if (hasPendingAssemblies) {
                                    DropdownMenuItem(
                                        text = { Text("Assemble into M4B") },
                                        enabled = isGoogleSignedIn,
                                        onClick = {
                                            showMenu = false
                                            onAssembleToCalibre(file, true)
                                        },
                                    )
                                }
                            }
                            if (isPdf) {
                                DropdownMenuItem(
                                    // CONTRACT: merge framework — see CONTRACTS.md "Merge framework"
                                    text = { Text("Merge") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onSendAsJoinedPdf(file)
                                    },
                                )
                                if (hasPendingAssemblies) {
                                    DropdownMenuItem(
                                        text = { Text("Assemble into fused PDF") },
                                        enabled = isGoogleSignedIn,
                                        onClick = {
                                            showMenu = false
                                            onAssembleIntoPdf(file)
                                        },
                                    )
                                }
                            }
                            if (isEpub) {
                                DropdownMenuItem(
                                    // CONTRACT: merge framework — see CONTRACTS.md "Merge framework"
                                    text = { Text("Merge") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onSendAsJoinedEpub(file)
                                    },
                                )
                            }
                            if (isComicArchive) {
                                DropdownMenuItem(
                                    // CONTRACT: merge framework — see CONTRACTS.md "Merge framework"
                                    text = { Text("Merge") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onSendAsCbrPdf(file)
                                    },
                                )
                            }
                            if (isVideo && file.isSynced) {
                                DropdownMenuItem(
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
                                    DropdownMenuItem(
                                        text = { Text("Assemble into movie") },
                                        enabled = isGoogleSignedIn,
                                        onClick = {
                                            showMenu = false
                                            onAssembleSubtitleIntoPlex(file)
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Add subtitle to movie") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onAddSubtitleToMovie(file)
                                    },
                                )
                            }
                            if (isAudio && file.isSynced) {
                                DropdownMenuItem(
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
                                DropdownMenuItem(
                                    text = { Text("Send folder to Plexamp") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onSendToPlexamp(file)
                                    },
                                )
                                DropdownMenuItem(
                                    // CONTRACT: merge framework — see CONTRACTS.md "Merge framework"
                                    text = { Text("Merge") },
                                    enabled = isGoogleSignedIn,
                                    onClick = {
                                        showMenu = false
                                        onMergeFolder(file)
                                    },
                                )
                            }
                            if (isEbook || isImage || isMultiTrackAudio || isPdf || isComicArchive || (isVideo && file.isSynced) || (isSubtitle && file.isSynced) || (isAudio && file.isSynced) || isRegularFolder) {
                                HorizontalDivider()
                            }
                        }
                        DropdownMenuItem(
                            text = { Text("Copy name") },
                            onClick = {
                                showMenu = false
                                clipboard.setText(AnnotatedString(file.name))
                            },
                        )
                        if (!file.isTrash && !file.isSpecialRootFolder && !file.isPutzAttachments && !file.isPutzHistory && !file.isPutzHidden) {
                            DropdownMenuItem(
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
                            DropdownMenuItem(
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
