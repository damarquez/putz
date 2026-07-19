package com.damarquez.putz.ui.authors

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.damarquez.putz.data.repository.CalibreRepository
import com.damarquez.putz.data.repository.RenameAuthorRequest
import com.damarquez.putz.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

// CONTRACT: RENAME_AUTHOR
// Receives a rename/merge-author intent from CalibreAnywhere and submits a RENAME_AUTHOR
// request to the daemon. The activity finishes immediately — no UI is shown.
// putz://rename_author?author_id=<id>&new_name=<encoded>
@AndroidEntryPoint
class RenameAuthorActivity : ComponentActivity() {

    @Inject lateinit var calibreRepository: CalibreRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        val authorId = uri?.getQueryParameter("author_id")?.toLongOrNull()
        val newName = uri?.getQueryParameter("new_name")
        if (authorId == null || newName.isNullOrBlank()) {
            Toast.makeText(this, "Rename author: missing author_id or new_name", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val googleAccount = settingsRepository.googleTokenFlow.first()
            if (googleAccount.isBlank()) {
                Toast.makeText(this@RenameAuthorActivity, "Rename author: Google account not set in Putz", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            val request = RenameAuthorRequest(
                putio_file_id = -System.currentTimeMillis(),  // negative = fileless, daemon-serialized
                author_id = authorId,
                new_name = newName,
            )
            calibreRepository.sendRenameAuthorRequest(request, googleAccount)
            finish()
        }
    }
}
