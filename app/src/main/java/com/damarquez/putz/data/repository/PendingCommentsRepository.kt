package com.damarquez.putz.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class PendingComments(val uuid: String, val text: String)

@Singleton
class PendingCommentsRepository @Inject constructor() {
    private val _flow = MutableStateFlow<PendingComments?>(null)
    val flow: StateFlow<PendingComments?> = _flow.asStateFlow()

    fun set(uuid: String, text: String) {
        _flow.value = PendingComments(uuid, text)
    }

    fun clear() {
        _flow.value = null
    }
}
