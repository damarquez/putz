package com.damarquez.putz.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class PendingUnprotectBook(val uuid: String, val title: String, val author: String)

@Singleton
class PendingUnprotectBookRepository @Inject constructor() {
    private val _flow = MutableStateFlow<PendingUnprotectBook?>(null)
    val flow: StateFlow<PendingUnprotectBook?> = _flow.asStateFlow()

    fun set(uuid: String, title: String, author: String) {
        _flow.value = PendingUnprotectBook(uuid, title, author)
    }

    fun clear() {
        _flow.value = null
    }
}
