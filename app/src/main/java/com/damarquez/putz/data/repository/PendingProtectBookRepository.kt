package com.damarquez.putz.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class PendingProtectBook(
    val uuid: String,
    val title: String,
    val author: String,
    val keepCover: Boolean = false,
    val extractCover: Boolean = false,
)

@Singleton
class PendingProtectBookRepository @Inject constructor() {
    private val _flow = MutableStateFlow<PendingProtectBook?>(null)
    val flow: StateFlow<PendingProtectBook?> = _flow.asStateFlow()

    fun set(uuid: String, title: String, author: String, keepCover: Boolean = false, extractCover: Boolean = false) {
        _flow.value = PendingProtectBook(uuid, title, author, keepCover, extractCover)
    }

    fun clear() {
        _flow.value = null
    }
}
