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

// CONTRACT: FUSE_BOOKS / JOIN_BOOKS
// Receives a fusion or join intent from CalibreAnywhere and submits the corresponding request
// to the daemon. Intent extra "fusion_payload" (putz://fuse_books) or "join_payload"
// (putz://join_books): JSON string produced by CalibreAnywhere's FusionViewModel. One activity
// handles both since the parse-build-upload plumbing is identical either way.
@AndroidEntryPoint
class FuseBooksActivity : ComponentActivity() {

    @Inject lateinit var calibreRepository: CalibreRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val fusionPayload = intent.getStringExtra(EXTRA_FUSION_PAYLOAD)
        val joinPayload = intent.getStringExtra(EXTRA_JOIN_PAYLOAD)
        val isJoin = joinPayload != null
        val payload = joinPayload ?: fusionPayload
        val actionLabel = if (isJoin) "Join" else "Fusion"
        if (payload.isNullOrBlank()) {
            Toast.makeText(this, "$actionLabel: missing payload", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val googleAccount = settingsRepository.googleTokenFlow.first()
            if (googleAccount.isBlank()) {
                Toast.makeText(this@FuseBooksActivity, "$actionLabel: Google account not set in Putz", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            val request = buildRequest(payload, isJoin)
            if (request == null) {
                Toast.makeText(this@FuseBooksActivity, "$actionLabel: could not parse payload", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            calibreRepository.sendFuseBooksRequest(
                request = request,
                displayTitle = request.metadata.title,
                googleAccount = googleAccount,
            )
            Toast.makeText(this@FuseBooksActivity, "$actionLabel submitted for \"${request.metadata.title}\"", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun buildRequest(payload: String, isJoin: Boolean): FuseBooksRequest? {
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
                title = meta["title"]?.jsonPrimitive?.contentOrNull ?: (if (isJoin) "Joined Book" else "Fused Book"),
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

            if (isJoin) {
                val joinFormat = root["join_format"]?.jsonPrimitive?.contentOrNull ?: return null
                val joinOutputFormat = root["join_output_format"]?.jsonPrimitive?.contentOrNull
                FuseBooksRequest(
                    action = "JOIN_BOOKS",
                    putio_file_id = -System.currentTimeMillis(),  // negative = fileless, daemon-serialized
                    source_book_ids = sourceIds,
                    cover_source_book_id = coverSourceId,
                    metadata = metadata,
                    join_format = joinFormat,
                    join_output_format = joinOutputFormat,
                )
            } else {
                val formats = root["formats"]?.jsonArray?.mapNotNull { entry ->
                    val obj = entry.jsonObject
                    val fmt = obj["format"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val srcId = obj["source_book_id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                    FuseFormatEntry(format = fmt, source_book_id = srcId)
                } ?: return null
                if (formats.isEmpty()) return null

                FuseBooksRequest(
                    action = "FUSE_BOOKS",
                    putio_file_id = -System.currentTimeMillis(),  // negative = fileless, daemon-serialized
                    source_book_ids = sourceIds,
                    cover_source_book_id = coverSourceId,
                    metadata = metadata,
                    formats = formats,
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("FuseBooksActivity", "Failed to parse ${if (isJoin) "join" else "fusion"} payload", e)
            null
        }
    }

    companion object {
        const val EXTRA_FUSION_PAYLOAD = "fusion_payload"
        const val EXTRA_JOIN_PAYLOAD = "join_payload"
    }
}
