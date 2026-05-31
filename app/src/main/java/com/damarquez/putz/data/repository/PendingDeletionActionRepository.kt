package com.damarquez.putz.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class PendingDeletionAction {
    data class MarkBook(val uuid: String) : PendingDeletionAction()
    data class MarkFormats(val uuid: String, val formats: List<String>) : PendingDeletionAction()
    data class ConfirmDeleteBook(val uuid: String) : PendingDeletionAction()
    data class ConfirmDeleteFormats(val uuid: String, val formats: List<String>) : PendingDeletionAction()
    data class Cancel(val uuid: String) : PendingDeletionAction()
}

@Singleton
class PendingDeletionActionRepository @Inject constructor() {
    private val _flow = MutableStateFlow<PendingDeletionAction?>(null)
    val flow: StateFlow<PendingDeletionAction?> = _flow.asStateFlow()

    fun set(action: PendingDeletionAction) {
        _flow.value = action
    }

    fun clear() {
        _flow.value = null
    }
}
