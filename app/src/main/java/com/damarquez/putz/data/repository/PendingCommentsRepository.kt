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

data class PendingBatchTags(val uuids: List<String>)

@Singleton
class PendingCommentsRepository @Inject constructor() {
    private val _flow = MutableStateFlow<PendingComments?>(null)
    val flow: StateFlow<PendingComments?> = _flow.asStateFlow()

    private val _batchTagsFlow = MutableStateFlow<PendingBatchTags?>(null)
    val batchTagsFlow: StateFlow<PendingBatchTags?> = _batchTagsFlow.asStateFlow()

    fun set(uuid: String, text: String) {
        _flow.value = PendingComments(uuid, text)
    }

    fun setTagsOnly(uuid: String, autoAddTags: String? = null) {
        _flow.value = PendingComments(uuid, null, includeComments = false, autoAddTags = autoAddTags)
    }

    fun clear() {
        _flow.value = null
    }

    fun setBatchTags(uuids: List<String>) {
        _batchTagsFlow.value = PendingBatchTags(uuids)
    }

    fun clearBatchTags() {
        _batchTagsFlow.value = null
    }
}
