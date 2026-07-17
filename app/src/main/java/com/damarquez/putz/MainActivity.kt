package com.damarquez.putz

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.damarquez.putz.data.repository.PendingCommentsRepository
import com.damarquez.putz.data.repository.PendingConvertFormatRepository
import com.damarquez.putz.data.repository.PendingCoverRepository
import com.damarquez.putz.data.repository.PendingDeletionAction
import com.damarquez.putz.data.repository.PendingDeletionActionRepository
import com.damarquez.putz.data.repository.PendingGenerateCoverRepository
import com.damarquez.putz.data.repository.PendingProtectBookRepository
import com.damarquez.putz.data.repository.PendingUnprotectBookRepository
import com.damarquez.putz.data.repository.PendingEditMetadata
import com.damarquez.putz.data.repository.PendingEditMetadataRepository
import com.damarquez.putz.data.repository.PendingSetPageCountRepository
import com.damarquez.putz.oauth.OAuthManager
import com.damarquez.putz.oauth.PendingMagnetRepository
import com.damarquez.putz.settings.SettingsRepository
import com.damarquez.putz.ui.navigation.AppNavGraph
import com.damarquez.putz.update.AppUpdateManager
import com.damarquez.putz.ui.theme.AppCategory
import com.damarquez.putz.ui.theme.AppMode
import com.damarquez.putz.ui.theme.PutzTheme
import com.damarquez.putz.util.MagnetParser
import com.damarquez.putz.util.MetadataUtils
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private sealed class PendingClipboardAction {
    data class Cover(val uuid: String) : PendingClipboardAction()
    data class Comments(val uuid: String) : PendingClipboardAction()
    data class Tags(val uuid: String, val autoAddTags: String?) : PendingClipboardAction()
    data class BatchTags(val uuids: List<String>) : PendingClipboardAction()
    data class GenerateCover(val uuid: String, val title: String, val author: String) : PendingClipboardAction()
    data class ProtectBook(val uuid: String, val title: String, val author: String, val keepCover: Boolean = false) : PendingClipboardAction()
    data class UnprotectBook(val uuid: String, val title: String, val author: String) : PendingClipboardAction()
    data class SetPageCount(val uuid: String, val pageCount: Int, val title: String? = null, val author: String? = null) : PendingClipboardAction()
    data class ConvertFormat(val uuid: String, val sourceFormat: String, val targetFormat: String, val title: String, val author: String) : PendingClipboardAction()
    data class DeletionAction(val action: PendingDeletionAction) : PendingClipboardAction()
    data class EditMetadata(
        val uuid: String,
        val title: String?,
        val author: String?,
        val comments: String?,
        val tags: String?,
        val pageCount: Int?,
    ) : PendingClipboardAction()
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var appUpdateManager: AppUpdateManager
    @Inject lateinit var oAuthManager: OAuthManager
    @Inject lateinit var pendingMagnetRepository: PendingMagnetRepository
    @Inject lateinit var pendingCoverRepository: PendingCoverRepository
    @Inject lateinit var pendingCommentsRepository: PendingCommentsRepository
    @Inject lateinit var pendingGenerateCoverRepository: PendingGenerateCoverRepository
    @Inject lateinit var pendingProtectBookRepository: PendingProtectBookRepository
    @Inject lateinit var pendingUnprotectBookRepository: PendingUnprotectBookRepository
    @Inject lateinit var pendingSetPageCountRepository: PendingSetPageCountRepository
    @Inject lateinit var pendingConvertFormatRepository: PendingConvertFormatRepository
    @Inject lateinit var pendingDeletionActionRepository: PendingDeletionActionRepository
    @Inject lateinit var pendingEditMetadataRepository: PendingEditMetadataRepository

    private var pendingClipboardAction: PendingClipboardAction? = null

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only process on first creation. On Activity recreation (config change, system restore),
        // the intent is the original launch intent and must not be re-processed.
        if (savedInstanceState == null) {
            handleIntent(intent)
        }
        // Needed so the transfer-delete progress notification (TransferDeleteService) is
        // actually visible. Without it the foreground service still runs — only the notification is hidden.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        enableEdgeToEdge()
        setContent {
            val appCategory by settingsRepository.appCategoryFlow
                .collectAsState(initial = AppCategory.NORMAL)
            val appMode by settingsRepository.appModeFlow
                .collectAsState(initial = AppMode.SYSTEM)

            // CONTRACT: self-update — one-time "Updated to X" toast on the first launch after a
            // self-update installs a newer build; silent otherwise (including a fresh install).
            LaunchedEffect(Unit) {
                appUpdateManager.checkVersionAnnouncement()?.let { message ->
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }

            PutzTheme(category = appCategory, mode = appMode) {
                AppNavGraph(
                    settingsRepository = settingsRepository,
                    pendingCoverRepository = pendingCoverRepository,
                    pendingCommentsRepository = pendingCommentsRepository,
                    pendingGenerateCoverRepository = pendingGenerateCoverRepository,
                    pendingProtectBookRepository = pendingProtectBookRepository,
                    pendingUnprotectBookRepository = pendingUnprotectBookRepository,
                    pendingSetPageCountRepository = pendingSetPageCountRepository,
                    pendingConvertFormatRepository = pendingConvertFormatRepository,
                    pendingDeletionActionRepository = pendingDeletionActionRepository,
                    pendingEditMetadataRepository = pendingEditMetadataRepository,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            processPendingClipboardAction()
        }
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        when {
            uri.scheme == "putz" && uri.host == "oauth" -> oAuthManager.handleRedirect(uri)
            uri.scheme == "putz" && uri.host == "replace_cover" -> {
                val uuid = uri.getQueryParameter("uuid")
                if (uuid != null) pendingClipboardAction = PendingClipboardAction.Cover(uuid)
            }
            uri.scheme == "putz" && uri.host == "update_comments" -> {
                val uuid = uri.getQueryParameter("uuid")
                if (uuid != null) pendingClipboardAction = PendingClipboardAction.Comments(uuid)
            }
            uri.scheme == "putz" && uri.host == "add_tags" -> {
                val uuid = uri.getQueryParameter("uuid")
                val autoAddTags = uri.getQueryParameter("auto_add") ?: uri.getQueryParameter("tags")
                if (uuid != null) pendingClipboardAction = PendingClipboardAction.Tags(uuid, autoAddTags)
            }
            uri.scheme == "putz" && uri.host == "batch_add_tags" -> {
                val uuidsStr = uri.getQueryParameter("uuids")
                val uuids = uuidsStr?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                if (uuids.isNotEmpty()) pendingClipboardAction = PendingClipboardAction.BatchTags(uuids)
            }
            uri.scheme == "putz" && uri.host == "generate_cover" -> {
                val uuid = uri.getQueryParameter("uuid")
                val title = uri.getQueryParameter("title") ?: ""
                val author = uri.getQueryParameter("author") ?: ""
                if (uuid != null) pendingClipboardAction = PendingClipboardAction.GenerateCover(uuid, title, author)
            }
            uri.scheme == "putz" && uri.host == "protect_book" -> {  // CONTRACT: PROTECT_BOOK
                val uuid = uri.getQueryParameter("uuid")
                val title = uri.getQueryParameter("title") ?: ""
                val author = uri.getQueryParameter("author") ?: ""
                val keepCover = uri.getQueryParameter("keep_cover")?.toBoolean() ?: false
                if (uuid != null) pendingClipboardAction = PendingClipboardAction.ProtectBook(uuid, title, author, keepCover)
            }
            uri.scheme == "putz" && uri.host == "unprotect_book" -> {  // CONTRACT: UNPROTECT_BOOK
                val uuid = uri.getQueryParameter("uuid")
                val title = uri.getQueryParameter("title") ?: ""
                val author = uri.getQueryParameter("author") ?: ""
                if (uuid != null) pendingClipboardAction = PendingClipboardAction.UnprotectBook(uuid, title, author)
            }
            uri.scheme == "putz" && uri.host == "set_page_count" -> {
                val uuid = uri.getQueryParameter("uuid")
                val pages = uri.getQueryParameter("pages")?.toIntOrNull()
                if (uuid != null && pages != null && pages > 0)
                    pendingClipboardAction = PendingClipboardAction.SetPageCount(
                        uuid, pages,
                        title = uri.getQueryParameter("title"),
                        author = uri.getQueryParameter("author"),
                    )
            }
            uri.scheme == "putz" && uri.host == "convert_format" -> {  // CONTRACT: CONVERT_FORMAT
                val uuid = uri.getQueryParameter("uuid")
                val sourceFormat = uri.getQueryParameter("source_format")
                val targetFormat = uri.getQueryParameter("target_format")
                val title = uri.getQueryParameter("title") ?: ""
                val author = uri.getQueryParameter("author") ?: ""
                if (uuid != null && sourceFormat != null && targetFormat != null)
                    pendingClipboardAction = PendingClipboardAction.ConvertFormat(uuid, sourceFormat, targetFormat, title, author)
            }
            uri.scheme == "putz" && uri.host == "edit_metadata" -> {  // CONTRACT: edit_metadata deep link
                val uuid = uri.getQueryParameter("uuid")
                if (uuid != null) {
                    val params = uri.queryParameterNames
                    pendingClipboardAction = PendingClipboardAction.EditMetadata(
                        uuid = uuid,
                        title    = if ("title"    in params) uri.getQueryParameter("title")    else null,
                        author   = if ("author"   in params) uri.getQueryParameter("author")   else null,
                        comments = if ("comments" in params) uri.getQueryParameter("comments") else null,
                        tags     = if ("tags"     in params) uri.getQueryParameter("tags")     else null,
                        pageCount = uri.getQueryParameter("pages")?.toIntOrNull(),
                    )
                }
            }
            uri.scheme == "putz" && uri.host == "mark_book_for_deletion" -> {  // CONTRACT: MARK_BOOK_FOR_DELETION
                val uuid = uri.getQueryParameter("uuid")
                if (uuid != null)
                    pendingClipboardAction = PendingClipboardAction.DeletionAction(PendingDeletionAction.MarkBook(
                        uuid,
                        title = uri.getQueryParameter("title"),
                        author = uri.getQueryParameter("author"),
                    ))
            }
            uri.scheme == "putz" && uri.host == "mark_formats_for_deletion" -> {  // CONTRACT: MARK_FORMATS_FOR_DELETION
                val uuid = uri.getQueryParameter("uuid")
                val formats = uri.getQueryParameter("formats")
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                if (uuid != null && formats.isNotEmpty())
                    pendingClipboardAction = PendingClipboardAction.DeletionAction(PendingDeletionAction.MarkFormats(
                        uuid, formats,
                        title = uri.getQueryParameter("title"),
                        author = uri.getQueryParameter("author"),
                    ))
            }
            uri.scheme == "putz" && uri.host == "confirm_delete_book" -> {  // CONTRACT: CONFIRM_DELETE_BOOK
                val uuid = uri.getQueryParameter("uuid")
                if (uuid != null)
                    pendingClipboardAction = PendingClipboardAction.DeletionAction(PendingDeletionAction.ConfirmDeleteBook(
                        uuid,
                        title = uri.getQueryParameter("title"),
                        author = uri.getQueryParameter("author"),
                    ))
            }
            uri.scheme == "putz" && uri.host == "confirm_delete_formats" -> {  // CONTRACT: CONFIRM_DELETE_FORMATS
                val uuid = uri.getQueryParameter("uuid")
                val formats = uri.getQueryParameter("formats")
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                if (uuid != null && formats.isNotEmpty())
                    pendingClipboardAction = PendingClipboardAction.DeletionAction(PendingDeletionAction.ConfirmDeleteFormats(
                        uuid, formats,
                        title = uri.getQueryParameter("title"),
                        author = uri.getQueryParameter("author"),
                    ))
            }
            uri.scheme == "putz" && uri.host == "cancel_delete" -> {  // CONTRACT: CANCEL_DELETION
                val uuid = uri.getQueryParameter("uuid")
                if (uuid != null)
                    pendingClipboardAction = PendingClipboardAction.DeletionAction(PendingDeletionAction.Cancel(
                        uuid,
                        title = uri.getQueryParameter("title"),
                        author = uri.getQueryParameter("author"),
                    ))
            }
            MagnetParser.isMagnetLink(uri.toString()) -> pendingMagnetRepository.set(uri.toString())
        }
    }

    private fun processPendingClipboardAction() {
        val action = pendingClipboardAction ?: return
        pendingClipboardAction = null
        try {
            when (action) {
                is PendingClipboardAction.Cover -> {
                    val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
                    val imageUri = clip?.getItemAt(0)?.uri
                    val mimeType = imageUri?.let { contentResolver.getType(it) }
                    if (imageUri != null && mimeType?.startsWith("image/") == true) {
                        pendingCoverRepository.set(action.uuid, imageUri)
                    }
                }
                is PendingClipboardAction.Comments -> {
                    val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
                    val item = clip?.getItemAt(0)
                    val text = item?.text?.toString()
                    val htmlText = item?.htmlText
                    if (!text.isNullOrBlank() || !htmlText.isNullOrBlank()) {
                        pendingCommentsRepository.set(action.uuid, MetadataUtils.sanitizeHtml(text ?: "", htmlText))
                    }
                }
                is PendingClipboardAction.Tags -> pendingCommentsRepository.setTagsOnly(action.uuid, action.autoAddTags)
                is PendingClipboardAction.BatchTags -> pendingCommentsRepository.setBatchTags(action.uuids)
                is PendingClipboardAction.GenerateCover -> pendingGenerateCoverRepository.set(action.uuid, action.title, action.author)
                is PendingClipboardAction.ProtectBook -> pendingProtectBookRepository.set(action.uuid, action.title, action.author, action.keepCover)
                is PendingClipboardAction.UnprotectBook -> pendingUnprotectBookRepository.set(action.uuid, action.title, action.author)
                is PendingClipboardAction.SetPageCount -> pendingSetPageCountRepository.set(action.uuid, action.pageCount, action.title, action.author)
                is PendingClipboardAction.ConvertFormat -> pendingConvertFormatRepository.set(action.uuid, action.sourceFormat, action.targetFormat, action.title, action.author)
                is PendingClipboardAction.DeletionAction -> pendingDeletionActionRepository.set(action.action)
                is PendingClipboardAction.EditMetadata -> pendingEditMetadataRepository.set(
                    PendingEditMetadata(action.uuid, action.title, action.author, action.comments, action.tags, action.pageCount)
                )
            }
        } catch (e: Exception) {
            // Clipboard access can fail on some devices/Android versions; silently ignore.
        }
    }
}
