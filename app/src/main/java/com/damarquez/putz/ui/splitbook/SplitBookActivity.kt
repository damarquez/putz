package com.damarquez.putz.ui.splitbook

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.damarquez.putz.data.repository.CalibreRepository
import com.damarquez.putz.data.repository.SplitBookMetadata
import com.damarquez.putz.data.repository.SplitBookRequest
import com.damarquez.putz.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

// CONTRACT: SPLIT_BOOK
// Receives a split intent from CalibreAnywhere and submits the corresponding request to the
// daemon. Intent extra "split_payload" (putz://split_book): JSON string produced by
// CalibreAnywhere's SplitBookDialog. Bare no-UI relay — same shape as FuseBooksActivity/
// MoveFormatActivity; all confirmation UI and metadata collection already happened on the
// CalibreAnywhere side.
@AndroidEntryPoint
class SplitBookActivity : ComponentActivity() {

    @Inject lateinit var calibreRepository: CalibreRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val payload = intent.getStringExtra(EXTRA_SPLIT_PAYLOAD)
        if (payload.isNullOrBlank()) {
            Toast.makeText(this, "Split book: missing payload", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val googleAccount = settingsRepository.googleTokenFlow.first()
            if (googleAccount.isBlank()) {
                Toast.makeText(this@SplitBookActivity, "Split book: Google account not set in Putz", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            val request = buildRequest(payload)
            if (request == null) {
                Toast.makeText(this@SplitBookActivity, "Split book: could not parse payload", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            calibreRepository.sendSplitBookRequest(
                request = request,
                displayTitle = request.metadata.title,
                googleAccount = googleAccount,
            )
            Toast.makeText(this@SplitBookActivity, "Split submitted for \"${request.metadata.title}\"", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun buildRequest(payload: String): SplitBookRequest? {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(payload).jsonObject

            val sourceUuid = root["source_book_uuid"]?.jsonPrimitive?.contentOrNull ?: return null
            val formats = root["formats"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: return null
            if (formats.isEmpty()) return null

            val meta = root["metadata"]?.jsonObject ?: return null
            val title = meta["title"]?.jsonPrimitive?.contentOrNull ?: return null
            val authors = meta["authors"]?.jsonPrimitive?.contentOrNull ?: return null
            val metadata = SplitBookMetadata(
                title = title,
                authors = authors,
                comments = meta["comments"]?.jsonPrimitive?.contentOrNull,
                tags = meta["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull },
            )

            val protect = root["protect"]?.jsonPrimitive?.booleanOrNull ?: false

            SplitBookRequest(
                putio_file_id = -System.currentTimeMillis(),  // negative = fileless, book-level-serialized
                source_book_uuid = sourceUuid,
                formats = formats,
                metadata = metadata,
                protect = protect,
            )
        } catch (e: Exception) {
            android.util.Log.e("SplitBookActivity", "Failed to parse split payload", e)
            null
        }
    }

    companion object {
        const val EXTRA_SPLIT_PAYLOAD = "split_payload"
    }
}
