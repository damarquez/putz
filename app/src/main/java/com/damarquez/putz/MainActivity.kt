package com.damarquez.putz

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.damarquez.putz.data.repository.PendingCommentsRepository
import com.damarquez.putz.data.repository.PendingCoverRepository
import com.damarquez.putz.data.repository.PendingGenerateCoverRepository
import com.damarquez.putz.oauth.OAuthManager
import com.damarquez.putz.oauth.PendingMagnetRepository
import com.damarquez.putz.settings.SettingsRepository
import com.damarquez.putz.ui.navigation.AppNavGraph
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
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var oAuthManager: OAuthManager
    @Inject lateinit var pendingMagnetRepository: PendingMagnetRepository
    @Inject lateinit var pendingCoverRepository: PendingCoverRepository
    @Inject lateinit var pendingCommentsRepository: PendingCommentsRepository
    @Inject lateinit var pendingGenerateCoverRepository: PendingGenerateCoverRepository

    private var pendingClipboardAction: PendingClipboardAction? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only process on first creation. On Activity recreation (config change, system restore),
        // the intent is the original launch intent and must not be re-processed.
        if (savedInstanceState == null) {
            handleIntent(intent)
        }
        enableEdgeToEdge()
        setContent {
            val appCategory by settingsRepository.appCategoryFlow
                .collectAsState(initial = AppCategory.NORMAL)
            val appMode by settingsRepository.appModeFlow
                .collectAsState(initial = AppMode.SYSTEM)

            PutzTheme(category = appCategory, mode = appMode) {
                AppNavGraph(
                    settingsRepository = settingsRepository,
                    pendingCoverRepository = pendingCoverRepository,
                    pendingCommentsRepository = pendingCommentsRepository,
                    pendingGenerateCoverRepository = pendingGenerateCoverRepository,
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
            }
        } catch (e: Exception) {
            // Clipboard access can fail on some devices/Android versions; silently ignore.
        }
    }
}
