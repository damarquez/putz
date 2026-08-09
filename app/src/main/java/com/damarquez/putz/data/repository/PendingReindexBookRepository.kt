package com.damarquez.putz.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class PendingReindexBook(val uuid: String, val title: String, val author: String)

// CONTRACT: REINDEX_BOOK — see CONTRACTS.md §37
@Singleton
class PendingReindexBookRepository @Inject constructor() {
    private val _flow = MutableStateFlow<PendingReindexBook?>(null)
    val flow: StateFlow<PendingReindexBook?> = _flow.asStateFlow()

    fun set(uuid: String, title: String, author: String) {
        _flow.value = PendingReindexBook(uuid, title, author)
    }

    fun clear() {
        _flow.value = null
    }
}
