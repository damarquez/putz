package com.damarquez.putz.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.damarquez.putz.data.model.PutioFile
import com.damarquez.putz.data.model.PutioFileType
import com.damarquez.putz.ui.theme.LocalAppStyling
import com.damarquez.putz.util.MetadataUtils

@Composable
fun FileItem(
    file: PutioFile,
    onClick: () -> Unit,
    onSendToCalibre: (PutioFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val styling = LocalAppStyling.current
    val cornerRadius = styling.cornerRadiusDp.dp
    val fileType = PutioFileType.from(file.fileType)
    val isEbook = MetadataUtils.isEbook(file.name)
    var showMenu by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileTypeIcon(
                fileType = fileType,
                isFolder = file.isFolder,
                cornerRadius = cornerRadius.value.toInt(),
            )

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

            if (isEbook) {
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
                        DropdownMenuItem(
                            text = { Text("Send to Calibre") },
                            onClick = {
                                showMenu = false
                                onSendToCalibre(file)
                            },
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
    isFolder: Boolean,
    cornerRadius: Int,
) {
    val styling = LocalAppStyling.current
    val icon: ImageVector = when {
        isFolder -> Icons.Default.Folder
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
