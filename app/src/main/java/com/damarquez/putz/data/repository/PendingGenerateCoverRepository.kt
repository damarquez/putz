package com.damarquez.putz.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class PendingGenerateCover(val uuid: String, val title: String, val author: String)

@Singleton
class PendingGenerateCoverRepository @Inject constructor() {
    private val _flow = MutableStateFlow<PendingGenerateCover?>(null)
    val flow: StateFlow<PendingGenerateCover?> = _flow.asStateFlow()

    fun set(uuid: String, title: String, author: String) {
        _flow.value = PendingGenerateCover(uuid, title, author)
    }

    fun clear() {
        _flow.value = null
    }
}
