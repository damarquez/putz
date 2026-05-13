package com.damarquez.putz.data.repository

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class PendingCover(val uuid: String, val imageUri: Uri)

@Singleton
class PendingCoverRepository @Inject constructor() {
    private val _flow = MutableStateFlow<PendingCover?>(null)
    val flow: StateFlow<PendingCover?> = _flow.asStateFlow()

    fun set(uuid: String, imageUri: Uri) {
        _flow.value = PendingCover(uuid, imageUri)
    }

    fun clear() {
        _flow.value = null
    }
}
