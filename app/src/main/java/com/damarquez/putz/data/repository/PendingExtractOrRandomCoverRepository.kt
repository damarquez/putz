package com.damarquez.putz.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class PendingExtractOrRandomCover(val uuid: String, val title: String, val author: String)

@Singleton
class PendingExtractOrRandomCoverRepository @Inject constructor() {
    private val _flow = MutableStateFlow<PendingExtractOrRandomCover?>(null)
    val flow: StateFlow<PendingExtractOrRandomCover?> = _flow.asStateFlow()

    fun set(uuid: String, title: String, author: String) {
        _flow.value = PendingExtractOrRandomCover(uuid, title, author)
    }

    fun clear() {
        _flow.value = null
    }
}
