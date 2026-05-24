package com.damarquez.putz.ui.fusion

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.damarquez.putz.data.repository.FuseBooksRequest
import com.damarquez.putz.data.repository.FuseFormatEntry
import com.damarquez.putz.data.repository.FuseMetadata
import com.damarquez.putz.data.repository.CalibreRepository
import com.damarquez.putz.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

// CONTRACT: FUSE_BOOKS
// Receives a fusion intent from CalibreAnywhere and submits a FUSE_BOOKS request to the daemon.
// Intent extra "fusion_payload": JSON string produced by CalibreAnywhere's FusionViewModel.
@AndroidEntryPoint
class FuseBooksActivity : ComponentActivity() {

    @Inject lateinit var calibreRepository: CalibreRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val payload = intent.getStringExtra(EXTRA_FUSION_PAYLOAD)
        if (payload.isNullOrBlank()) {
            Toast.makeText(this, "Fusion: missing payload", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val googleAccount = settingsRepository.googleTokenFlow.first()
            if (googleAccount.isBlank()) {
                Toast.makeText(this@FuseBooksActivity, "Fusion: Google account not set in Putz", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            val request = buildRequest(payload)
            if (request == null) {
                Toast.makeText(this@FuseBooksActivity, "Fusion: could not parse payload", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            calibreRepository.sendFuseBooksRequest(
                request = request,
                displayTitle = request.metadata.title,
                googleAccount = googleAccount,
            )
            Toast.makeText(this@FuseBooksActivity, "Fusion submitted for \"${request.metadata.title}\"", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun buildRequest(payload: String): FuseBooksRequest? {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(payload).jsonObject

            val sourceIds = root["source_book_ids"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.longOrNull }
                ?: return null
            if (sourceIds.size < 2) return null

            val coverSourceId = root["cover_source_book_id"]?.jsonPrimitive?.longOrNull

            val meta = root["metadata"]?.jsonObject ?: return null
            val metadata = FuseMetadata(
                title = meta["title"]?.jsonPrimitive?.contentOrNull ?: "Fused Book",
                authors = meta["authors"]?.jsonPrimitive?.contentOrNull ?: "Unknown",
                publisher = meta["publisher"]?.jsonPrimitive?.contentOrNull,
                pubdate = meta["pubdate"]?.jsonPrimitive?.contentOrNull,
                series = meta["series"]?.jsonPrimitive?.contentOrNull,
                series_index = meta["series_index"]?.jsonPrimitive?.floatOrNull,
                language = meta["language"]?.jsonPrimitive?.contentOrNull,
                rating = meta["rating"]?.jsonPrimitive?.intOrNull,
                tags = meta["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull },
                comments = meta["comments"]?.jsonPrimitive?.contentOrNull,
            )

            val formats = root["formats"]?.jsonArray?.mapNotNull { entry ->
                val obj = entry.jsonObject
                val fmt = obj["format"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val srcId = obj["source_book_id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                FuseFormatEntry(format = fmt, source_book_id = srcId)
            } ?: return null
            if (formats.isEmpty()) return null

            FuseBooksRequest(
                putio_file_id = System.currentTimeMillis(),
                source_book_ids = sourceIds,
                cover_source_book_id = coverSourceId,
                metadata = metadata,
                formats = formats,
            )
        } catch (e: Exception) {
            android.util.Log.e("FuseBooksActivity", "Failed to parse fusion payload", e)
            null
        }
    }

    companion object {
        const val EXTRA_FUSION_PAYLOAD = "fusion_payload"
    }
}
