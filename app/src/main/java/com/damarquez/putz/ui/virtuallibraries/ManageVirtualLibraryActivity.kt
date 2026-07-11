package com.damarquez.putz.ui.virtuallibraries

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.damarquez.putz.data.repository.CalibreRepository
import com.damarquez.putz.data.repository.ManageVirtualLibraryRequest
import com.damarquez.putz.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

// CONTRACT: MANAGE_VIRTUAL_LIBRARY
// Receives a VL management intent from CalibreAnywhere and submits a MANAGE_VIRTUAL_LIBRARY
// request to the daemon. The activity finishes immediately — no UI is shown.
// Intent extra "vl_payload": JSON with { operation, name, new_name?, search_query? }
@AndroidEntryPoint
class ManageVirtualLibraryActivity : ComponentActivity() {

    @Inject lateinit var calibreRepository: CalibreRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val payload = intent.getStringExtra(EXTRA_VL_PAYLOAD)
        if (payload.isNullOrBlank()) {
            Toast.makeText(this, "VL manage: missing payload", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val googleAccount = settingsRepository.googleTokenFlow.first()
            if (googleAccount.isBlank()) {
                Toast.makeText(this@ManageVirtualLibraryActivity, "VL manage: Google account not set in Putz", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            val request = buildRequest(payload)
            if (request == null) {
                Toast.makeText(this@ManageVirtualLibraryActivity, "VL manage: could not parse payload", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            calibreRepository.sendManageVirtualLibraryRequest(request, googleAccount)
            finish()
        }
    }

    private fun buildRequest(payload: String): ManageVirtualLibraryRequest? {
        return try {
            val root = JSONObject(payload)
            val operation = root.optString("operation").uppercase()
            val name = root.optString("name").trim()
            if (operation.isBlank() || name.isBlank()) return null

            ManageVirtualLibraryRequest(
                putio_file_id = -System.currentTimeMillis(),  // negative = fileless, daemon-serialized
                operation = operation,
                name = name,
                new_name = root.optString("new_name").takeIf { it.isNotBlank() },
                search_query = root.optString("search_query").takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            android.util.Log.e("ManageVirtualLibraryActivity", "Failed to parse VL payload", e)
            null
        }
    }

    companion object {
        const val EXTRA_VL_PAYLOAD = "vl_payload"
    }
}
