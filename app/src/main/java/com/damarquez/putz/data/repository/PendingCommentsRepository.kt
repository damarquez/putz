package com.damarquez.putz.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class PendingComments(
    val uuid: String,
    val text: String?,
    val includeComments: Boolean = true,
    val autoAddTags: String? = null,
)

@Singleton
class PendingCommentsRepository @Inject constructor() {
    private val _flow = MutableStateFlow<PendingComments?>(null)
    val flow: StateFlow<PendingComments?> = _flow.asStateFlow()

    fun set(uuid: String, text: String) {
        _flow.value = PendingComments(uuid, text)
    }

    fun setTagsOnly(uuid: String, autoAddTags: String? = null) {
        _flow.value = PendingComments(uuid, null, includeComments = false, autoAddTags = autoAddTags)
    }

    fun clear() {
        _flow.value = null
    }
}
