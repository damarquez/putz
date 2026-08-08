package com.damarquez.putz.ui.moveformat

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.damarquez.putz.data.repository.CalibreRepository
import com.damarquez.putz.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

// CONTRACT: MOVE_FORMAT
// Receives a putz://move_format?source_uuid=...&dest_uuid=...&format=...&title=...&author=...
// intent from CalibreAnywhere and submits the MOVE_FORMAT request to the daemon. All
// confirmation UI (marked-format state, destination search, the base/_BKP slot check,
// protection messaging) already happened in CalibreAnywhere before this fired — Putz just
// relays, same bare-Activity shape as FuseBooksActivity.
@AndroidEntryPoint
class MoveFormatActivity : ComponentActivity() {

    @Inject lateinit var calibreRepository: CalibreRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent.data
        val sourceUuid = uri?.getQueryParameter("source_uuid")
        val destUuid = uri?.getQueryParameter("dest_uuid")
        val format = uri?.getQueryParameter("format")
        val title = uri?.getQueryParameter("title") ?: "Book"
        val author = uri?.getQueryParameter("author") ?: ""

        if (sourceUuid.isNullOrBlank() || destUuid.isNullOrBlank() || format.isNullOrBlank()) {
            Toast.makeText(this, "Move format: missing source_uuid/dest_uuid/format", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val googleAccount = settingsRepository.googleTokenFlow.first()
            if (googleAccount.isBlank()) {
                Toast.makeText(this@MoveFormatActivity, "Move format: Google account not set in Putz", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            calibreRepository.sendMoveFormatRequest(
                sourceBookUuid = sourceUuid,
                destBookUuid = destUuid,
                format = format,
                displayTitle = title,
                displayAuthor = author,
                googleAccount = googleAccount,
            )
            Toast.makeText(this@MoveFormatActivity, "Move $format to \"$title\" submitted", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
