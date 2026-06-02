package com.damarquez.putz.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class PendingEditMetadata(
    val uuid: String,
    val title: String?,    // null = skip; non-null = set
    val author: String?,   // null = skip; non-null = set
    val comments: String?, // null = skip; "" = clear; non-empty = set
    val tags: String?,     // null = skip; "" = clear; non-empty = set
    val pageCount: Int?,   // null = skip; positive int = set
)

@Singleton
class PendingEditMetadataRepository @Inject constructor() {
    private val _flow = MutableStateFlow<PendingEditMetadata?>(null)
    val flow: StateFlow<PendingEditMetadata?> = _flow.asStateFlow()

    fun set(pending: PendingEditMetadata) { _flow.value = pending }
    fun clear() { _flow.value = null }
}
