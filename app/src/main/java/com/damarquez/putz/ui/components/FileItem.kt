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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.data.model.PutioFileType
import com.damarquez.putz.ui.theme.LocalAppStyling
import com.damarquez.putz.util.MetadataUtils

import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.material.icons.filled.Book
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileItem(
    file: PutioFile,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPreview: (PutioFile) -> Unit,
    onSendToCalibre: (PutioFile) -> Unit,
    onSendAsAudiobookPack: (PutioFile) -> Unit,
    onAssembleToCalibre: (PutioFile, isPack: Boolean) -> Unit,
    onDownload: (PutioFile) -> Unit,
    onCopyLink: (PutioFile) -> Unit,
    onDelete: () -> Unit,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    hasPendingAssemblies: Boolean = false,
    isHighlighted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val styling = LocalAppStyling.current
    val cornerRadius = styling.cornerRadiusDp.dp
    val fileType = PutioFileType.from(file.fileType)
    val isEbook = MetadataUtils.isEbook(file.name)
    val isMultiTrackAudio = MetadataUtils.isMultiTrackAudio(file.name)
    val clipboard = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }

    val formatLabel = file.name
        .substringAfterLast('.', "")
        .takeIf { it.isNotEmpty() && it.length <= 5 && !it.contains(' ') }
        ?.uppercase()

    val backgroundColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        isHighlighted -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
        else -> Color.Transparent
    }

    Column(modifier = modifier.fillMaxWidth().background(backgroundColor)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
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
                        fileName = file.name,
                        isFolder = file.isFolder,
                        cornerRadius = cornerRadius.value.toInt(),
                    )
                    if (file.isLocal) {
                        Icon(
                            imageVector = Icons.Default.Smartphone,
                            contentDescription = "Local file",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(14.dp)
                                .align(Alignment.BottomEnd)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(2.dp))
                                .padding(1.dp)
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
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!file.isFolder) {
                    Text(
                        text = buildString {
                            append(fileType.apiValue.lowercase().replaceFirstChar { it.uppercase() })
                            if (file.size > 0) {
                                append("  •  ")
                                append(formatFileSize(file.size))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        if (!file.isFolder) {
                            DropdownMenuItem(
                                text = { Text("Preview") },
                                onClick = {
                                    showMenu = false
                                    onPreview(file)
                                },
                            )
                            HorizontalDivider()
                        }
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
                        if (isEbook) {
                            DropdownMenuItem(
                                text = { Text("Send to Calibre") },
                                onClick = {
                                    showMenu = false
                                    onSendToCalibre(file)
                                },
                            )
                            if (hasPendingAssemblies) {
                                DropdownMenuItem(
                                    text = { Text("Assemble into book") },
                                    onClick = {
                                        showMenu = false
                                        onAssembleToCalibre(file, false)
                                    },
                                )
                            }
                        }
                        if (isMultiTrackAudio) {
                            DropdownMenuItem(
                                text = { Text("Send to Calibre as M4B") },
                                onClick = {
                                    showMenu = false
                                    onSendAsAudiobookPack(file)
                                },
                            )
                            if (hasPendingAssemblies) {
                                DropdownMenuItem(
                                    text = { Text("Assemble into M4B") },
                                    onClick = {
                                        showMenu = false
                                        onAssembleToCalibre(file, true)
                                    },
                                )
                            }
                        }
                        if (isEbook || isMultiTrackAudio) {
                            HorizontalDivider()
                        }
                        DropdownMenuItem(
                            text = { Text("Copy name") },
                            onClick = {
                                showMenu = false
                                clipboard.setText(AnnotatedString(file.name))
                            },
                        )
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
    val isEbook = MetadataUtils.isEbook(fileName)
    val icon: ImageVector = when {
        isFolder -> Icons.Default.Folder
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
            .size(44.dp)
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
            modifier = Modifier.size(24.dp),
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
